package com.example.camundademo.service;

import com.example.camundademo.entity.BizSignRecord;
import com.example.camundademo.entity.BizTaskExt;
import com.example.camundademo.persistence.BizSignRecordManager;
import com.example.camundademo.persistence.BizTaskExtManager;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ActivityInstance;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 加签服务：前加签、后加签、并加签，以及减签与子轮次收口。
 *
 * <p>三种加签落在完全不同的执行层，这是实现上的第一原则：</p>
 * <ul>
 *   <li><b>并加签</b>：当前节点必须是多实例，用 {@code ProcessInstanceModification.startBeforeActivity}
 *       在节点内扩容，引擎自动维护 nrOfInstances，完成条件沿用节点自身配置；</li>
 *   <li><b>前加签</b>：锁住原任务（{@code BIZ_TASK_EXT_.BIZ_STATUS_ = WAIT_PRE_SIGN}），
 *       另起一个加签子流程；子轮次通过后解锁原任务，驳回则原任务走驳回分支；</li>
 *   <li><b>后加签</b>：暂存原审批意见并锁任务（{@code WAIT_POST_SIGN}），
 *       后置子轮次结束后按“原意见 + 轮次结果”合并，再真正 complete 原任务。</li>
 * </ul>
 *
 * <p><b>多人加签的两个开关是分开的</b>：并行/串行由发起人在弹层选择，落到启动哪一个
 * 子流程定义（Camunda 的 isSequential 是部署期属性，运行期不能改）；完成条件由管理员在
 * 部署期确定，后端通过 {@code addSignApproveMode} 变量下发，接口不接收该参数。</p>
 *
 * <p><b>多实例节点上的前/后加签只锁发起人自己那一张内部任务</b>：锁定以 taskId 为键，
 * 不碰 miBody、不改 nrOfInstances，同节点其他人的待办不受影响。</p>
 */
@Service
public class SignService {

    /** 加签子流程任务定义 key，与 BPMN 中保持一致 */
    public static final String ADD_SIGN_TASK_KEY = BizSignRecord.ADD_SIGN_TASK_KEY;

    private static final String VAR_USERS = "addSignUsers";
    private static final String VAR_USER = "addSignUser";
    private static final String VAR_APPROVE_MODE = "addSignApproveMode";
    private static final String VAR_REJECTED = "addSignRejected";
    private static final String VAR_ROUND_ID = "addSignRoundId";
    private static final String VAR_SOURCE_TASK = "addSignSourceTaskId";
    private static final String VAR_TYPE = "addSignType";
    private static final String VAR_SOURCE_OPINION = "addSignSourceOpinion";
    private static final String VAR_MAIN_PID = "addSignMainProcessInstanceId";

    /** 完成条件：管理员口径，接口不允许覆盖 */
    @Value("${app.add-sign.approve-mode:ALL}")
    private String approveMode = BizSignRecord.APPROVE_ALL;

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private IdentityService identityService;

    private ProcessEngineConfigurationImpl engineConfig() {
        return (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    }

    // ==================================================================
    // 写：发起加签
    // ==================================================================

    /**
     * 发起加签。
     *
     * <p>前加签：随时可发起；并加签：仅多实例节点可发起。
     * 后加签首轮必须随“通过审批”一起提交（{@link #completeWithPostSign}），
     * 但<b>减错人之后允许补人</b>——此时已存在进行中的后置轮次，
     * 会追加到同一轮，而不是报错要求重新提交审批。</p>
     *
     * @param signType   PRE / PARALLEL / POST（POST 仅用于向进行中的后置轮次补人）
     * @param users      加签人清单，多人一次传完
     * @param sequential 仅前加签有效：true 串行、false 并行
     */
    public Map<String, Object> add(String taskId, String operator, String signType,
                                   List<String> users, boolean sequential) {
        return add(taskId, operator, signType, users, sequential, null);
    }

    /**
     * 发起加签（带发起说明）。
     *
     * <p>{@code comment} 是发起人「为什么加签 / 请对方看什么」，前加签与并加签都会落库到
     * 记录的发起说明字段，加签人在自己的待办里能看到；它<b>不会</b>覆盖加签人之后提交的意见。</p>
     */
    public Map<String, Object> add(String taskId, String operator, String signType,
                                   List<String> users, boolean sequential, String comment) {
        String type = normalizeType(signType);
        List<String> targets = normalizeUsers(users);
        if (targets.isEmpty()) {
            throw new BusinessException("请选择加签人");
        }
        String opinion = (comment == null || comment.isBlank()) ? null : comment.trim();
        return engineConfig().getCommandExecutorTxRequired().execute(
                (Command<Map<String, Object>>) ctx -> {
                    Task task = requireOwnTask(taskId, operator);
                    if (isAddSignTask(task)) {
                        throw new BusinessException("加签任务不能再发起加签");
                    }
                    BizSignRecordManager signMgr = ctx.getSession(BizSignRecordManager.class);
                    if (findPendingParallelForTask(signMgr, task) != null) {
                        throw new BusinessException("加签任务不能再发起加签");
                    }
                    if (BizSignRecord.TYPE_POST.equals(type)) {
                        BizSignRecord running = firstPendingRound(
                                signMgr.findBySourceTaskId(task.getId()), BizSignRecord.TYPE_POST);
                        if (running == null) {
                            throw new BusinessException(
                                    "后加签需在提交审批时一并发起，请使用“通过并后加签”");
                        }
                        appendToRound(ctx, signMgr, running, targets);
                    } else if (BizSignRecord.TYPE_PARALLEL.equals(type)) {
                        addParallelInCommand(ctx, task, operator, targets, opinion);
                    } else {
                        addPreInCommand(ctx, task, operator, targets, sequential, opinion);
                    }
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("result", "ok");
                    resp.put("signType", type);
                    resp.put("signMode", sequential ? "SEQUENTIAL" : "PARALLEL");
                    resp.put("addedUsers", targets);
                    resp.put("signOpinion", opinion);
                    return resp;
                });
    }

    /**
     * 通过审批并后加签：原意见先暂存，后置轮次结束后再真正 complete 原任务。
     *
     * <p>原审批人拒绝时不启动后加签，直接按驳回处理——这与前加签的“驳回即整轮不通过”
     * 保持同一口径，也避免“先拒绝、再请人复核”产生一个语义矛盾的节点。</p>
     */
    public Map<String, Object> completeWithPostSign(String taskId, String operator, String comment,
                                                    boolean approved, List<String> users, boolean sequential) {
        if (!approved) {
            return engineConfig().getCommandExecutorTxRequired().execute(
                    (Command<Map<String, Object>>) ctx -> {
                        Task task = requireOwnTask(taskId, operator);
                        addComment(task, comment);
                        Map<String, Object> vars = new LinkedHashMap<>();
                        vars.put("approved", false);
                        vars.put("signRejected", true);
                        taskService.complete(taskId, vars);
                        Map<String, Object> resp = new LinkedHashMap<>();
                        resp.put("result", "ok");
                        resp.put("postSignStarted", false);
                        resp.put("message", "已驳回，原审批结果为拒绝，不启动后加签");
                        return resp;
                    });
        }
        List<String> targets = normalizeUsers(users);
        if (targets.isEmpty()) {
            throw new BusinessException("请选择后加签人员");
        }
        return engineConfig().getCommandExecutorTxRequired().execute(
                (Command<Map<String, Object>>) ctx -> {
                    Task task = requireOwnTask(taskId, operator);
                    if (isAddSignTask(task)
                            || findPendingParallelForTask(
                                    ctx.getSession(BizSignRecordManager.class), task) != null) {
                        throw new BusinessException("加签任务不能再发起加签");
                    }
                    BizTaskExtManager extMgr = ctx.getSession(BizTaskExtManager.class);
                    BizTaskExt ext = lockTask(task, extMgr, BizTaskExt.STATUS_WAIT_POST_SIGN);
                    addComment(task, comment);

                    String roundId = newRoundId();
                    startRound(ctx, task, operator, targets, BizSignRecord.TYPE_POST,
                            sequential, roundId, comment, ext.getBizStatus());

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("result", "ok");
                    resp.put("postSignStarted", true);
                    resp.put("signRoundId", roundId);
                    resp.put("addedUsers", targets);
                    return resp;
                });
    }

    private void addPreInCommand(org.camunda.bpm.engine.impl.interceptor.CommandContext ctx,
                                 Task task, String operator, List<String> users, boolean sequential,
                                 String opinion) {
        BizTaskExtManager extMgr = ctx.getSession(BizTaskExtManager.class);
        BizSignRecordManager signMgr = ctx.getSession(BizSignRecordManager.class);

        // 已存在进行中的前置轮次 → 按“补人”处理，不推翻整轮
        BizSignRecord running = firstPendingRound(signMgr.findBySourceTaskId(task.getId()),
                BizSignRecord.TYPE_PRE);
        if (running != null) {
            appendToRound(ctx, signMgr, running, users);
            return;
        }
        if (BizTaskExt.STATUS_COMMUNICATING.equals(currentStatus(ctx, task.getId()))) {
            throw new BusinessException("当前待办正在沟通中，请等待沟通完成或收回后再加签");
        }
        lockTask(task, extMgr, BizTaskExt.STATUS_WAIT_PRE_SIGN);
        // 发起说明同时进引擎意见：前加签期间原待办被锁，发起人没有别的机会留意见
        addComment(task, opinion);
        String roundId = newRoundId();
        startRound(ctx, task, operator, users, BizSignRecord.TYPE_PRE, sequential, roundId, opinion,
                BizTaskExt.STATUS_WAIT_PRE_SIGN);
    }

    private void addParallelInCommand(org.camunda.bpm.engine.impl.interceptor.CommandContext ctx,
                                      Task task, String operator, List<String> users, String opinion) {
        String elementVariable = elementVariableOfMultiInstance(task);
        if (elementVariable == null || elementVariable.isBlank()) {
            throw new BusinessException(
                    "当前节点不是多实例，不能直接并加签。请在设计期把该节点配置成多实例"
                            + "（multiInstanceLoopCharacteristics + collection + elementVariable），"
                            + "或改用前加签/后加签");
        }
        BizSignRecordManager signMgr = ctx.getSession(BizSignRecordManager.class);
        if (BizTaskExt.STATUS_COMMUNICATING.equals(currentStatus(ctx, task.getId()))) {
            throw new BusinessException("当前待办正在沟通中，请等待沟通完成或收回后再加签");
        }
        BizSignRecord running = firstPendingRound(signMgr.findBySourceTaskId(task.getId()),
                BizSignRecord.TYPE_PARALLEL);
        String roundId = running == null ? newRoundId() : running.getSignRoundId();

        List<String> fresh = new ArrayList<>();
        for (String user : users) {
            if (running != null && signMgr.countActiveByRoundAndUser(roundId, user) > 0) {
                continue;
            }
            fresh.add(user);
        }
        if (fresh.isEmpty()) {
            throw new BusinessException("所选人员已在本轮加签中");
        }

        for (String user : fresh) {
            // 每个实例单独一次 modification：指令更清晰，也避免链式 API 在版本间的差异
            runtimeService.createProcessInstanceModification(task.getProcessInstanceId())
                    .startBeforeActivity(task.getTaskDefinitionKey())
                    .setVariableLocal(elementVariable, user)
                    .setVariableLocal("addSignType", BizSignRecord.TYPE_PARALLEL)
                    .execute();
            Task created = latestTaskOf(task.getProcessInstanceId(), task.getTaskDefinitionKey(), user);
            BizSignRecord record = newRecord(roundId, task, operator, user,
                    BizSignRecord.TYPE_PARALLEL, BizSignRecord.APPROVE_ALL, opinion);
            record.setAddTaskId(created == null ? null : created.getId());
            record.setAddActivityInstanceId(created == null ? null
                    : resolveActivityInstanceId(task.getProcessInstanceId(), created.getExecutionId()));
            signMgr.insert(record);
        }
    }

    /**
     * 在一个已经存在的子轮次里补人（减错人后重新加签，不推翻整轮）。
     */
    private void appendToRound(org.camunda.bpm.engine.impl.interceptor.CommandContext ctx,
                               BizSignRecordManager signMgr, BizSignRecord running, List<String> users) {
        for (String user : users) {
            if (signMgr.countActiveByRoundAndUser(running.getSignRoundId(), user) > 0) {
                continue;
            }
            runtimeService.createProcessInstanceModification(running.getSubProcessInstanceId())
                    .startBeforeActivity(ADD_SIGN_TASK_KEY)
                    .setVariableLocal(VAR_USER, user)
                    .execute();
            Task created = latestTaskOf(running.getSubProcessInstanceId(), ADD_SIGN_TASK_KEY, user);
            BizSignRecord record = new BizSignRecord();
            record.setId(newId());
            record.setSignRoundId(running.getSignRoundId());
            record.setProcessInstanceId(running.getProcessInstanceId());
            record.setSubProcessInstanceId(running.getSubProcessInstanceId());
            record.setSourceTaskId(running.getSourceTaskId());
            record.setSourceTaskDefKey(running.getSourceTaskDefKey());
            record.setSourceActivityInstanceId(running.getSourceActivityInstanceId());
            record.setSignType(running.getSignType());
            record.setSignMode(running.getSignMode());
            record.setApproveMode(running.getApproveMode());
            record.setInitiatorId(running.getInitiatorId());
            record.setAddUserId(user);
            record.setAddTaskId(created == null ? null : created.getId());
            record.setSequenceNo(null);
            record.setSignStatus(BizSignRecord.STATUS_PENDING);
            record.setCreateTime(new Date());
            record.setUpdateTime(new Date());
            signMgr.insert(record);
        }
    }

    /** 启动一轮加签子流程，并写入本轮全部记录 */
    private void startRound(org.camunda.bpm.engine.impl.interceptor.CommandContext ctx,
                            Task task, String operator, List<String> users, String type,
                            boolean sequential, String roundId, String sourceOpinion,
                            String lockStatus) {
        BizSignRecordManager signMgr = ctx.getSession(BizSignRecordManager.class);
        String mode = sequential ? "SEQUENTIAL" : "PARALLEL";
        String modeSnapshot = BizSignRecord.APPROVE_ALL.equalsIgnoreCase(approveMode)
                ? BizSignRecord.APPROVE_ALL : BizSignRecord.APPROVE_ANY;

        String processKey = sequential ? "addSignSequential" : "addSignParallel";
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processKey)
                .latestVersion()
                .singleResult();
        if (definition == null) {
            throw new BusinessException("加签子流程未部署：" + processKey + "，请检查 BPMN 是否随应用发布");
        }

        Map<String, Object> roundVars = new LinkedHashMap<>();
        roundVars.put(VAR_USERS, users);
        roundVars.put(VAR_APPROVE_MODE, modeSnapshot);
        roundVars.put(VAR_REJECTED, false);
        roundVars.put(VAR_ROUND_ID, roundId);
        roundVars.put(VAR_SOURCE_TASK, task.getId());
        roundVars.put(VAR_TYPE, type);
        roundVars.put(VAR_MAIN_PID, task.getProcessInstanceId());
        if (sourceOpinion != null) {
            roundVars.put(VAR_SOURCE_OPINION, sourceOpinion);
        }
        ProcessInstance sub = runtimeService.startProcessInstanceByKey(
                processKey, task.getProcessInstanceId() + ":" + roundId, roundVars);

        // 先启动子流程拿到实例 ID，再插记录：SUB_PROC_INST_ID_ 必须随插入一起落库。
        // 若先插后 update，该字段不在 getPersistentState 里，DB 会一直是空，
        // 子轮次结束时按子流程实例找不到记录，前/后加签就永远不会解锁原任务。
        List<BizSignRecord> records = new ArrayList<>();
        int seq = 1;
        for (String user : users) {
            BizSignRecord record = newRecord(roundId, task, operator, user, type, modeSnapshot, sourceOpinion);
            record.setSignMode(mode);
            record.setSubProcessInstanceId(sub.getId());
            record.setSequenceNo(seq++);
            signMgr.insert(record);
            records.add(record);
        }

        // 回填已创建的任务 ID（串行时只有第一个任务）
        for (BizSignRecord record : records) {
            Task created = latestTaskOf(sub.getId(), ADD_SIGN_TASK_KEY, record.getAddUserId());
            if (created != null) {
                record.setAddTaskId(created.getId());
                record.setUpdateTime(new Date());
                signMgr.update(record);
            }
        }
    }

    // ==================================================================
    // 写：加签人提交 / 减签
    // ==================================================================

    /**
     * 加签人提交。驳回时先把 {@code addSignRejected} 置为 true，多实例完成条件立即满足，
     * 引擎会销毁其余活动实例——这是“任一驳回即整轮不通过”的落地方式。
     */
    public Map<String, Object> completeAddSignTask(String taskId, String operator, boolean approved, String comment) {
        return engineConfig().getCommandExecutorTxRequired().execute(
                (Command<Map<String, Object>>) ctx -> {
                    Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
                    if (task == null) {
                        throw new BusinessException("加签任务不存在或已被处理");
                    }
                    if (task.getAssignee() != null && !task.getAssignee().equals(operator)) {
                        throw new BusinessException("只能提交分派给自己的加签任务");
                    }
                    BizSignRecordManager signMgr = ctx.getSession(BizSignRecordManager.class);
                    BizSignRecord record = signMgr.findByAddTaskId(taskId);
                    if (record == null) {
                        // 并加签复用原节点（taskDefinitionKey 仍是 miReview），不是 addSignTask 子流程节点，
                        // 单靠 isAddSignTask 会把它当普通任务拒掉，导致加签人点“同意/驳回”必然失败。
                        record = findPendingParallelForTask(signMgr, task);
                    }
                    if (!isAddSignTask(task)
                            && (record == null || !BizSignRecord.TYPE_PARALLEL.equals(record.getSignType()))) {
                        throw new BusinessException("该任务不是加签任务");
                    }
                    if (record == null) {
                        // 并加签是“同节点扩容”，没有子流程可查：回填 addTaskId 发生在 flush 之前，
                        // 偶发查不到，必须按「同流程 + 同节点 + 同办理人 + PENDING」兜底，
                        // 否则驳回会在这里直接报“找不到记录”，整轮撤销逻辑根本走不到。
                        record = findPendingParallelForTask(signMgr, task);
                    }
                    if (record == null) {
                        record = findPendingBySubProcess(signMgr, task.getProcessInstanceId(), operator);
                    }
                    if (record == null) {
                        throw new BusinessException("找不到该加签任务对应的加签记录");
                    }
                    if (!BizSignRecord.STATUS_PENDING.equals(record.getSignStatus())) {
                        throw new BusinessException("该加签任务已处理");
                    }
                    record.setAddTaskId(taskId);
                    record.setSignStatus(approved ? BizSignRecord.STATUS_APPROVED : BizSignRecord.STATUS_REJECTED);
                    // 记录上的意见优先保留发起人的加签说明；只有它为空时才用加签人意见填充，
                    // 避免加签人一提交就把「请他看什么」覆盖掉（加签人意见另有 taskService comment 留痕）
                    if (record.getOpinion() == null || record.getOpinion().isBlank()) {
                        record.setOpinion(comment);
                    }
                    record.setUpdateTime(new Date());
                    if (!approved) {
                        record.setFinishTime(new Date());
                    }
                    signMgr.update(record);
                    if (!approved) {
                        runtimeService.setVariable(task.getProcessInstanceId(), VAR_REJECTED, true);
                        // 并加签没有子流程分支，“任一驳回即整轮不通过”必须由本方法自己撤销同轮其他人：
                        // 前/后加签靠子流程的 completionCondition 收口，并加签只能在这里显式撤销，
                        // 否则同轮其他人会继续持有待办，节点永远等不齐。
                        if (BizSignRecord.TYPE_PARALLEL.equals(record.getSignType())) {
                            cancelSiblings(ctx, signMgr, record);
                        }
                    }
                    addComment(task, comment);
                    taskService.complete(taskId);

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("result", "ok");
                    resp.put("approved", approved);
                    return resp;
                });
    }

    /**
     * 减签：已提交意见的不可减，只能走驳回落整轮。
     */
    public Map<String, Object> remove(String recordId, String operator) {
        if (recordId == null || recordId.isBlank()) {
            throw new BusinessException("请选择要减签的人员");
        }
        return engineConfig().getCommandExecutorTxRequired().execute(
                (Command<Map<String, Object>>) ctx -> {
                    BizSignRecordManager signMgr = ctx.getSession(BizSignRecordManager.class);
                    BizSignRecord record = signMgr.findById(recordId);
                    if (record == null) {
                        throw new BusinessException("加签记录不存在");
                    }
                    if (!BizSignRecord.STATUS_PENDING.equals(record.getSignStatus())) {
                        throw new BusinessException("该加签人已提交意见，不能减签；如需撤销请走驳回，整轮重新发起");
                    }
                    if (!operator.equals(record.getInitiatorId())) {
                        throw new BusinessException("仅本轮加签的发起人可减签");
                    }

                    // 允许减到只剩已提交意见的人：此时引擎按剩余人数收敛，
                    // 这是“加错人 → 减掉 → 补人重加”链路上的正常一步，不应被拦住。
                    cancelRecordInstance(record);
                    record.setSignStatus(BizSignRecord.STATUS_CANCELLED);
                    record.setFinishTime(new Date());
                    record.setUpdateTime(new Date());
                    signMgr.update(record);

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("result", "ok");
                    resp.put("recordId", recordId);
                    resp.put("addUserId", record.getAddUserId());
                    return resp;
                });
    }

    /** 并加签任务的记录兜底查找：同流程、同节点、同办理人，且仍待处理 */
    private BizSignRecord findPendingParallelForTask(BizSignRecordManager signMgr, Task task) {
        for (BizSignRecord record : signMgr.findByProcessInstanceId(task.getProcessInstanceId())) {
            if (BizSignRecord.TYPE_PARALLEL.equals(record.getSignType())
                    && BizSignRecord.STATUS_PENDING.equals(record.getSignStatus())
                    && task.getTaskDefinitionKey().equals(record.getSourceTaskDefKey())
                    && task.getAssignee() != null
                    && task.getAssignee().equals(record.getAddUserId())) {
                return record;
            }
        }
        return null;
    }

    /** 取消一个加签实例：并加签按 activityInstanceId，前/后加签按子流程任务 */
    private void cancelRecordInstance(BizSignRecord record) {
        if (BizSignRecord.TYPE_PARALLEL.equals(record.getSignType())) {
            // 并加签是在节点内扩容出来的实例，撤销必须走 cancelActivityInstance，
            // 不能删任务——删任务不会同步多实例计数，节点会一直等这个已消失的实例。
            String aiId = record.getAddActivityInstanceId();
            if (isBlank(aiId) && record.getAddTaskId() != null) {
                aiId = activityInstanceIdOfTask(record.getAddTaskId());
            }
            if (isBlank(aiId)) {
                // 扩容任务是在同一个命令里创建的，回填 addTaskId 时可能还查不到（未 flush），
                // 所以这里不能只依赖记录字段，按「同流程 + 同节点 + 同办理人」现查一次。
                Task task = findParallelTaskOf(record);
                if (task != null) {
                    aiId = activityInstanceIdOfTask(task.getId());
                }
            }
            if (!isBlank(aiId)) {
                runtimeService.createProcessInstanceModification(record.getProcessInstanceId())
                        .cancelActivityInstance(aiId)
                        .execute();
            } else {
                System.err.println("[SignService] 并加签实例定位失败，无法撤销 user="
                        + record.getAddUserId() + " recordId=" + record.getId());
            }
            return;
        }
        Task task = null;
        if (record.getAddTaskId() != null) {
            task = taskService.createTaskQuery().taskId(record.getAddTaskId()).singleResult();
        }
        if (task == null) {
            task = latestTaskOf(record.getSubProcessInstanceId(), ADD_SIGN_TASK_KEY, record.getAddUserId());
        }
        if (task == null) {
            return;
        }
        String aiId = resolveActivityInstanceId(record.getSubProcessInstanceId(), task.getExecutionId());
        if (aiId != null) {
            runtimeService.createProcessInstanceModification(record.getSubProcessInstanceId())
                    .cancelActivityInstance(aiId)
                    .execute();
        } else {
            // 兜底：取消活动实例是唯一能同步多实例计数的做法，绝不直接删任务
            taskService.deleteTask(task.getId(), "减签");
        }
    }

    /** 按记录上的节点与办理人，定位仍挂在该节点上的并加签任务 */
    private Task findParallelTaskOf(BizSignRecord record) {
        if (record.getSourceTaskDefKey() == null || record.getAddUserId() == null) {
            return null;
        }
        return taskService.createTaskQuery()
                .processInstanceId(record.getProcessInstanceId())
                .taskDefinitionKey(record.getSourceTaskDefKey())
                .taskAssignee(record.getAddUserId())
                .list().stream().findFirst().orElse(null);
    }

    private String activityInstanceIdOfTask(String taskId) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            return null;
        }
        return resolveActivityInstanceId(task.getProcessInstanceId(), task.getExecutionId());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ==================================================================
    // 写：主任务提交
    // ==================================================================

    /**
     * 提交主任务（同意 / 驳回），并在同一命令内收口并加签记录。
     *
     * <p>并加签的“任一驳回即整轮不通过”在这里落地：驳回时先取消同轮其余并加签实例，
     * 再 complete 当前任务；如果节点是多实例且完成条件为全部通过，其余实例被取消后
     * 计数随之更新，节点不会卡住。</p>
     *
     * <p>前/后加签进行中的任务会被拦截器挡住，不会走到这里。</p>
     */
    public Map<String, Object> completeMainTask(String taskId, String operator,
                                                boolean approved, String comment) {
        return engineConfig().getCommandExecutorTxRequired().execute(
                (Command<Map<String, Object>>) ctx -> {
                    Task task = requireOwnTask(taskId, operator);
                    BizSignRecordManager signMgr = ctx.getSession(BizSignRecordManager.class);
                    BizSignRecord record = signMgr.findByAddTaskId(taskId);
                    if (record == null) {
                        // 并加签扩容出来的任务偶尔拿不到回填的 addTaskId（查询发生在 flush 之前），
                        // 这里按「同流程 + 同节点 + 同办理人 + PENDING」兜底，否则驳回不会撤销同轮其他人。
                        record = findPendingParallelForTask(signMgr, task);
                    }
                    if (record != null && BizSignRecord.STATUS_PENDING.equals(record.getSignStatus())) {
                        record.setSignStatus(approved
                                ? BizSignRecord.STATUS_APPROVED : BizSignRecord.STATUS_REJECTED);
                        record.setOpinion(comment);
                        record.setUpdateTime(new Date());
                        record.setFinishTime(new Date());
                        signMgr.update(record);
                        if (!approved) {
                            cancelSiblings(ctx, signMgr, record);
                        }
                    }
                    addComment(task, comment);
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("approved", approved);
                    vars.put("signRejected", !approved);
                    taskService.complete(taskId, vars);

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("result", "ok");
                    resp.put("approved", approved);
                    return resp;
                });
    }

    private void cancelSiblings(org.camunda.bpm.engine.impl.interceptor.CommandContext ctx,
                                BizSignRecordManager signMgr, BizSignRecord current) {
        for (BizSignRecord other : signMgr.findByRoundId(current.getSignRoundId())) {
            if (other.getId().equals(current.getId())
                    || !BizSignRecord.STATUS_PENDING.equals(other.getSignStatus())) {
                continue;
            }
            try {
                cancelRecordInstance(other);
            } catch (RuntimeException e) {
                // 实例可能已随节点收敛被销毁；记录仍要标记，避免留下“永远待处理”的假记录。
                // 但不能完全静默：撤销失败会让同轮其他人继续持有待办，必须留痕便于排查。
                System.err.println("[SignService] 撤销同轮加签实例失败 recordId=" + other.getId()
                        + " user=" + other.getAddUserId()
                        + " aiId=" + other.getAddActivityInstanceId()
                        + " taskId=" + other.getAddTaskId()
                        + " cause=" + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            other.setSignStatus(BizSignRecord.STATUS_CANCELLED);
            other.setFinishTime(new Date());
            other.setUpdateTime(new Date());
            signMgr.update(other);
        }
    }

    // ==================================================================
    // 子轮次收口（由加签子流程的 end 监听器调用，不在本类开新事务）
    // ==================================================================

    /**
     * 子轮次结束时回写记录并推进原任务。由 {@code AddSignRoundEndListener} 在命令内调用。
     */
    public void onRoundEnd(org.camunda.bpm.engine.impl.interceptor.CommandContext ctx,
                           String subProcessInstanceId, boolean rejected) {
        BizSignRecordManager signMgr = ctx.getSession(BizSignRecordManager.class);
        BizTaskExtManager extMgr = ctx.getSession(BizTaskExtManager.class);
        List<BizSignRecord> records = signMgr.findByRoundId(
                resolveRoundId(signMgr, subProcessInstanceId));
        if (records.isEmpty()) {
            return;
        }
        BizSignRecord head = records.get(0);
        Date now = new Date();
        // 未处理的记录统一标记为已取消（任一通过 / 驳回时引擎会销毁剩余实例）
        signMgr.finishRound(head.getSignRoundId(), BizSignRecord.STATUS_CANCELLED, null, now);

        BizTaskExt ext = extMgr.findByTaskId(head.getSourceTaskId());
        if (ext == null) {
            return;
        }
        ext.setBizStatus(BizTaskExt.STATUS_NORMAL);
        ext.setUpdateTime(now);
        extMgr.update(ext);

        Task sourceTask = taskService.createTaskQuery().taskId(head.getSourceTaskId()).singleResult();
        if (sourceTask == null) {
            // 原任务已被撤回/流程已终止：只归档记录，不再推进
            return;
        }

        if (BizSignRecord.TYPE_PRE.equals(head.getSignType())) {
            if (!rejected) {
                // 前加签通过：解锁，原审批人继续审
                return;
            }
            // 前加签驳回：原任务走驳回分支，不再解锁
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("approved", false);
            vars.put("signRejected", true);
            taskService.complete(sourceTask.getId(), vars);
            return;
        }

        // 后加签：原意见与轮次结果合并。原审批人同意是启动后加签的前提，
        // 因此这里只需要看轮次是否驳回。
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("approved", !rejected);
        vars.put("signRejected", rejected);
        taskService.complete(sourceTask.getId(), vars);
    }

    private String resolveRoundId(BizSignRecordManager signMgr, String subProcessInstanceId) {
        if (subProcessInstanceId == null) {
            return "";
        }
        BizSignRecord record = signMgr.findBySubProcessInstanceId(subProcessInstanceId);
        if (record != null) {
            return record.getSignRoundId();
        }
        return "";
    }

    // ==================================================================
    // 读
    // ==================================================================

    public List<Map<String, Object>> listByTaskId(String taskId) {
        return engineConfig().getCommandExecutorTxRequired().execute(
                (Command<List<Map<String, Object>>>) ctx -> toVOList(
                        ctx.getSession(BizSignRecordManager.class).findBySourceTaskId(taskId)));
    }

    public List<Map<String, Object>> listByProcessInstanceId(String processInstanceId) {
        return groupQuery(() -> engineConfig().getCommandExecutorTxRequired().execute(
                (Command<List<Map<String, Object>>>) ctx -> toVOList(
                        ctx.getSession(BizSignRecordManager.class).findByProcessInstanceId(processInstanceId))));
    }

    public List<Map<String, Object>> listBySourceTaskIds(Collection<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Collections.emptyList();
        }
        return engineConfig().getCommandExecutorTxRequired().execute(
                (Command<List<Map<String, Object>>>) ctx -> {
                    BizSignRecordManager signMgr = ctx.getSession(BizSignRecordManager.class);
                    List<Map<String, Object>> all = new ArrayList<>();
                    for (String taskId : taskIds) {
                        all.addAll(toVOList(signMgr.findBySourceTaskId(taskId)));
                    }
                    return all;
                });
    }

    public boolean hasPendingSign(String sourceTaskId) {
        if (sourceTaskId == null) {
            return false;
        }
        return engineConfig().getCommandExecutorTxRequired().execute(
                (Command<Boolean>) ctx -> ctx.getSession(BizSignRecordManager.class)
                        .countPendingBySourceTask(sourceTaskId) > 0);
    }

    /** 是否为加签子流程里的任务（前端据此渲染“提交加签意见”） */
    public boolean isAddSignTask(Task task) {
        return task != null && ADD_SIGN_TASK_KEY.equals(task.getTaskDefinitionKey());
    }

    /**
     * 当前任务所在节点是否支持并加签（即是否多实例节点）。
     *
     * <p>单实例节点上并加签必然失败（{@link #addParallelInCommand} 会直接报错），
     * 因此前端据此隐藏「并加签」选项，避免用户点了才看到失败提示。</p>
     */
    public boolean supportsParallelSign(Task task) {
        if (task == null || isAddSignTask(task)) {
            return false;
        }
        try {
            String elementVariable = elementVariableOfMultiInstance(task);
            return elementVariable != null && !elementVariable.isBlank();
        } catch (RuntimeException e) {
            // 读不到模型时按“不支持”处理：宁可少一个入口，也不要给一个必然失败的按钮
            return false;
        }
    }

    /**
     * 并加签扩容任务的整轮记录。
     *
     * <p>并加签是“同节点扩容”，扩容出来的任务 {@code taskDefinitionKey} 仍是原节点 key，
     * 前端只看 key 会把它当普通审批任务，页面上只渲染“通过审批”，加签人根本没有驳回入口——
     * “任一驳回即整轮不通过”就只能在接口层被触发。这里按
     * 「同流程 + 同节点 + 同办理人 + PENDING」反查所属轮次并返回整轮记录，
     * 详情抽屉据此渲染加签待办标识、整轮进度与本人状态。</p>
     *
     * @return 该任务不是并加签扩容任务时返回空列表
     */
    public List<Map<String, Object>> roundRecordsOfParallelTask(Task task) {
        if (task == null || isAddSignTask(task)) {
            return Collections.emptyList();
        }
        return engineConfig().getCommandExecutorTxRequired().execute(
                (Command<List<Map<String, Object>>>) ctx -> {
                    BizSignRecordManager signMgr = ctx.getSession(BizSignRecordManager.class);
                    BizSignRecord record = findPendingParallelForTask(signMgr, task);
                    if (record == null) {
                        return Collections.emptyList();
                    }
                    return toVOList(signMgr.findByRoundId(record.getSignRoundId()));
                });
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private Task requireOwnTask(String taskId, String operator) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            throw new BusinessException("任务不存在或已被处理");
        }
        if (task.getAssignee() != null && !task.getAssignee().equals(operator)) {
            throw new BusinessException("只能对分派给自己的待办加签");
        }
        return task;
    }

    private void addComment(Task task, String comment) {
        if (comment == null || comment.isBlank()) {
            return;
        }
        taskService.createComment(task.getId(), task.getProcessInstanceId(),
                comment.length() > 500 ? comment.substring(0, 500) : comment);
    }

    private String currentStatus(org.camunda.bpm.engine.impl.interceptor.CommandContext ctx, String taskId) {
        BizTaskExt ext = ctx.getSession(BizTaskExtManager.class).findByTaskId(taskId);
        return ext == null ? BizTaskExt.STATUS_NORMAL : ext.getBizStatus();
    }

    /** 锁定原任务：只按 taskId 锁这一张，且不新增列 */
    private BizTaskExt lockTask(Task task, BizTaskExtManager extMgr, String status) {
        BizTaskExt ext = extMgr.findByTaskId(task.getId());
        if (ext == null) {
            ext = new BizTaskExt();
            ext.setId(newId());
            ext.setTaskId(task.getId());
            ext.setProcessInstanceId(task.getProcessInstanceId());
            ext.setAssignee(task.getAssignee());
            ext.setCreateTime(new Date());
            extMgr.insert(ext);
        }
        ext.setBizStatus(status);
        ext.setUpdateTime(new Date());
        extMgr.update(ext);
        return ext;
    }

    private BizSignRecord newRecord(String roundId, Task task, String operator, String addUser,
                                    String type, String modeSnapshot, String opinion) {
        BizSignRecord record = new BizSignRecord();
        record.setId(newId());
        record.setSignRoundId(roundId);
        record.setProcessInstanceId(task.getProcessInstanceId());
        record.setSourceTaskId(task.getId());
        record.setSourceTaskDefKey(task.getTaskDefinitionKey());
        record.setSourceActivityInstanceId(
                resolveActivityInstanceId(task.getProcessInstanceId(), task.getExecutionId()));
        record.setSignType(type);
        record.setSignMode(BizSignRecord.TYPE_PARALLEL.equals(type) ? "PARALLEL" : null);
        record.setApproveMode(modeSnapshot);
        record.setInitiatorId(operator);
        record.setAddUserId(addUser);
        record.setOpinion(opinion);
        record.setSignStatus(BizSignRecord.STATUS_PENDING);
        record.setCreateTime(new Date());
        record.setUpdateTime(new Date());
        return record;
    }

    /**
     * 找同一源任务上仍未结束的轮次。
     *
     * <p>并加签没有子流程（{@code SUB_PROC_INST_ID_} 为空），它按“同一张源任务”归组；
     * 前/后加签按子流程实例归组。两者判定条件不同，不能共用“子流程不为空”这一条，
     * 否则每次并加签都会新建轮次，驳回时只取消掉同一次加的人。</p>
     */
    private BizSignRecord firstPendingRound(List<BizSignRecord> records, String type) {
        for (BizSignRecord record : records) {
            if (!BizSignRecord.STATUS_PENDING.equals(record.getSignStatus())
                    || !type.equals(record.getSignType())) {
                continue;
            }
            if (BizSignRecord.TYPE_PARALLEL.equals(type) || record.getSubProcessInstanceId() != null) {
                return record;
            }
        }
        return null;
    }

    private BizSignRecord findPendingBySubProcess(BizSignRecordManager signMgr,
                                                  String subProcessInstanceId, String user) {
        BizSignRecord first = signMgr.findBySubProcessInstanceId(subProcessInstanceId);
        if (first == null) {
            return null;
        }
        for (BizSignRecord record : signMgr.findByRoundId(first.getSignRoundId())) {
            if (user.equals(record.getAddUserId())
                    && BizSignRecord.STATUS_PENDING.equals(record.getSignStatus())) {
                return record;
            }
        }
        return null;
    }

    /**
     * 当前节点是否多实例，是则返回 elementVariable，否则 null。
     *
     * <p>直接读部署时的 BPMN XML 片段判断：不依赖 camunda-bpmn-model 的编译期类型，
     * 避免不同 7.x 小版本之间模型 API 差异导致整个服务编译不过。</p>
     */
    private String elementVariableOfMultiInstance(Task task) {
        try (java.io.InputStream in = repositoryService.getProcessModel(task.getProcessDefinitionId())) {
            if (in == null) {
                return null;
            }
            String xml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int idx = xml.indexOf("id=\"" + task.getTaskDefinitionKey() + "\"");
            if (idx < 0) {
                return null;
            }
            int start = xml.lastIndexOf("<bpmn:userTask", idx);
            if (start < 0) {
                start = xml.lastIndexOf("<userTask", idx);
            }
            if (start < 0) {
                return null;
            }
            String segment = xml.substring(start, Math.min(xml.length(), start + 4000));
            if (!segment.contains("multiInstanceLoopCharacteristics")) {
                return null;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("elementVariable=\"([^\"]+)\"").matcher(segment);
            return matcher.find() ? matcher.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Task latestTaskOf(String processInstanceId, String taskDefinitionKey, String assignee) {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .taskAssignee(assignee)
                .orderByTaskCreateTime().desc()
                .list();
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    private String resolveActivityInstanceId(String processInstanceId, String executionId) {
        if (processInstanceId == null || executionId == null) {
            return null;
        }
        try {
            ActivityInstance root = runtimeService.getActivityInstance(processInstanceId);
            return findActivityInstanceId(root, executionId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String findActivityInstanceId(ActivityInstance node, String executionId) {
        if (node == null) {
            return null;
        }
        String[] executionIds = node.getExecutionIds();
        if (executionIds != null) {
            for (String id : executionIds) {
                if (executionId.equals(id)) {
                    return activityInstanceIdOf(node);
                }
            }
        }
        ActivityInstance[] children = node.getChildActivityInstances();
        if (children != null) {
            for (ActivityInstance child : children) {
                String hit = findActivityInstanceId(child, executionId);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** 活动实例 ID 用反射读取，兼容不同 7.x 小版本的接口差异 */
    private String activityInstanceIdOf(ActivityInstance node) {
        for (String method : new String[]{"getActivityInstanceId", "getId"}) {
            try {
                Object value = node.getClass().getMethod(method).invoke(node);
                if (value != null) {
                    return String.valueOf(value);
                }
            } catch (Exception ignored) {
                // 换下一个方法名
            }
        }
        return null;
    }

    private List<String> normalizeUsers(List<String> users) {
        if (users == null || users.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : users) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String user = raw.trim();
            if (identityService.createUserQuery().userId(user).count() == 0) {
                throw new BusinessException("加签人不存在：" + user);
            }
            unique.add(user);
        }
        return new ArrayList<>(unique);
    }

    private String normalizeType(String signType) {
        String type = signType == null ? "" : signType.trim().toUpperCase();
        if (BizSignRecord.TYPE_PRE.equals(type) || BizSignRecord.TYPE_POST.equals(type)
                || BizSignRecord.TYPE_PARALLEL.equals(type)) {
            return type;
        }
        throw new BusinessException("不支持的加签类型：" + signType);
    }

    private List<Map<String, Object>> toVOList(List<BizSignRecord> records) {
        return toVO(records);
    }

    /** 读接口统一走同一事务命令；保留一层包装便于后续按轮次分组 */
    private <T> T groupQuery(java.util.function.Supplier<T> supplier) {
        return supplier.get();
    }

    private List<Map<String, Object>> toVO(List<BizSignRecord> records) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BizSignRecord record : records) {
            result.add(toVO(record));
        }
        return result;
    }

    private Map<String, Object> toVO(BizSignRecord record) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", record.getId());
        vo.put("signRoundId", record.getSignRoundId());
        vo.put("processInstanceId", record.getProcessInstanceId());
        vo.put("subProcessInstanceId", record.getSubProcessInstanceId());
        vo.put("sourceTaskId", record.getSourceTaskId());
        vo.put("sourceTaskDefKey", record.getSourceTaskDefKey());
        vo.put("signType", record.getSignType());
        vo.put("signMode", record.getSignMode());
        vo.put("approveMode", record.getApproveMode());
        vo.put("initiatorId", record.getInitiatorId());
        vo.put("addUserId", record.getAddUserId());
        vo.put("addTaskId", record.getAddTaskId());
        vo.put("status", record.getSignStatus());
        vo.put("opinion", record.getOpinion());
        vo.put("signOpinion", record.getSignOpinion());
        vo.put("sequenceNo", record.getSequenceNo());
        vo.put("createTime", record.getCreateTime());
        vo.put("finishTime", record.getFinishTime());
        return vo;
    }

    private String newRoundId() {
        return newId();
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
