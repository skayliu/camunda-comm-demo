package com.example.camundademo.service;

import com.example.camundademo.entity.BizCommSession;
import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.entity.BizTaskExt;
import com.example.camundademo.persistence.BizCommSessionManager;
import com.example.camundademo.persistence.BizCommTaskManager;
import com.example.camundademo.persistence.BizTaskExtManager;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.persistence.entity.TaskEntity;
import org.camunda.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 沟通建单：发起、补充对象、追问、转交。
 *
 * <p>四类入口共用同一套落库动作：创建无 executionId 的 Camunda 旁路任务、
 * 初始化任务扩展记录、写业务沟通记录。区别只在权限校验和父链挂载点。</p>
 */
@Service
public class CommTaskFactory {

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProcessEngine processEngine;

    private ProcessEngineConfigurationImpl getEngineConfig() {
        return (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    }

    /**
     * 发起沟通（兼容旧签名：只返回会话ID）
     */
    public String startCommunication(String rootTaskId, String initiator, String content,
                                     List<Map<String, String>> targets) {
        Object sessionId = startCommunicationDetailed(rootTaskId, initiator, content, targets).get("sessionId");
        return sessionId == null ? null : sessionId.toString();
    }

    /**
     * 发起沟通，并返回本次操作的明细（是否追加、新增/跳过的人员）。
     *
     * <p><b>进行中会话上再次发起 = 补充沟通对象</b>：实际使用中经常出现“发完才发现漏了人”，
     * 老实现直接抛「当前待办已有进行中的沟通，请勿重复发起」，而前端又没有校验响应体里的
     * {@code error}，于是表现为“提示成功、后台报错、人员没加上”。现在改为在原会话内补齐：
     * 已在本次沟通中的对象跳过，只给新增对象建沟通任务，会话轮次与进行中状态保持不变。</p>
     */
    public Map<String, Object> startCommunicationDetailed(String rootTaskId, String initiator, String content,
                                                          List<Map<String, String>> targets) {
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        Map<String, Object> result = new LinkedHashMap<>();

        engineConfig.getCommandExecutorTxRequired().execute((Command<Void>) commandContext -> {
            Task rootTask = taskService.createTaskQuery().taskId(rootTaskId).singleResult();
            if (rootTask == null) {
                throw new BusinessException("源待办不存在");
            }
            // 身份校验：只有当前待办处理人才能发起沟通，避免任意用户代他人发起
            if (initiator == null || !initiator.equals(rootTask.getAssignee())) {
                throw new BusinessException("仅当前待办处理人可发起沟通");
            }
            // 归一化沟通对象：不同前端可能提交 id/name 或 name/value，
            // 这里统一解析出“账号标识 + 显示名”，避免 null 落库
            List<Map<String, String>> normalizedTargets = CommChainRules.normalizeTargets(targets);

            BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);
            BizCommSessionManager sessionMgr = commandContext.getSession(BizCommSessionManager.class);
            BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);

            BizTaskExt taskExt = extMgr.findByTaskId(rootTaskId);
            if (taskExt == null) {
                taskExt = new BizTaskExt();
                taskExt.setId(UUID.randomUUID().toString().replace("-", ""));
                taskExt.setTaskId(rootTaskId);
                taskExt.setProcessInstanceId(rootTask.getProcessInstanceId());
                taskExt.setAssignee(rootTask.getAssignee());
                taskExt.setBizStatus(BizTaskExt.STATUS_NORMAL);
                taskExt.setCreateTime(new Date());
                taskExt.setUpdateTime(new Date());
                extMgr.insert(taskExt);
            }

            // ① 已有进行中的会话：在原会话上补充沟通对象，而不是拒绝或另开一个新会话。
            //    这样“少加了人”的二次发起能真正生效，也不会把一次沟通拆成两个会话。
            BizCommSession activeSession = sessionMgr.findActiveByRootTask(rootTaskId);
            if (activeSession != null) {
                if (!initiator.equals(activeSession.getInitiator())) {
                    throw new BusinessException("本次沟通由 " + activeSession.getInitiator()
                            + " 发起，仅发起人可补充沟通对象");
                }
                String appendContent = CommChainRules.firstNonBlank(content, activeSession.getInitialContent(), "");

                // 已在本次沟通中的对象：待反馈/已反馈都跳过；已收回的允许重新发起
                Set<String> joined = new HashSet<>();
                for (BizCommTask exist : taskMgr.findBySessionId(activeSession.getId())) {
                    if (BizCommTask.STATUS_WITHDRAWN.equals(exist.getStatus())) {
                        continue;
                    }
                    joined.add(CommChainRules.targetKey(exist.getTargetType(), exist.getTargetId()));
                }

                List<String> added = new ArrayList<>();
                List<String> skipped = new ArrayList<>();
                for (Map<String, String> t : normalizedTargets) {
                    String key = CommChainRules.targetKey(t.get("type"), t.get("id"));
                    if (joined.contains(key)) {
                        skipped.add(t.get("name"));
                        continue;
                    }
                    createCommTaskRecord(rootTask, activeSession.getId(), null, appendContent, t,
                            extMgr, taskMgr);
                    // 同一次提交里重复勾选同一人时也只建一条
                    joined.add(key);
                    added.add(t.get("name"));
                }
                if (added.isEmpty()) {
                    throw new BusinessException("所选沟通对象已在本次沟通中："
                            + String.join("、", skipped) + "，无需重复发起");
                }

                // 会话本来就是进行中，这里只确保待办仍处于锁定态（幂等）
                taskExt.setBizStatus(BizTaskExt.STATUS_COMMUNICATING);
                taskExt.setActiveSessionId(activeSession.getId());
                taskExt.setUpdateTime(new Date());
                extMgr.update(taskExt);

                result.put("sessionId", activeSession.getId());
                result.put("appended", true);
                result.put("addedTargets", added);
                result.put("skippedTargets", skipped);
                result.put("currentRound", activeSession.getCurrentRound());
                return null;
            }

            // ② 没有进行中的会话时才新建会话。
            //    若扩展表仍标着“沟通中”，说明是历史脏数据（会话已结束但状态没回写、
            //    或会话被直接中断），纠正后继续发起，不再把用户挡在门外。
            if (BizTaskExt.STATUS_COMMUNICATING.equals(taskExt.getBizStatus())) {
                taskExt.setBizStatus(BizTaskExt.STATUS_NORMAL);
                taskExt.setActiveSessionId(null);
            }

            String sessionId = UUID.randomUUID().toString().replace("-", "");
            BizCommSession session = new BizCommSession();
            session.setId(sessionId);
            session.setRootTaskId(rootTaskId);
            session.setInitiator(initiator);
            session.setInitialContent(content);
            session.setStatus(BizCommSession.STATUS_ACTIVE);
            session.setCurrentRound(1);
            session.setCreateTime(new Date());
            sessionMgr.insert(session);

            List<String> created = new ArrayList<>();
            for (Map<String, String> t : normalizedTargets) {
                createCommTaskRecord(rootTask, sessionId, null, content, t, extMgr, taskMgr);
                created.add(t.get("name"));
            }

            taskExt.setBizStatus(BizTaskExt.STATUS_COMMUNICATING);
            taskExt.setActiveSessionId(sessionId);
            taskExt.setUpdateTime(new Date());
            extMgr.update(taskExt);

            result.put("sessionId", sessionId);
            result.put("appended", false);
            result.put("addedTargets", created);
            result.put("skippedTargets", Collections.emptyList());
            return null;
        });

        return result;
    }

    /**
     * 建一条沟通记录：Camunda 旁路任务 + 扩展记录 + 业务沟通记录。
     *
     * <p>首轮沟通、补充对象、追问（见 {@link #continueCommunication}）三处建单逻辑一致，
     * 抽出来避免三份代码各自漂移。</p>
     */
    private void createCommTaskRecord(Task rootTask, String sessionId, String parentRecordId, String content,
                                      Map<String, String> target,
                                      BizTaskExtManager extMgr, BizCommTaskManager taskMgr) {
        String targetType = target.get("type");
        String targetId = target.get("id");
        String targetName = target.get("name");

        // 创建Camunda原生旁路沟通任务（无executionId），
        // taskDefinitionKey 记为「源节点key#COMM」，便于追溯是哪个节点发起的沟通
        TaskEntity commTask = (TaskEntity) taskService.newTask();
        // 沟通任务沿用源待办（当前待办）的名称，待办列表里与源节点保持一致
        commTask.setName(CommChainRules.resolveTaskDisplayName(rootTask));
        commTask.setTaskDefinitionKey(BizCommTask.buildCommTaskKey(rootTask.getTaskDefinitionKey()));
        commTask.setParentTaskId(rootTask.getId());
        commTask.setProcessInstanceId(rootTask.getProcessInstanceId());
        // 必须带上流程定义 ID：standalone 任务若 processDefinitionId 为空，
        // Camunda 解析 taskDefinition 时会退化为按 caseDefinitionId 查 CMMN 定义并抛错。
        // 实测：缺这个字段时，complete 与 deleteTask 都会抛
        // “Invalid case definition id: caseDefinitionId is null”，而不只是某一个操作。
        commTask.setProcessDefinitionId(rootTask.getProcessDefinitionId());
        commTask.setAssignee(targetId);
        commTask.setDescription(content);
        taskService.saveTask(commTask);
        // 任务 ID 由引擎生成，必须在 save 之后取，否则业务表拿到的是空/错值
        String camundaTaskId = commTask.getId();

        // 同时给沟通任务初始化扩展记录
        BizTaskExt commExt = new BizTaskExt();
        commExt.setId(UUID.randomUUID().toString().replace("-", ""));
        commExt.setTaskId(camundaTaskId);
        commExt.setProcessInstanceId(rootTask.getProcessInstanceId());
        commExt.setAssignee(targetId);
        commExt.setBizStatus(BizTaskExt.STATUS_NORMAL);
        commExt.setCreateTime(new Date());
        commExt.setUpdateTime(new Date());
        extMgr.insert(commExt);

        BizCommTask ct = new BizCommTask();
        ct.setId(UUID.randomUUID().toString().replace("-", ""));
        ct.setSessionId(sessionId);
        ct.setParentCommTaskId(parentRecordId);
        ct.setSourceTaskId(rootTask.getId());
        ct.setRootTaskId(rootTask.getId());
        // 单独落一份来源节点 key，报表按节点统计时无需解析 #COMM 后缀
        ct.setSourceTaskDefKey(rootTask.getTaskDefinitionKey());
        ct.setTargetType(targetType);
        ct.setTargetId(targetId);
        ct.setTargetName(targetName);
        ct.setContent(content);
        ct.setStatus(BizCommTask.STATUS_PENDING);
        ct.setCreateTime(new Date());
        // 旁路任务ID 落 CAMUNDA_TASK_ID_，供后续收回/反馈定位任务
        ct.setCamundaTaskId(camundaTaskId);
        // 部门沟通子流程的挂载点（当前未实现，deptProcInstId 保持 null）：
        //   if (BizCommTask.TARGET_DEPT.equals(targetType)) {
        //       ProcessInstance sub = runtimeService.startProcessInstanceByKey("dept_communication_flow", vars);
        //       ct.setDeptProcInstId(sub.getId());
        //   }
        // 两个字段各归各位：任务ID 只进 camundaTaskId，子流程实例ID 只进 deptProcInstId，不再互相顶替。
        taskMgr.insert(ct);
    }

    /**
     * 继续沟通
     */
    public void continueCommunication(String parentCommTaskRecordId, String userId, String content,
                                      List<Map<String, String>> targets) {
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        engineConfig.getCommandExecutorTxRequired().execute((Command<Void>) commandContext -> {
            BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);
            BizCommSessionManager sessionMgr = commandContext.getSession(BizCommSessionManager.class);
            BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);

            BizCommTask parent = taskMgr.findById(parentCommTaskRecordId);
            if (parent == null) throw new BusinessException("父沟通任务不存在");

            BizCommSession session = sessionMgr.findById(parent.getSessionId());
            if (session == null) throw new BusinessException("父沟通任务所属会话不存在");
            // 身份校验：追问等同于继续发起沟通，只有原发起人可操作
            if (userId == null || !userId.equals(session.getInitiator())) {
                throw new BusinessException("仅沟通发起人可继续沟通");
            }
            if (targets == null || targets.isEmpty()) {
                throw new BusinessException("请至少选择一个沟通对象");
            }
            List<Map<String, String>> normalizedTargets = CommChainRules.normalizeTargets(targets);

            // 先确认源待办仍存在：已结束的待办再建旁路任务会得到 processDefinitionId=null，
            // Camunda 解析 taskDefinition 时会退化为按 caseDefinitionId 查 CMMN 并抛错。
            Task rootTask = taskService.createTaskQuery().taskId(session.getRootTaskId()).singleResult();
            if (rootTask == null) {
                throw new BusinessException("源待办已结束，无法继续沟通");
            }

            session.setCurrentRound(session.getCurrentRound() + 1);
            session.setStatus(BizCommSession.STATUS_ACTIVE);
            sessionMgr.update(session);

            // 确保待办锁定
            BizTaskExt ext = extMgr.findByTaskId(session.getRootTaskId());
            if (ext != null) {
                ext.setBizStatus(BizTaskExt.STATUS_COMMUNICATING);
                ext.setActiveSessionId(session.getId());
                ext.setUpdateTime(new Date());
                extMgr.update(ext);
            }

            createFollowUpRecords(rootTask, parent, session, content, normalizedTargets, extMgr, taskMgr);
            return null;
        });
    }

    /**
     * 沟通对象在反馈界面“发起沟通”：把当前这条沟通转给其他人补充核实。
     *
     * <p>与原发起人的“追问”共用同一套建单逻辑，区别只在入口与权限：
     * 追问要求操作人是会话发起人，这里是<b>该条沟通的接收人本人</b>。
     * 新记录挂在当前记录下（{@code PARENT_COMM_TASK_ID_}），所以审批界面的追问树、
     * 流程跟踪页的会话分组都能顺着父链看到“谁转给了谁”，不会出现孤立记录。</p>
     *
     * <p>当前记录保持 PENDING，接收人转交后仍可以继续填写自己的反馈。</p>
     */
    public Map<String, Object> forwardCommunication(String commRecordId, String operator, String content,
                                                    List<Map<String, String>> targets) {
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        Map<String, Object> result = new LinkedHashMap<>();
        engineConfig.getCommandExecutorTxRequired().execute((Command<Void>) commandContext -> {
            BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);
            BizCommSessionManager sessionMgr = commandContext.getSession(BizCommSessionManager.class);
            BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);

            BizCommTask parent = taskMgr.findById(commRecordId);
            if (parent == null) {
                throw new BusinessException("沟通记录不存在");
            }
            // 身份校验：只有这条沟通的接收人本人可以把沟通转给其他人
            if (operator == null || !operator.equals(parent.getTargetId())) {
                throw new BusinessException("仅当前沟通对象本人可再次发起沟通");
            }
            if (!BizCommTask.STATUS_PENDING.equals(parent.getStatus())) {
                throw new BusinessException("该沟通已处理或已收回，无法再次发起沟通");
            }
            BizCommSession session = sessionMgr.findById(parent.getSessionId());
            if (session == null) {
                throw new BusinessException("该沟通所属会话不存在");
            }
            String normalizedContent = CommChainRules.firstNonBlank(content);
            if (normalizedContent == null) {
                throw new BusinessException("请填写沟通内容");
            }
            List<Map<String, String>> normalizedTargets = CommChainRules.normalizeTargets(targets);
            // 上游对象（把沟通转给我的人，以及更上层的人）不能作为本次沟通对象：
            // 他们自己的那条沟通仍在待反馈，转回去会形成互相等待的环。
            Set<String> upstreamTargetIds = CommChainRules.collectUpstreamTargetIds(parent, taskMgr);
            String initiator = session.getInitiator();
            for (Map<String, String> t : normalizedTargets) {
                String targetId = t.get("id");
                // 转给自己没有意义：既不会产生新的待办，也会让追问链自我引用
                if (operator.equals(targetId)) {
                    throw new BusinessException("不能将沟通发起给自己，请选择其他人员");
                }
                // 发起人只负责发起和收回，不承接沟通；转给发起人等同于把问题退回原点
                if (initiator != null && initiator.equals(targetId)) {
                    throw new BusinessException("不能把沟通再发给发起人（" + initiator
                            + "），请直接完成反馈，或转给其他人员");
                }
                if (upstreamTargetIds.contains(targetId)) {
                    throw new BusinessException("不能把沟通转回给上游沟通对象（" + t.get("name")
                            + "），请直接完成反馈，或转给其他人员");
                }
            }

            // 源待办必须仍存在：已结束的待办再建旁路任务会得到 processDefinitionId=null，
            // Camunda 解析 taskDefinition 时会退化为按 caseDefinitionId 查 CMMN 并抛错。
            Task rootTask = taskService.createTaskQuery().taskId(parent.getRootTaskId()).singleResult();
            if (rootTask == null) {
                throw new BusinessException("源待办已结束，无法再次发起沟通");
            }

            session.setCurrentRound(session.getCurrentRound() + 1);
            session.setStatus(BizCommSession.STATUS_ACTIVE);
            sessionMgr.update(session);

            // 保持源待办锁定：转交期间原审批人仍不能审批
            BizTaskExt ext = extMgr.findByTaskId(session.getRootTaskId());
            if (ext != null) {
                ext.setBizStatus(BizTaskExt.STATUS_COMMUNICATING);
                ext.setActiveSessionId(session.getId());
                ext.setUpdateTime(new Date());
                extMgr.update(ext);
            }

            List<String> created = createFollowUpRecords(rootTask, parent, session,
                    normalizedContent, normalizedTargets, extMgr, taskMgr);

            result.put("sessionId", session.getId());
            result.put("currentRound", session.getCurrentRound());
            result.put("addedTargets", created);
            return null;
        });
        return result;
    }

    /**
     * 为首轮之后的沟通建单（追问 / 转交共用）。
     * 每条记录同时创建 Camunda 旁路任务与扩展记录，父链指向 {@code parent}。
     */
    private List<String> createFollowUpRecords(Task rootTask, BizCommTask parent, BizCommSession session,
                                               String content, List<Map<String, String>> targets,
                                               BizTaskExtManager extMgr, BizCommTaskManager taskMgr) {
        String procInstId = rootTask.getProcessInstanceId();
        String procDefId = rootTask.getProcessDefinitionId();
        int round = session.getCurrentRound();
        List<String> created = new ArrayList<>(targets.size());
        for (Map<String, String> t : targets) {
            String newRecordId = UUID.randomUUID().toString().replace("-", "");

            TaskEntity commTask = (TaskEntity) taskService.newTask();
            // 追问同样沿用源待办名称，与源节点保持一致；用 description 保留“追问 + 轮次 + 内容”，
            // 前端/报表需要区分首轮与追问时读 description，不必依赖任务名
            commTask.setName(CommChainRules.resolveTaskDisplayName(rootTask));
            commTask.setDescription("沟通追问（第 " + round + " 轮）：" + content);
            commTask.setTaskDefinitionKey(BizCommTask.buildCommTaskKey(rootTask.getTaskDefinitionKey()));
            commTask.setParentTaskId(parent.getSourceTaskId());
            if (procInstId != null) {
                commTask.setProcessInstanceId(procInstId);
            }
            if (procDefId != null) {
                commTask.setProcessDefinitionId(procDefId);
            }
            commTask.setAssignee(t.get("id"));
            taskService.saveTask(commTask);
            String newCamundaTaskId = commTask.getId();

            BizTaskExt commExt = new BizTaskExt();
            commExt.setId(UUID.randomUUID().toString().replace("-", ""));
            commExt.setTaskId(newCamundaTaskId);
            commExt.setProcessInstanceId(procInstId);
            commExt.setAssignee(t.get("id"));
            commExt.setBizStatus(BizTaskExt.STATUS_NORMAL);
            commExt.setCreateTime(new Date());
            commExt.setUpdateTime(new Date());
            extMgr.insert(commExt);

            BizCommTask ct = new BizCommTask();
            ct.setId(newRecordId);
            ct.setSessionId(parent.getSessionId());
            ct.setParentCommTaskId(parent.getId());
            ct.setSourceTaskId(parent.getSourceTaskId());
            ct.setRootTaskId(parent.getRootTaskId());
            // 追问同样归属会话发起节点；历史父记录可能没有该字段，回退到父记录的上游节点
            ct.setSourceTaskDefKey(rootTask.getTaskDefinitionKey() != null
                    ? rootTask.getTaskDefinitionKey() : parent.getSourceTaskDefKey());
            ct.setTargetType(t.get("type"));
            ct.setTargetId(t.get("id"));
            ct.setTargetName(t.get("name"));
            ct.setContent(content);
            ct.setStatus(BizCommTask.STATUS_PENDING);
            ct.setCreateTime(new Date());
            ct.setCamundaTaskId(newCamundaTaskId);
            taskMgr.insert(ct);
            created.add(t.get("name"));
        }
        return created;
    }
}
