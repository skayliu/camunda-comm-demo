package com.example.camundademo.service;

import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.entity.BizTaskDelegate;
import com.example.camundademo.entity.BizTaskExt;
import com.example.camundademo.persistence.BizTaskDelegateManager;
import com.example.camundademo.persistence.BizTaskExtManager;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.task.DelegationState;
import org.camunda.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 待办委托服务。
 *
 * <p><b>委托 vs 转办</b>：转办是责任彻底转移（改派），委托是“托人办理、责任仍在我”。
 * 委托用 Camunda 原生 {@code delegateTask / resolveTask}，语义与引擎状态完全对齐：</p>
 * <ul>
 *   <li>{@code delegateTask}：OWNER_ 保留原办理人，ASSIGNEE_ 变成受托人，
 *       DELEGATION_STATE_ 变成 PENDING；</li>
 *   <li>{@code resolveTask}：ASSIGNEE_ 回到 OWNER_，任务重新出现在原办理人的待办里，
 *       只有原办理人能 complete——这是引擎硬约束，也是委托不能被做成“受托人直接办结”的原因；</li>
 *   <li>受托人 resolve 不等于任务结束，因此不会进入任何人的正式已办，
 *       受托意见单独落 {@code BIZ_TASK_DELEGATE_.RESOLVE_CONTENT_}。</li>
 * </ul>
 *
 * <p>所有校验、引擎操作、业务落表都在同一个引擎命令内完成，避免“先查后改”写出
 * 与事实不符的委托记录（与 {@link TaskTransferService} 同一口径）。</p>
 */
@Service
public class TaskDelegateService {

    /** REASON_ / RESOLVE_CONTENT_ 列宽约束 */
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_CONTENT_LENGTH = 2000;

    /**
     * 受托人反馈写进引擎 comment 时加前缀：Camunda 的 comment 没有类型字段，
     * 用前缀把「受托建议」和「最终审批意见」区分开，避免在时间线上混成一条。
     */
    public static final String DELEGATE_COMMENT_PREFIX = "[委托反馈] ";

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private TaskService taskService;

    @Autowired
    private IdentityService identityService;

    private ProcessEngineConfigurationImpl getEngineConfig() {
        return (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    }

    // ===== 写 =====

    /**
     * 发起委托：仅当前办理人可操作。
     *
     * @param taskId       待办 ID
     * @param operator     当前登录用户（不可信外部传入）
     * @param targetUserId 受托人
     * @param reason       委托说明，可空
     */
    public Map<String, Object> delegate(String taskId, String operator, String targetUserId, String reason) {
        return getEngineConfig().getCommandExecutorTxRequired().execute((Command<Map<String, Object>>)
                commandContext -> {
                    Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
                    if (task == null) {
                        throw new BusinessException("任务不存在或已被处理");
                    }
                    String owner = task.getAssignee();
                    if (owner == null || owner.isBlank()) {
                        throw new BusinessException("该待办尚未分派给具体办理人，无法委托");
                    }
                    if (!owner.equals(operator)) {
                        throw new BusinessException("仅当前待办处理人可委托");
                    }
                    if (BizCommTask.isCommTaskKey(task.getTaskDefinitionKey())) {
                        throw new BusinessException("沟通待办不支持委托，请提交反馈或由发起人收回");
                    }
                    if (DelegationState.PENDING.equals(task.getDelegationState())) {
                        // 受托人只有“提交反馈 / 交回”的权限，责任链不能继续往下传
                        throw new BusinessException("该待办正处于委托中，受托人不能再次委托");
                    }

                    String target = targetUserId == null ? null : targetUserId.trim();
                    if (target == null || target.isEmpty()) {
                        throw new BusinessException("请选择委托接收人");
                    }
                    if (target.equals(owner)) {
                        throw new BusinessException("不能委托给自己");
                    }
                    if (identityService.createUserQuery().userId(target).count() == 0) {
                        throw new BusinessException("受托人不存在：" + target);
                    }

                    BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);
                    BizTaskExt ext = extMgr.findByTaskId(taskId);
                    if (ext != null && BizTaskExt.STATUS_COMMUNICATING.equals(ext.getBizStatus())) {
                        throw new BusinessException("当前待办正在沟通中，请等待沟通完成或收回沟通后再委托");
                    }

                    Date now = new Date();
                    // 引擎侧：OWNER_ = 原办理人，ASSIGNEE_ = 受托人，DELEGATION_STATE_ = PENDING
                    taskService.delegateTask(taskId, target);
                    syncExtAssignee(extMgr, ext, target, now);

                    BizTaskDelegate record = newRecord(task, owner, target,
                            BizTaskDelegate.SOURCE_MANUAL, null, normalize(reason, MAX_REASON_LENGTH));
                    commandContext.getSession(BizTaskDelegateManager.class).insert(record);

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("result", "ok");
                    resp.put("delegateId", record.getId());
                    resp.put("taskId", taskId);
                    resp.put("owner", owner);
                    resp.put("delegateUser", target);
                    resp.put("status", record.getStatus());
                    return resp;
                });
    }

    /**
     * 受托人提交受托意见并交回：只允许 ASSIGNEE_（受托人）且 DELEGATION_STATE_=PENDING。
     *
     * <p>这里调的是 {@code resolveTask} 而不是 {@code complete}：
     * 受托人提交后任务回到原办理人待办，由原人确认办结。</p>
     */
    public Map<String, Object> resolve(String taskId, String operator, String comment) {
        return getEngineConfig().getCommandExecutorTxRequired().execute((Command<Map<String, Object>>)
                commandContext -> {
                    Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
                    if (task == null) {
                        throw new BusinessException("任务不存在或已被处理");
                    }
                    if (!DelegationState.PENDING.equals(task.getDelegationState())) {
                        throw new BusinessException("该待办不在委托中");
                    }
                    if (!operator.equals(task.getAssignee())) {
                        throw new BusinessException("仅受托人可提交委托反馈");
                    }
                    String owner = task.getOwner();
                    if (owner == null || owner.isBlank()) {
                        throw new BusinessException("委托关系缺少原办理人，无法交回");
                    }

                    String content = normalize(comment, MAX_CONTENT_LENGTH);
                    if (content == null) {
                        throw new BusinessException("请填写委托反馈意见");
                    }

                    BizTaskDelegateManager delegateMgr = commandContext.getSession(BizTaskDelegateManager.class);
                    BizTaskDelegate record = delegateMgr.findPendingByTaskId(taskId);
                    if (record == null) {
                        // 引擎是 PENDING 但业务表没有记录，说明数据被外部改过。
                        // 仍然允许交回，但记录无法闭环，必须在响应里暴露出来。
                        record = newRecord(task, owner, operator,
                                BizTaskDelegate.SOURCE_MANUAL, null, null);
                        delegateMgr.insert(record);
                    }

                    // 意见先落引擎 comment（时间线可见），再落业务表（类型明确、不受历史清理影响）
                    taskService.createComment(taskId, task.getProcessInstanceId(),
                            DELEGATE_COMMENT_PREFIX + content);
                    taskService.resolveTask(taskId);

                    Date now = new Date();
                    record.setStatus(BizTaskDelegate.STATUS_RESOLVED);
                    record.setResolveContent(content);
                    record.setResolveTime(now);
                    record.setUpdateTime(now);
                    delegateMgr.update(record);

                    BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);
                    syncExtAssignee(extMgr, extMgr.findByTaskId(taskId), owner, now);

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("result", "ok");
                    resp.put("taskId", taskId);
                    resp.put("owner", owner);
                    resp.put("delegateUser", operator);
                    resp.put("status", BizTaskDelegate.STATUS_RESOLVED);
                    return resp;
                });
    }

    /**
     * 委托人收回委托：受托人尚未交回时可用。
     *
     * <p>语义是“我不麻烦你了”，与受托人交回区别在于归属：收回记录 STATUS_=CANCELED，
     * 委托反馈内容为空，列表里能区分“对方没给意见”和“我给过意见后被收回”。</p>
     */
    public Map<String, Object> cancel(String taskId, String operator, String reason) {
        return getEngineConfig().getCommandExecutorTxRequired().execute((Command<Map<String, Object>>)
                commandContext -> {
                    Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
                    if (task == null) {
                        throw new BusinessException("任务不存在或已被处理");
                    }
                    BizTaskDelegateManager delegateMgr = commandContext.getSession(BizTaskDelegateManager.class);
                    BizTaskDelegate record = delegateMgr.findPendingByTaskId(taskId);
                    // 「可直接办结」（SIGN）的代理任务没有 DELEGATION_STATE_，resolveTask 无法处理，
                    // 只能把 ASSIGNEE_ 改回委托人；判定条件同时要求任务确实在该次委托的代理人名下，
                    // 避免规则改过代理人后把别人的任务抢回来。
                    boolean signMode = record != null
                            && BizTaskDelegate.MODE_SIGN.equals(record.getDelegateMode())
                            && !DelegationState.PENDING.equals(task.getDelegationState())
                            && task.getAssignee() != null
                            && task.getAssignee().equals(record.getDelegateUserId());
                    String owner;
                    if (signMode) {
                        owner = record.getOwnerUserId();
                        if (!operator.equals(owner)) {
                            throw new BusinessException("仅委托人可收回委托");
                        }
                        // 先改派、后改记录：assignment 回调会命中代理监听器的“已有 PENDING 记录”判断，
                        // 不会在收回的同一秒被同一规则重新代理出去
                        taskService.setAssignee(taskId, owner);
                    } else {
                        if (!DelegationState.PENDING.equals(task.getDelegationState())) {
                            throw new BusinessException("该待办不在委托中，无需收回");
                        }
                        owner = task.getOwner();
                        if (!operator.equals(owner)) {
                            throw new BusinessException("仅委托人可收回委托");
                        }
                        taskService.resolveTask(taskId);
                    }

                    Date now = new Date();
                    if (record != null) {
                        record.setStatus(BizTaskDelegate.STATUS_CANCELED);
                        record.setResolveContent(normalize(reason, MAX_CONTENT_LENGTH));
                        record.setResolveTime(now);
                        record.setUpdateTime(now);
                        delegateMgr.update(record);
                    }

                    BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);
                    syncExtAssignee(extMgr, extMgr.findByTaskId(taskId), owner, now);

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("result", "ok");
                    resp.put("taskId", taskId);
                    resp.put("owner", owner);
                    resp.put("status", BizTaskDelegate.STATUS_CANCELED);
                    return resp;
                });
    }

    // ===== 读 =====

    /** 单条待办的委托记录（按时间升序，即委托链） */
    public List<Map<String, Object>> listByTaskId(String taskId) {
        if (taskId == null) {
            return Collections.emptyList();
        }
        return getEngineConfig().getCommandExecutorTxRequired().execute((Command<List<Map<String, Object>>>)
                commandContext -> toVOList(commandContext.getSession(BizTaskDelegateManager.class)
                        .findByTaskId(taskId)));
    }

    public List<Map<String, Object>> listByProcessInstanceId(String processInstanceId) {
        if (processInstanceId == null) {
            return Collections.emptyList();
        }
        return getEngineConfig().getCommandExecutorTxRequired().execute((Command<List<Map<String, Object>>>)
                commandContext -> toVOList(commandContext.getSession(BizTaskDelegateManager.class)
                        .findByProcessInstanceId(processInstanceId)));
    }

    /** 我的委托（含已交回/已收回） */
    public List<Map<String, Object>> listByOwner(String ownerUserId) {
        if (ownerUserId == null) {
            return Collections.emptyList();
        }
        return getEngineConfig().getCommandExecutorTxRequired().execute((Command<List<Map<String, Object>>>)
                commandContext -> toVOList(commandContext.getSession(BizTaskDelegateManager.class)
                        .findByOwner(ownerUserId)));
    }

    /**
     * 我委托出去、对方尚未交回、且任务仍在运行的条数（待办中心角标用）。
     *
     * <p>不做 {@code listByOwner} + 前端过滤，是因为菜单角标每次切页都会刷新：
     * 把整段历史委托记录连列表一起返回太浪费。这里只回一个数，
     * 且与 {@link #listByOwner} 同一口径（PENDING 且任务仍在运行）。</p>
     */
    public int countPendingOut(String ownerUserId) {
        if (ownerUserId == null) {
            return 0;
        }
        return getEngineConfig().getCommandExecutorTxRequired().execute((Command<Integer>) commandContext -> {
            List<BizTaskDelegate> records = commandContext.getSession(BizTaskDelegateManager.class)
                    .findByOwner(ownerUserId);
            Set<String> taskIds = new LinkedHashSet<>();
            for (BizTaskDelegate record : records) {
                if (BizTaskDelegate.STATUS_PENDING.equals(record.getStatus()) && record.getTaskId() != null) {
                    taskIds.add(record.getTaskId());
                }
            }
            if (taskIds.isEmpty()) {
                return 0;
            }
            Set<String> activeTaskIds = new HashSet<>();
            List<String> ids = new ArrayList<>(taskIds);
            // 分段查询：Oracle 的 IN 列表上限是 1000
            for (int i = 0; i < ids.size(); i += 200) {
                List<String> chunk = ids.subList(i, Math.min(i + 200, ids.size()));
                for (Task task : taskService.createTaskQuery()
                        .taskIdIn(chunk.toArray(new String[0])).list()) {
                    activeTaskIds.add(task.getId());
                }
            }
            return activeTaskIds.size();
        });
    }

    /** 我的受托（含已交回/被收回） */
    public List<Map<String, Object>> listByDelegateUser(String delegateUserId) {
        if (delegateUserId == null) {
            return Collections.emptyList();
        }
        return getEngineConfig().getCommandExecutorTxRequired().execute((Command<List<Map<String, Object>>>)
                commandContext -> toVOList(commandContext.getSession(BizTaskDelegateManager.class)
                        .findByDelegateUser(delegateUserId)));
    }

    /** 待办列表整页批量取，按 taskId 分组；每行只需要最近一条 */
    public Map<String, BizTaskDelegate> latestByTaskIds(Collection<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return getEngineConfig().getCommandExecutorTxRequired().execute((Command<Map<String, BizTaskDelegate>>)
                commandContext -> {
                    List<BizTaskDelegate> records = commandContext.getSession(BizTaskDelegateManager.class)
                            .findByTaskIds(taskIds);
                    Map<String, BizTaskDelegate> latest = new LinkedHashMap<>();
                    for (BizTaskDelegate record : records) {
                        latest.put(record.getTaskId(), record);
                    }
                    return latest;
                });
    }

    /** 该用户是否参与过此实例的委托（委托人或受托人），用于流程跟踪的可见性 */
    public boolean participatedInDelegate(String processInstanceId, String userId) {
        if (processInstanceId == null || userId == null) {
            return false;
        }
        return getEngineConfig().getCommandExecutorTxRequired().execute((Command<Boolean>) commandContext ->
                commandContext.getSession(BizTaskDelegateManager.class)
                        .findByProcessInstanceId(processInstanceId).stream()
                        .anyMatch(r -> userId.equals(r.getOwnerUserId())
                                || userId.equals(r.getDelegateUserId())));
    }

    // ===== 内部 =====

    private BizTaskDelegate newRecord(Task task, String owner, String delegateUser,
                                      String source, String agentRuleId, String reason) {
        Date now = new Date();
        BizTaskDelegate record = new BizTaskDelegate();
        record.setId(UUID.randomUUID().toString().replace("-", ""));
        record.setTaskId(task.getId());
        record.setProcessInstanceId(task.getProcessInstanceId());
        record.setTaskDefKey(task.getTaskDefinitionKey());
        record.setTaskName(task.getName());
        record.setOwnerUserId(owner);
        record.setDelegateUserId(delegateUser);
        record.setSource(source);
        record.setAgentRuleId(agentRuleId);
        record.setReason(reason);
        record.setStatus(BizTaskDelegate.STATUS_PENDING);
        record.setCreateTime(now);
        record.setUpdateTime(now);
        return record;
    }

    /** BizTaskExt.ASSIGNEE_ 是冗余快照，改派后必须同步，否则按扩展表筛人会漏 */
    private void syncExtAssignee(BizTaskExtManager extMgr, BizTaskExt ext, String assignee, Date now) {
        if (ext == null) {
            return;
        }
        ext.setAssignee(assignee);
        ext.setUpdateTime(now);
        extMgr.update(ext);
    }

    private String normalize(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    /**
     * 委托记录转 VO，并补一列 {@code taskActive}。
     *
     * <p>委托记录在任务办结后不会删除（要留痕），所以“委托中”不等于“任务还在办”。
     * 这里用一个批量查询回答“任务是否仍在运行”，列表页才能把待办与已办分开：
     * 状态 PENDING 且 {@code taskActive=true} 才是真的还在受托处理中。</p>
     */
    private List<Map<String, Object>> toVOList(List<BizTaskDelegate> records) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (records.isEmpty()) {
            return result;
        }
        Set<String> taskIds = new LinkedHashSet<>();
        for (BizTaskDelegate record : records) {
            if (record.getTaskId() != null) {
                taskIds.add(record.getTaskId());
            }
        }
        Set<String> activeTaskIds = new HashSet<>();
        List<String> ids = new ArrayList<>(taskIds);
        // 分段查询：IN 列表不宜过长，Oracle 的上限是 1000
        for (int i = 0; i < ids.size(); i += 200) {
            List<String> chunk = ids.subList(i, Math.min(i + 200, ids.size()));
            for (Task task : taskService.createTaskQuery()
                    .taskIdIn(chunk.toArray(new String[0])).list()) {
                activeTaskIds.add(task.getId());
            }
        }
        for (BizTaskDelegate record : records) {
            Map<String, Object> vo = toVO(record);
            vo.put("taskActive", activeTaskIds.contains(record.getTaskId()));
            result.add(vo);
        }
        return result;
    }

    public Map<String, Object> toVO(BizTaskDelegate record) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", record.getId());
        vo.put("taskId", record.getTaskId());
        vo.put("processInstanceId", record.getProcessInstanceId());
        vo.put("taskName", record.getTaskName());
        vo.put("taskDefKey", record.getTaskDefKey());
        vo.put("owner", record.getOwnerUserId());
        vo.put("delegateUser", record.getDelegateUserId());
        vo.put("source", record.getSource());
        vo.put("delegateMode", record.getDelegateMode() == null
                ? BizTaskDelegate.MODE_CONFIRM : record.getDelegateMode());
        vo.put("agentRuleId", record.getAgentRuleId());
        vo.put("reason", record.getReason());
        vo.put("status", record.getStatus());
        vo.put("resolveContent", record.getResolveContent());
        vo.put("resolveTime", record.getResolveTime());
        vo.put("createTime", record.getCreateTime());
        return vo;
    }
}
