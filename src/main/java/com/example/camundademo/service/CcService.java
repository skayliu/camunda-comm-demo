package com.example.camundademo.service;

import com.example.camundademo.entity.BizCcRecord;
import com.example.camundademo.persistence.BizCcRecordManager;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.runtime.VariableInstance;
import org.camunda.bpm.engine.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 传阅服务。
 *
 * <p>传阅是“送达”，不是任务：不建 userTask、不阻塞流程、不进待办/已办。
 * 所有写操作都只改业务表，<b>永远不会</b>调用 {@code taskService.complete()}。</p>
 */
@Service
public class CcService {

    private static final Logger log = LoggerFactory.getLogger(CcService.class);

    private static final int MAX_PAGE_SIZE = 200;
    /** 传阅附言最大长度，与前端输入框 maxlength 一致 */
    private static final int NOTE_MAX_LENGTH = 200;
    private static final String ADMIN_GROUP = "camunda-admin";

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private IdentityService identityService;

    // ===== 写 =====

    /**
     * 运行时动态加传阅。
     *
     * @param processInstanceId 流程实例ID
     * @param taskId            来源待办ID，可为空（流程级传阅）
     * @param senderId          发起人（当前登录用户，由 Controller 从认证上下文取）
     * @param userIds           待阅人列表
     * @param ccType            NODE / INST / MANUAL，默认 MANUAL
     * @param note              传阅附言，可为空；超过 200 字截断
     */
    public Map<String, Object> addCc(String processInstanceId, String taskId, String senderId,
                                     List<String> userIds, String ccType, String note) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            throw new BusinessException("缺少流程实例ID");
        }
        if (!canSend(processInstanceId, taskId, senderId)) {
            throw new BusinessException("只有当前审批人、流程发起人或管理员可以发起传阅");
        }
        Set<String> candidates = new LinkedHashSet<>();
        if (userIds != null) {
            for (String userId : userIds) {
                if (userId != null && !userId.isBlank() && !userId.trim().equals(senderId)) {
                    candidates.add(userId.trim());
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new BusinessException("请选择待阅人");
        }

        Task task = taskId == null || taskId.isBlank() ? null : findTask(taskId);
        String title = resolveTitle(processInstanceId, task);
        String processDefinitionKey = resolveProcessDefinitionKey(processInstanceId, task);
        String taskDefinitionKey = task == null ? null : task.getTaskDefinitionKey();
        String type = ccType == null || ccType.isBlank() ? BizCcRecord.TYPE_MANUAL : ccType;
        String noteText = normalizeNote(note);

        List<String> added = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        tx(commandContext -> {
            BizCcRecordManager manager = commandContext.getSession(BizCcRecordManager.class);
            Date now = new Date();
            for (String userId : candidates) {
                if (manager.countActive(processInstanceId, userId) > 0) {
                    skipped.add(userId);
                    continue;
                }
                BizCcRecord record = new BizCcRecord();
                record.setId(java.util.UUID.randomUUID().toString().replace("-", ""));
                record.setProcessInstanceId(processInstanceId);
                record.setTaskId(taskId);
                record.setProcessDefinitionKey(processDefinitionKey);
                record.setTaskDefinitionKey(taskDefinitionKey);
                record.setTitle(title);
                record.setCcUserId(userId);
                record.setSenderUserId(senderId);
                record.setCcType(type);
                record.setNote(noteText);
                record.setRead(0);
                record.setRevoked(0);
                record.setCreateTime(now);
                record.setUpdateTime(now);
                manager.insert(record);
                added.add(userId);
            }
            return null;
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "ok");
        result.put("added", added);
        result.put("skipped", skipped);
        result.put("addedCount", added.size());
        result.put("skippedCount", skipped.size());
        log.info("动态传阅：process={}, sender={}, added={}, skipped={}",
                processInstanceId, senderId, added, skipped);
        return result;
    }

    /** 兼容旧调用：不带附言的传阅 */
    public Map<String, Object> addCc(String processInstanceId, String taskId, String senderId,
                                     List<String> userIds, String ccType) {
        return addCc(processInstanceId, taskId, senderId, userIds, ccType, null);
    }

    /** 附言只做去空白与限长，不参与任何流转判断 */
    private String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        String text = note.trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > NOTE_MAX_LENGTH ? text.substring(0, NOTE_MAX_LENGTH) : text;
    }

    /** 标记单条已读：只有待阅人本人可以操作；已撤回的记录忽略 */
    public boolean markRead(String recordId, String userId) {
        BizCcRecord record = findById(recordId);
        if (record == null) {
            throw new BusinessException("传阅记录不存在");
        }
        if (!userId.equals(record.getCcUserId())) {
            throw new BusinessException("无权操作他人的传阅记录");
        }
        if (isRevoked(record)) {
            return false;
        }
        if (isRead(record)) {
            return true;
        }
        Date now = new Date();
        int updated = tx(commandContext -> commandContext.getSession(BizCcRecordManager.class)
                .markRead(recordId, userId, now));
        return updated > 0;
    }

    /** 批量标记已读，只处理本人的未读记录 */
    public int batchMarkRead(List<String> recordIds, String userId) {
        if (recordIds == null || recordIds.isEmpty()) {
            return 0;
        }
        Date now = new Date();
        return tx(commandContext -> commandContext.getSession(BizCcRecordManager.class)
                .batchMarkRead(recordIds, userId, now));
    }

    /** 撤回传阅：只有发送人可以撤回，且对方未读；软删除保留审计痕迹 */
    public void revoke(String recordId, String senderId) {
        BizCcRecord record = findById(recordId);
        if (record == null) {
            throw new BusinessException("传阅记录不存在");
        }
        if (!senderId.equals(record.getSenderUserId())) {
            throw new BusinessException("只有发送人可以撤回传阅");
        }
        if (isRead(record)) {
            throw new BusinessException("对方已读，无法撤回");
        }
        if (isRevoked(record)) {
            return;
        }
        Date now = new Date();
        int updated = tx(commandContext -> commandContext.getSession(BizCcRecordManager.class)
                .revoke(recordId, senderId, now, BizCcRecord.REVOKE_REASON_REVOKED));
        if (updated == 0) {
            throw new BusinessException("传阅状态已变化，请刷新后重试");
        }
    }

    /** 流程驳回/撤回时让未读传阅失效（监听器内也会调用，这里供接口兜底） */
    public int invalidateByProcessInstance(String processInstanceId) {
        Date now = new Date();
        return tx(commandContext -> commandContext.getSession(BizCcRecordManager.class)
                .invalidateByProcInst(processInstanceId, now));
    }

    // ===== 查询 =====

    /**
     * 我的待阅：status = UNREAD / READ / REVOKED / ALL，默认 UNREAD。
     *
     * <p>READ 只取真正已读的记录，不含已撤回/已失效；REVOKED 取撤回与失效两种，
     * 与列表状态列的“已撤回 / 已失效”标签口径一致。</p>
     */
    public Map<String, Object> listMine(String userId, String status, String keyword, int page, int size) {
        List<BizCcRecord> all = tx(commandContext ->
                commandContext.getSession(BizCcRecordManager.class).findByCcUser(userId));
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        String mode = status == null || status.isBlank() ? "UNREAD" : status.trim().toUpperCase();
        List<BizCcRecord> filtered = new ArrayList<>();
        for (BizCcRecord record : all) {
            if (!matchMineStatus(record, mode)) {
                continue;
            }
            if (!kw.isEmpty() && !matches(record, kw)) {
                continue;
            }
            filtered.add(record);
        }
        filtered.sort(Comparator
                .comparing((BizCcRecord r) -> isRead(r) || isRevoked(r) ? 1 : 0)
                .thenComparing(BizCcRecord::getCreateTime, Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, HistoricProcessInstance> processes = batchProcesses(filtered);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BizCcRecord record : filtered) {
            rows.add(toVO(record, userId, processes.get(record.getProcessInstanceId())));
        }
        Map<String, Object> result = pageResult(rows, page, size);
        long unread = countUnread(userId);
        result.put("unread", unread);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", all.size());
        stats.put("unread", unread);
        stats.put("revoked", all.stream().filter(this::isRevoked).count());
        result.put("stats", stats);
        return result;
    }

    public long countUnread(String userId) {
        return tx(commandContext -> commandContext.getSession(BizCcRecordManager.class).countUnread(userId));
    }

    /**
     * 我发出的传阅：按发送人反查。
     *
     * <p>与 {@link #listMine} 是同一张表的两个视角：那边回答“谁传给了我”，
     * 这边回答“我传给了谁、对方读了没有”。发送人在这里只能撤回仍未读的记录，
     * 已读的必须保留——它是“确实送达”的证据，撤回会让审计说不清楚。</p>
     *
     * @param status ALL / UNREAD / READ / REVOKED，默认 ALL
     */
    public Map<String, Object> listSent(String senderId, String status, String keyword, int page, int size) {
        List<BizCcRecord> all = tx(commandContext ->
                commandContext.getSession(BizCcRecordManager.class).findBySender(senderId));
        String mode = status == null || status.isBlank() ? "ALL" : status.trim().toUpperCase();
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<BizCcRecord> filtered = new ArrayList<>();
        for (BizCcRecord record : all) {
            if (!matchSentStatus(record, mode)) {
                continue;
            }
            if (!kw.isEmpty() && !matchesSent(record, kw)) {
                continue;
            }
            filtered.add(record);
        }
        // 撤回/失效的沉底，再按发送时间倒序：审计要留痕，但不该压住仍在流转的传阅
        filtered.sort(Comparator
                .comparing((BizCcRecord r) -> isRevoked(r) ? 1 : 0)
                .thenComparing(BizCcRecord::getCreateTime, Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, HistoricProcessInstance> processes = batchProcesses(filtered);
        // 同一个待阅人/发送人可能出现在很多行，显示名按账号缓存，避免逐行查身份库
        Map<String, String> nameCache = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BizCcRecord record : filtered) {
            rows.add(toSentVO(record, senderId, processes.get(record.getProcessInstanceId()), nameCache));
        }
        Map<String, Object> result = pageResult(rows, page, size);
        result.put("stats", sentStats(all));
        return result;
    }

    /**
     * 我发出的传阅 · 按流程实例聚合。
     *
     * <p>{@link #listSent} 回答“这条传阅谁读了”，本方法回答“这个流程我传给了谁、谁还没读”。
     * 同一批数据、同一套权限，只是把视角从「单条记录」换成「一次流程」，
     * 便于一次性看清某个流程的送达情况并批量撤回仍未读的传阅。</p>
     *
     * <p>组级筛选口径：UNREAD = 组内仍有未读；READ = 组内无未读且至少一条已读；
     * REVOKED = 组内存在撤回/失效记录；ALL = 不过滤。</p>
     */
    public Map<String, Object> listSentGroupedByProcess(String senderId, String status, String keyword,
                                                        int page, int size) {
        List<BizCcRecord> all = tx(commandContext ->
                commandContext.getSession(BizCcRecordManager.class).findBySender(senderId));
        String mode = status == null || status.isBlank() ? "ALL" : status.trim().toUpperCase();
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();

        Map<String, List<BizCcRecord>> grouped = new LinkedHashMap<>();
        for (BizCcRecord record : all) {
            String pid = record.getProcessInstanceId() == null ? "" : record.getProcessInstanceId();
            grouped.computeIfAbsent(pid, key -> new ArrayList<>()).add(record);
        }
        Map<String, HistoricProcessInstance> processes = batchProcesses(all);
        Map<String, String> nameCache = new LinkedHashMap<>();
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<BizCcRecord>> entry : grouped.entrySet()) {
            List<BizCcRecord> items = new ArrayList<>(entry.getValue());
            items.sort(Comparator.comparing(BizCcRecord::getCreateTime,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            Map<String, Object> vo = toProcessGroupVO(senderId, entry.getKey(), items,
                    processes.get(entry.getKey()), nameCache);
            if (!kw.isEmpty() && !matchesGroup(vo, items, kw)) {
                continue;
            }
            if (!matchGroupStatus(vo, mode)) {
                continue;
            }
            groups.add(vo);
        }
        // 还有未读的流程排前面：这是唯一需要“现在就去处理”的组
        groups.sort(Comparator
                .comparing((Map<String, Object> g) -> groupUnread(g) > 0 ? 0 : 1)
                .thenComparing(g -> (Date) g.get("lastSendTime"),
                        Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, Object> result = pageResult(groups, page, size);
        Map<String, Object> stats = sentStats(all);
        stats.put("processCount", grouped.size());
        // 全量口径：不随筛选条件变化，避免“筛到只剩一个流程”时把总数算成 1
        stats.put("pendingProcessCount", all.stream()
                .filter(r -> !isRead(r) && !isRevoked(r))
                .map(BizCcRecord::getProcessInstanceId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count());
        result.put("stats", stats);
        return result;
    }

    /**
     * 按流程批量撤回：只撤自己发出、对方未读、尚未撤回的记录。
     *
     * <p>必须是发送人本人操作，且在同一个事务里完成，避免撤一半失败留下半截状态。</p>
     */
    public Map<String, Object> revokeBatchByProcess(String processInstanceId, String senderId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            throw new BusinessException("缺少流程实例ID");
        }
        List<BizCcRecord> records = tx(commandContext ->
                commandContext.getSession(BizCcRecordManager.class).findByProcInst(processInstanceId));
        List<String> targets = new ArrayList<>();
        for (BizCcRecord record : records) {
            if (senderId != null && senderId.equals(record.getSenderUserId())
                    && !isRead(record) && !isRevoked(record)) {
                targets.add(record.getId());
            }
        }
        if (targets.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("result", "ok");
            empty.put("revoked", 0);
            empty.put("skipped", records.size());
            return empty;
        }
        Date now = new Date();
        int updated = tx(commandContext -> {
            BizCcRecordManager manager = commandContext.getSession(BizCcRecordManager.class);
            int count = 0;
            for (String id : targets) {
                count += manager.revoke(id, senderId, now, BizCcRecord.REVOKE_REASON_REVOKED);
            }
            return count;
        });
        log.info("按流程批量撤回传阅：process={}, sender={}, revoked={}/{}",
                processInstanceId, senderId, updated, targets.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "ok");
        result.put("revoked", updated);
        result.put("skipped", records.size() - updated);
        return result;
    }

    /** 一组传阅（同一流程实例）的聚合视图：人员明细 + 阅读进度 */
    private Map<String, Object> toProcessGroupVO(String senderId, String processInstanceId,
                                                 List<BizCcRecord> items, HistoricProcessInstance hpi,
                                                 Map<String, String> nameCache) {
        long read = 0;
        long unread = 0;
        long revoked = 0;
        Date first = null;
        Date last = null;
        String title = null;
        String processDefinitionKey = null;
        List<Map<String, Object>> members = new ArrayList<>();
        List<Map<String, Object>> unreadMembers = new ArrayList<>();
        List<String> revokeIds = new ArrayList<>();
        for (BizCcRecord record : items) {
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("id", record.getId());
            member.put("ccUserId", record.getCcUserId());
            member.put("ccUserName", displayName(record.getCcUserId(), nameCache));
            member.put("ccType", record.getCcType());
            member.put("ccTypeName", ccTypeName(record.getCcType()));
            member.put("note", record.getNote());
            member.put("taskDefinitionKey", record.getTaskDefinitionKey());
            member.put("read", isRead(record));
            member.put("readTime", record.getReadTime());
            member.put("revoked", isRevoked(record));
            member.put("revokeReason", record.getRevokeReason());
            member.put("revokeTime", record.getRevokeTime());
            member.put("createTime", record.getCreateTime());
            boolean unreadNow = !isRead(record) && !isRevoked(record);
            member.put("canRevoke", senderId != null && senderId.equals(record.getSenderUserId()) && unreadNow);
            members.add(member);

            if (unreadNow) {
                unread++;
                unreadMembers.add(member);
                revokeIds.add(record.getId());
            } else if (isRead(record)) {
                read++;
            } else {
                revoked++;
            }
            if (record.getCreateTime() != null) {
                if (first == null || record.getCreateTime().before(first)) {
                    first = record.getCreateTime();
                }
                if (last == null || record.getCreateTime().after(last)) {
                    last = record.getCreateTime();
                }
            }
            if (title == null && record.getTitle() != null && !record.getTitle().isBlank()) {
                title = record.getTitle();
            }
            if (processDefinitionKey == null) {
                processDefinitionKey = record.getProcessDefinitionKey();
            }
        }
        long delivered = read + unread;
        boolean running = hpi != null && hpi.getEndTime() == null;
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("processInstanceId", processInstanceId);
        vo.put("processDefinitionKey", processDefinitionKey);
        vo.put("title", title);
        vo.put("processRunning", running);
        vo.put("processEndTime", hpi == null ? null : hpi.getEndTime());
        vo.put("processState", running ? "RUNNING" : "FINISHED");
        vo.put("total", items.size());
        vo.put("read", read);
        vo.put("unread", unread);
        vo.put("revoked", revoked);
        vo.put("readRate", delivered == 0 ? 0 : Math.round(read * 100.0 / delivered));
        vo.put("firstSendTime", first);
        vo.put("lastSendTime", last);
        vo.put("unreadMembers", unreadMembers);
        vo.put("revokeIds", revokeIds);
        vo.put("members", members);
        return vo;
    }

    private boolean matchGroupStatus(Map<String, Object> group, String mode) {
        long read = ((Number) group.get("read")).longValue();
        long unread = groupUnread(group);
        long revoked = ((Number) group.get("revoked")).longValue();
        return switch (mode) {
            case "UNREAD" -> unread > 0;
            case "READ" -> unread == 0 && read > 0;
            case "REVOKED" -> revoked > 0;
            default -> true;
        };
    }

    private long groupUnread(Map<String, Object> group) {
        Object value = group.get("unread");
        return value instanceof Number number ? number.longValue() : 0;
    }

    /** 组内关键词匹配：标题、流程key、实例号、待阅人账号/姓名、节点 */
    private boolean matchesGroup(Map<String, Object> group, List<BizCcRecord> items, String keyword) {
        if (contains((String) group.get("title"), keyword)
                || contains((String) group.get("processDefinitionKey"), keyword)
                || contains((String) group.get("processInstanceId"), keyword)) {
            return true;
        }
        for (BizCcRecord record : items) {
            if (contains(record.getCcUserId(), keyword)
                    || contains(record.getTaskDefinitionKey(), keyword)
                    || contains(record.getCcType(), keyword)
                    || contains(record.getNote(), keyword)) {
                return true;
            }
        }
        Object unread = group.get("unreadMembers");
        if (unread instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object name = map.get("ccUserName");
                    if (name != null && name.toString().toLowerCase().contains(keyword)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Map<String, Object> sentStats(List<BizCcRecord> all) {
        long unread = all.stream().filter(r -> !isRead(r) && !isRevoked(r)).count();
        long read = all.stream().filter(this::isRead).count();
        long revoked = all.stream().filter(this::isRevoked).count();
        long delivered = read + unread;
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", all.size());
        stats.put("unread", unread);
        stats.put("read", read);
        stats.put("revoked", revoked);
        stats.put("readRate", delivered == 0 ? 0 : Math.round(read * 100.0 / delivered));
        return stats;
    }

    /** 流程下的全部传阅记录（跟踪图/详情展示用） */
    public List<Map<String, Object>> listByProcess(String processInstanceId, String viewerId) {
        List<BizCcRecord> records = tx(commandContext ->
                commandContext.getSession(BizCcRecordManager.class).findByProcInst(processInstanceId));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BizCcRecord record : records) {
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("id", record.getId());
            vo.put("ccUserId", record.getCcUserId());
            vo.put("ccUserName", displayName(record.getCcUserId()));
            vo.put("senderUserId", record.getSenderUserId());
            vo.put("senderName", displayName(record.getSenderUserId()));
            vo.put("ccType", record.getCcType());
            vo.put("note", record.getNote());
            vo.put("taskDefinitionKey", record.getTaskDefinitionKey());
            vo.put("read", isRead(record));
            vo.put("readTime", record.getReadTime());
            vo.put("revoked", isRevoked(record));
            vo.put("revokeReason", record.getRevokeReason());
            vo.put("revokeTime", record.getRevokeTime());
            vo.put("createTime", record.getCreateTime());
            vo.put("canRevoke", viewerId != null && viewerId.equals(record.getSenderUserId())
                    && !isRead(record) && !isRevoked(record));
            rows.add(vo);
        }
        return rows;
    }

    /** 待阅人可见流程跟踪：传阅不是审批人，但“副本送达后可查看”是基本要求 */
    public boolean hasCcRecord(String processInstanceId, String userId) {
        if (processInstanceId == null || userId == null) {
            return false;
        }
        return tx(commandContext -> commandContext.getSession(BizCcRecordManager.class)
                .existsByProcInstAndUser(processInstanceId, userId));
    }

    // ===== 内部 =====

    private BizCcRecord findById(String recordId) {
        return tx(commandContext -> commandContext.getSession(BizCcRecordManager.class).findById(recordId));
    }

    private boolean canSend(String processInstanceId, String taskId, String senderId) {
        if (senderId == null || senderId.isBlank()) {
            return false;
        }
        if (taskId != null && !taskId.isBlank()) {
            Task task = findTask(taskId);
            if (task != null && senderId.equals(task.getAssignee())) {
                return true;
            }
        }
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (hpi != null && senderId.equals(hpi.getStartUserId())) {
            return true;
        }
        return identityService.createUserQuery().userId(senderId).memberOfGroup(ADMIN_GROUP).count() > 0;
    }

    private Task findTask(String taskId) {
        return taskService.createTaskQuery().taskId(taskId).singleResult();
    }

    private String resolveTitle(String processInstanceId, Task task) {
        Object title = null;
        try {
            List<VariableInstance> variables = runtimeService.createVariableInstanceQuery()
                    .processInstanceIdIn(processInstanceId).variableName("title").list();
            if (!variables.isEmpty()) {
                title = variables.get(0).getValue();
            }
        } catch (RuntimeException ignored) {
            // 实例已结束：继续走历史/兜底
        }
        if (title != null && !String.valueOf(title).isBlank()) {
            return String.valueOf(title);
        }
        ProcessInstance live = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (live != null && live.getBusinessKey() != null && !live.getBusinessKey().isBlank()) {
            return live.getBusinessKey();
        }
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (hpi != null && hpi.getBusinessKey() != null && !hpi.getBusinessKey().isBlank()) {
            return hpi.getBusinessKey();
        }
        if (task != null && task.getName() != null) {
            return task.getName();
        }
        return hpi == null ? "流程传阅" : "流程传阅 · " + definitionKey(hpi.getProcessDefinitionId());
    }

    private String resolveProcessDefinitionKey(String processInstanceId, Task task) {
        if (task != null) {
            return definitionKey(task.getProcessDefinitionId());
        }
        ProcessInstance live = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (live != null) {
            return definitionKey(live.getProcessDefinitionId());
        }
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        return hpi == null ? null : definitionKey(hpi.getProcessDefinitionId());
    }

    private String definitionKey(String processDefinitionId) {
        return processDefinitionId == null ? null : processDefinitionId.split(":")[0];
    }

    private Map<String, HistoricProcessInstance> batchProcesses(List<BizCcRecord> records) {
        Set<String> ids = new LinkedHashSet<>();
        for (BizCcRecord record : records) {
            if (record.getProcessInstanceId() != null) {
                ids.add(record.getProcessInstanceId());
            }
        }
        Map<String, HistoricProcessInstance> result = new LinkedHashMap<>();
        for (String id : ids) {
            HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(id).singleResult();
            if (hpi != null) {
                result.put(hpi.getId(), hpi);
            }
        }
        return result;
    }

    private Map<String, Object> toVO(BizCcRecord record, String viewerId, HistoricProcessInstance hpi) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", record.getId());
        vo.put("processInstanceId", record.getProcessInstanceId());
        vo.put("taskId", record.getTaskId());
        vo.put("processDefinitionKey", record.getProcessDefinitionKey());
        vo.put("taskDefinitionKey", record.getTaskDefinitionKey());
        vo.put("title", record.getTitle());
        vo.put("ccType", record.getCcType());
        vo.put("ccTypeName", ccTypeName(record.getCcType()));
        vo.put("note", record.getNote());
        vo.put("sender", record.getSenderUserId());
        vo.put("senderName", displayName(record.getSenderUserId()));
        vo.put("read", isRead(record));
        vo.put("readTime", record.getReadTime());
        vo.put("revoked", isRevoked(record));
        vo.put("revokeReason", record.getRevokeReason());
        vo.put("revokeTime", record.getRevokeTime());
        vo.put("createTime", record.getCreateTime());
        boolean running = hpi != null && hpi.getEndTime() == null;
        vo.put("processRunning", running);
        vo.put("processEndTime", hpi == null ? null : hpi.getEndTime());
        vo.put("processState", running ? "RUNNING" : "FINISHED");
        vo.put("canRead", viewerId != null && viewerId.equals(record.getCcUserId())
                && !isRead(record) && !isRevoked(record));
        vo.put("canRevoke", viewerId != null && viewerId.equals(record.getSenderUserId())
                && !isRead(record) && !isRevoked(record));
        return vo;
    }

    private String ccTypeName(String ccType) {
        if (BizCcRecord.TYPE_INST.equals(ccType)) {
            return "流程传阅";
        }
        if (BizCcRecord.TYPE_MANUAL.equals(ccType)) {
            return "手动传阅";
        }
        return "节点传阅";
    }

    /** 发送人视角的单行：待阅人是主角，状态回答“对方到底看了没有” */
    private Map<String, Object> toSentVO(BizCcRecord record, String senderId,
                                         HistoricProcessInstance hpi, Map<String, String> nameCache) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", record.getId());
        vo.put("processInstanceId", record.getProcessInstanceId());
        vo.put("processDefinitionKey", record.getProcessDefinitionKey());
        vo.put("taskDefinitionKey", record.getTaskDefinitionKey());
        vo.put("title", record.getTitle());
        vo.put("ccType", record.getCcType());
        vo.put("ccTypeName", ccTypeName(record.getCcType()));
        vo.put("note", record.getNote());
        vo.put("ccUserId", record.getCcUserId());
        vo.put("ccUserName", displayName(record.getCcUserId(), nameCache));
        vo.put("sender", record.getSenderUserId());
        vo.put("senderName", displayName(record.getSenderUserId(), nameCache));
        vo.put("read", isRead(record));
        vo.put("readTime", record.getReadTime());
        vo.put("revoked", isRevoked(record));
        vo.put("revokeReason", record.getRevokeReason());
        vo.put("revokeTime", record.getRevokeTime());
        vo.put("createTime", record.getCreateTime());
        boolean running = hpi != null && hpi.getEndTime() == null;
        vo.put("processRunning", running);
        vo.put("processEndTime", hpi == null ? null : hpi.getEndTime());
        vo.put("processState", running ? "RUNNING" : "FINISHED");
        // 已读不能撤回：对方确实看到过，撤回会抹掉送达证据
        vo.put("canRevoke", senderId != null && senderId.equals(record.getSenderUserId())
                && !isRead(record) && !isRevoked(record));
        return vo;
    }

    /** 我的待阅的状态筛选：UNREAD 不含已读与已撤回；READ 只取已读；REVOKED 含撤回与失效 */
    private boolean matchMineStatus(BizCcRecord record, String mode) {
        return switch (mode) {
            case "UNREAD" -> !isRead(record) && !isRevoked(record);
            case "READ" -> isRead(record) && !isRevoked(record);
            case "REVOKED" -> isRevoked(record);
            default -> true;
        };
    }

    private boolean matchSentStatus(BizCcRecord record, String mode) {
        return switch (mode) {
            case "UNREAD" -> !isRead(record) && !isRevoked(record);
            case "READ" -> isRead(record);
            case "REVOKED" -> isRevoked(record);
            default -> true;
        };
    }

    private boolean matchesSent(BizCcRecord record, String keyword) {
        return contains(record.getTitle(), keyword)
                || contains(record.getCcUserId(), keyword)
                || contains(record.getTaskDefinitionKey(), keyword)
                || contains(record.getCcType(), keyword)
                || contains(record.getNote(), keyword);
    }

    /** 带缓存的显示名：同一账号在列表里只查一次身份库 */
    private String displayName(String userId, Map<String, String> cache) {
        if (userId == null) {
            return null;
        }
        String cached = cache.get(userId);
        if (cached != null) {
            return cached;
        }
        String name = displayName(userId);
        cache.put(userId, name);
        return name;
    }

    private String displayName(String userId) {
        if (userId == null) {
            return null;
        }
        try {
            org.camunda.bpm.engine.identity.User user = identityService.createUserQuery()
                    .userId(userId).singleResult();
            if (user != null && user.getFirstName() != null && !user.getFirstName().isBlank()) {
                return user.getFirstName();
            }
        } catch (RuntimeException ignored) {
            // 身份库不可用时退回账号
        }
        return userId;
    }

    private boolean matches(BizCcRecord record, String keyword) {
        return contains(record.getTitle(), keyword)
                || contains(record.getSenderUserId(), keyword)
                || contains(record.getTaskDefinitionKey(), keyword)
                || contains(record.getCcType(), keyword)
                || contains(record.getNote(), keyword);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private boolean isRead(BizCcRecord record) {
        return record.getRead() != null && record.getRead() == 1;
    }

    private boolean isRevoked(BizCcRecord record) {
        return record.getRevoked() != null && record.getRevoked() == 1;
    }

    private Map<String, Object> pageResult(List<Map<String, Object>> all, int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int from = Math.min((p - 1) * s, all.size());
        int to = Math.min(from + s, all.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", all.size());
        result.put("page", p);
        result.put("size", s);
        result.put("list", all.subList(from, to));
        return result;
    }

    private <T> T tx(Command<T> command) {
        ProcessEngineConfigurationImpl configuration =
                (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
        return configuration.getCommandExecutorTxRequired().execute(command);
    }
}
