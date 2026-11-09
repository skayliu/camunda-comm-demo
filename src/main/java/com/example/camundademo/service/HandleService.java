package com.example.camundademo.service;

import com.example.camundademo.entity.BizHandleRecord;
import com.example.camundademo.persistence.BizHandleRecordManager;
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
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阅办（承办）服务。
 *
 * <p>阅办 = “看完去做、反馈结果，但不拍板”。它有办理时限与办理结果，
 * 同样<b>不创建引擎任务、不阻塞主流程</b>；办理结果只存业务表，
 * 不写流程变量，避免引擎误把承办意见当成审批结论。</p>
 */
@Service
public class HandleService {

    private static final Logger log = LoggerFactory.getLogger(HandleService.class);

    private static final int MAX_PAGE_SIZE = 200;
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

    /** 发起阅办：审批人/发起人/管理员可以指定承办人、办理要求与时限 */
    public Map<String, Object> addHandle(String processInstanceId, String taskId, String senderId,
                                         String assigneeId, String instruction, Date deadline) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            throw new BusinessException("缺少流程实例ID");
        }
        if (assigneeId == null || assigneeId.isBlank()) {
            throw new BusinessException("请选择承办人");
        }
        if (assigneeId.equals(senderId)) {
            throw new BusinessException("不能把阅办派给自己");
        }
        if (!canSend(processInstanceId, taskId, senderId)) {
            throw new BusinessException("只有当前审批人、流程发起人或管理员可以发起阅办");
        }

        Task task = taskId == null || taskId.isBlank() ? null : findTask(taskId);
        long active = tx(commandContext -> commandContext.getSession(BizHandleRecordManager.class)
                .countActive(processInstanceId, taskId, assigneeId));
        if (active > 0) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("result", "ok");
            result.put("duplicated", true);
            result.put("message", "该承办人已有未完成的阅办，无需重复派发");
            return result;
        }

        String title = task != null && task.getName() != null
                ? task.getName()
                : resolveProcessTitle(processInstanceId);
        String processDefinitionKey = resolveProcessDefinitionKey(processInstanceId, task);
        String taskDefinitionKey = task == null ? null : task.getTaskDefinitionKey();

        String recordId = tx(commandContext -> {
            BizHandleRecordManager manager = commandContext.getSession(BizHandleRecordManager.class);
            BizHandleRecord record = new BizHandleRecord();
            record.setId(java.util.UUID.randomUUID().toString().replace("-", ""));
            record.setProcessInstanceId(processInstanceId);
            record.setTaskId(taskId);
            record.setProcessDefinitionKey(processDefinitionKey);
            record.setTaskDefinitionKey(taskDefinitionKey);
            record.setTitle(title);
            record.setAssigneeId(assigneeId);
            record.setSenderId(senderId);
            record.setInstruction(instruction);
            record.setDeadline(deadline);
            record.setHandleStatus(BizHandleRecord.STATUS_PENDING);
            record.setRevoked(0);
            record.setCreateTime(new Date());
            record.setUpdateTime(new Date());
            manager.insert(record);
            return record.getId();
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "ok");
        result.put("id", recordId);
        result.put("duplicated", false);
        log.info("阅办已发起：process={}, task={}, sender={}, assignee={}, deadline={}",
                processInstanceId, taskId, senderId, assigneeId, deadline);
        return result;
    }

    /** 承办人开始办理：PENDING → HANDLING（幂等） */
    public void start(String recordId, String userId) {
        BizHandleRecord record = requireOwnAssignee(recordId, userId);
        if (record.isFinished() || isRevoked(record)) {
            return;
        }
        if (BizHandleRecord.STATUS_PENDING.equals(record.getHandleStatus())) {
            tx(commandContext -> commandContext.getSession(BizHandleRecordManager.class)
                    .updateStatus(recordId, BizHandleRecord.STATUS_HANDLING, null, null));
        }
    }

    /** 承办人提交办理结果 */
    public void submitResult(String recordId, String userId, String result) {
        if (result == null || result.isBlank()) {
            throw new BusinessException("请填写办理结果");
        }
        BizHandleRecord record = requireOwnAssignee(recordId, userId);
        if (isRevoked(record)) {
            throw new BusinessException("该阅办已撤回或已失效");
        }
        if (record.isFinished()) {
            throw new BusinessException("该阅办已完成，无需重复提交");
        }
        Date now = new Date();
        tx(commandContext -> commandContext.getSession(BizHandleRecordManager.class)
                .updateStatus(recordId, BizHandleRecord.STATUS_DONE, result.trim(), now));
        log.info("阅办已完成：record={}, assignee={}", recordId, userId);
    }

    /** 发起人撤回阅办：只需软撤回，不硬删记录 */
    public void revoke(String recordId, String senderId) {
        BizHandleRecord record = findById(recordId);
        if (record == null) {
            throw new BusinessException("阅办记录不存在");
        }
        if (!senderId.equals(record.getSenderId())) {
            throw new BusinessException("只有发起人可以撤回阅办");
        }
        if (record.isFinished()) {
            throw new BusinessException("对方已提交办理结果，无法撤回");
        }
        if (isRevoked(record)) {
            return;
        }
        Date now = new Date();
        int updated = tx(commandContext -> commandContext.getSession(BizHandleRecordManager.class)
                .revoke(recordId, senderId, now, BizHandleRecord.REVOKE_REASON_REVOKED));
        if (updated == 0) {
            throw new BusinessException("阅办状态已变化，请刷新后重试");
        }
    }

    /** 流程结束/撤销：未完成阅办失效 */
    public int invalidateByProcessInstance(String processInstanceId) {
        Date now = new Date();
        return tx(commandContext -> commandContext.getSession(BizHandleRecordManager.class)
                .invalidateByProcInst(processInstanceId, now));
    }

    /** 定时任务：逾期自动标记 */
    public int markOverdue() {
        Date now = new Date();
        return tx(commandContext -> commandContext.getSession(BizHandleRecordManager.class)
                .markOverdue(now));
    }

    // ===== 查询 =====

    /** 我的承办：status = TODO / PENDING / HANDLING / OVERDUE / DONE / ALL */
    public Map<String, Object> listMine(String userId, String status, String keyword, int page, int size) {
        List<BizHandleRecord> all = tx(commandContext ->
                commandContext.getSession(BizHandleRecordManager.class).findByAssignee(userId));
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<BizHandleRecord> filtered = new ArrayList<>();
        for (BizHandleRecord record : all) {
            if (!matchesStatus(record, status)) {
                continue;
            }
            if (!kw.isEmpty() && !matches(record, kw)) {
                continue;
            }
            filtered.add(record);
        }
        filtered.sort(Comparator
                .comparing((BizHandleRecord r) -> isDone(r) || isRevoked(r) ? 1 : 0)
                .thenComparing(BizHandleRecord::getCreateTime, Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, HistoricProcessInstance> processes = batchProcesses(filtered);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BizHandleRecord record : filtered) {
            rows.add(toVO(record, userId, processes.get(record.getProcessInstanceId())));
        }
        Map<String, Object> result = pageResult(rows, page, size);
        long pending = countPending(userId);
        result.put("pending", pending);
        result.put("stats", stats(all));
        return result;
    }

    /**
     * 我发出的阅办：按发起人反查，供「我的传阅 · 阅办」使用。
     *
     * <p>口径与「我的承办」对称：status = ALL / PENDING / HANDLING / OVERDUE / DONE / REVOKED，
     * 逾期仍按 deadline 实时推算；只有未完成且未撤回的记录才给撤回入口。</p>
     */
    public Map<String, Object> listSent(String userId, String status, String keyword, int page, int size) {
        List<BizHandleRecord> all = tx(commandContext ->
                commandContext.getSession(BizHandleRecordManager.class).findBySender(userId));
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<BizHandleRecord> filtered = new ArrayList<>();
        for (BizHandleRecord record : all) {
            if (!matchesStatus(record, status)) {
                continue;
            }
            if (!kw.isEmpty() && !matchesSent(record, kw)) {
                continue;
            }
            filtered.add(record);
        }
        filtered.sort(Comparator
                .comparing((BizHandleRecord r) -> isDone(r) || isRevoked(r) ? 1 : 0)
                .thenComparing(BizHandleRecord::getCreateTime, Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, HistoricProcessInstance> processes = batchProcesses(filtered);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BizHandleRecord record : filtered) {
            rows.add(toVO(record, userId, processes.get(record.getProcessInstanceId())));
        }
        Map<String, Object> result = pageResult(rows, page, size);
        result.put("stats", sentStats(all));
        return result;
    }

    public long countPending(String userId) {
        return tx(commandContext -> commandContext.getSession(BizHandleRecordManager.class)
                .countPending(userId));
    }

    /** 流程下的阅办记录（跟踪图/详情展示用） */
    public List<Map<String, Object>> listByProcess(String processInstanceId, String viewerId) {
        List<BizHandleRecord> records = tx(commandContext ->
                commandContext.getSession(BizHandleRecordManager.class).findByProcInst(processInstanceId));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BizHandleRecord record : records) {
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("id", record.getId());
            vo.put("assignee", record.getAssigneeId());
            vo.put("assigneeName", displayName(record.getAssigneeId()));
            vo.put("sender", record.getSenderId());
            vo.put("senderName", displayName(record.getSenderId()));
            vo.put("instruction", record.getInstruction());
            vo.put("deadline", record.getDeadline());
            vo.put("handleStatus", effectiveStatus(record));
            vo.put("handleResult", record.getHandleResult());
            vo.put("handleTime", record.getHandleTime());
            vo.put("revoked", isRevoked(record));
            vo.put("revokeReason", record.getRevokeReason());
            vo.put("createTime", record.getCreateTime());
            vo.put("canRevoke", viewerId != null && viewerId.equals(record.getSenderId())
                    && !isDone(record) && !isRevoked(record));
            rows.add(vo);
        }
        return rows;
    }

    /** 承办人可见流程跟踪 */
    public boolean hasHandleRecord(String processInstanceId, String userId) {
        if (processInstanceId == null || userId == null) {
            return false;
        }
        return tx(commandContext -> commandContext.getSession(BizHandleRecordManager.class)
                .existsByProcInstAndUser(processInstanceId, userId));
    }

    // ===== 内部 =====

    private BizHandleRecord findById(String recordId) {
        return tx(commandContext -> commandContext.getSession(BizHandleRecordManager.class).findById(recordId));
    }

    private BizHandleRecord requireOwnAssignee(String recordId, String userId) {
        BizHandleRecord record = findById(recordId);
        if (record == null) {
            throw new BusinessException("阅办记录不存在");
        }
        if (!userId.equals(record.getAssigneeId())) {
            throw new BusinessException("只有承办人本人可以办理");
        }
        return record;
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

    private String resolveProcessTitle(String processInstanceId) {
        try {
            List<VariableInstance> variables = runtimeService.createVariableInstanceQuery()
                    .processInstanceIdIn(processInstanceId).variableName("title").list();
            if (!variables.isEmpty() && variables.get(0).getValue() != null) {
                return String.valueOf(variables.get(0).getValue());
            }
        } catch (RuntimeException ignored) {
            // 实例已结束
        }
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (hpi != null && hpi.getBusinessKey() != null && !hpi.getBusinessKey().isBlank()) {
            return hpi.getBusinessKey();
        }
        return "流程阅办";
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

    private boolean matchesStatus(BizHandleRecord record, String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return true;
        }
        String effective = effectiveStatus(record);
        if ("TODO".equalsIgnoreCase(status)) {
            return !isDone(record) && !isRevoked(record);
        }
        return status.equalsIgnoreCase(effective);
    }

    /** 展示状态：逾期由时限实时推算，不依赖定时任务是否已经跑过 */
    private String effectiveStatus(BizHandleRecord record) {
        if (isRevoked(record)) {
            return "REVOKED";
        }
        if (isDone(record)) {
            return BizHandleRecord.STATUS_DONE;
        }
        if (record.getDeadline() != null && record.getDeadline().before(new Date())) {
            return BizHandleRecord.STATUS_OVERDUE;
        }
        return record.getHandleStatus();
    }

    private Map<String, Object> stats(List<BizHandleRecord> all) {
        long pending = 0;
        long handling = 0;
        long overdue = 0;
        long done = 0;
        for (BizHandleRecord record : all) {
            String status = effectiveStatus(record);
            if (BizHandleRecord.STATUS_DONE.equals(status)) {
                done++;
            } else if (BizHandleRecord.STATUS_OVERDUE.equals(status)) {
                overdue++;
            } else if (BizHandleRecord.STATUS_HANDLING.equals(status)) {
                handling++;
            } else if (!"REVOKED".equals(status)) {
                pending++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", pending);
        result.put("handling", handling);
        result.put("overdue", overdue);
        result.put("done", done);
        return result;
    }

    /** 发出视角的统计：撤回/失效单列，完成率只按仍然有效的阅办计算 */
    private Map<String, Object> sentStats(List<BizHandleRecord> all) {
        long pending = 0;
        long handling = 0;
        long overdue = 0;
        long done = 0;
        long revoked = 0;
        for (BizHandleRecord record : all) {
            if (isRevoked(record)) {
                revoked++;
                continue;
            }
            String status = effectiveStatus(record);
            if (BizHandleRecord.STATUS_DONE.equals(status)) {
                done++;
            } else if (BizHandleRecord.STATUS_OVERDUE.equals(status)) {
                overdue++;
            } else if (BizHandleRecord.STATUS_HANDLING.equals(status)) {
                handling++;
            } else {
                pending++;
            }
        }
        long active = pending + handling + overdue + done;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", (long) all.size());
        result.put("pending", pending);
        result.put("handling", handling);
        result.put("overdue", overdue);
        result.put("done", done);
        result.put("revoked", revoked);
        result.put("doneRate", active == 0 ? 0 : Math.round(done * 100.0 / active));
        return result;
    }

    /** 发出视角的关键字：搜事项、承办人、办理要求 */
    private boolean matchesSent(BizHandleRecord record, String keyword) {
        return contains(record.getTitle(), keyword)
                || contains(record.getAssigneeId(), keyword)
                || contains(record.getInstruction(), keyword)
                || contains(record.getTaskDefinitionKey(), keyword);
    }

    private Map<String, HistoricProcessInstance> batchProcesses(List<BizHandleRecord> records) {
        Set<String> ids = new LinkedHashSet<>();
        for (BizHandleRecord record : records) {
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

    private Map<String, Object> toVO(BizHandleRecord record, String viewerId, HistoricProcessInstance hpi) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", record.getId());
        vo.put("processInstanceId", record.getProcessInstanceId());
        vo.put("taskId", record.getTaskId());
        vo.put("processDefinitionKey", record.getProcessDefinitionKey());
        vo.put("taskDefinitionKey", record.getTaskDefinitionKey());
        vo.put("title", record.getTitle());
        vo.put("assignee", record.getAssigneeId());
        vo.put("assigneeName", displayName(record.getAssigneeId()));
        vo.put("sender", record.getSenderId());
        vo.put("senderName", displayName(record.getSenderId()));
        vo.put("instruction", record.getInstruction());
        vo.put("deadline", record.getDeadline());
        vo.put("overdue", BizHandleRecord.STATUS_OVERDUE.equals(effectiveStatus(record)));
        vo.put("handleStatus", effectiveStatus(record));
        vo.put("handleResult", record.getHandleResult());
        vo.put("handleTime", record.getHandleTime());
        vo.put("revoked", isRevoked(record));
        vo.put("revokeReason", record.getRevokeReason());
        vo.put("revokeTime", record.getRevokeTime());
        vo.put("createTime", record.getCreateTime());
        boolean running = hpi != null && hpi.getEndTime() == null;
        vo.put("processRunning", running);
        vo.put("processEndTime", hpi == null ? null : hpi.getEndTime());
        vo.put("processState", running ? "RUNNING" : "FINISHED");
        vo.put("canSubmit", viewerId != null && viewerId.equals(record.getAssigneeId())
                && !isDone(record) && !isRevoked(record));
        vo.put("canRevoke", viewerId != null && viewerId.equals(record.getSenderId())
                && !isDone(record) && !isRevoked(record));
        return vo;
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

    private boolean matches(BizHandleRecord record, String keyword) {
        return contains(record.getTitle(), keyword)
                || contains(record.getSenderId(), keyword)
                || contains(record.getInstruction(), keyword)
                || contains(record.getTaskDefinitionKey(), keyword);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private boolean isDone(BizHandleRecord record) {
        return BizHandleRecord.STATUS_DONE.equals(record.getHandleStatus());
    }

    private boolean isRevoked(BizHandleRecord record) {
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
