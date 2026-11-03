package com.example.camundademo.service;

import com.example.camundademo.entity.BizCommSession;
import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.entity.BizTaskExt;
import com.example.camundademo.persistence.BizCommSessionManager;
import com.example.camundademo.persistence.BizCommTaskManager;
import com.example.camundademo.persistence.BizTaskExtManager;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.persistence.entity.TaskEntity;
import org.camunda.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 旁路沟通核心服务
 */
@Service
public class CommunicationService {

    @Autowired
    private TaskService taskService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ProcessEngine processEngine;

    private ProcessEngineConfigurationImpl getEngineConfig() {
        return (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    }

    /**
     * 发起沟通
     */
    public String startCommunication(String rootTaskId, String initiator, String content,
                                     List<Map<String, String>> targets) {
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        String[] result = new String[1];

        engineConfig.getCommandExecutorTxRequired().execute((Command<Void>) commandContext -> {
            Task rootTask = taskService.createTaskQuery().taskId(rootTaskId).singleResult();
            if (rootTask == null) {
                throw new BusinessException("源待办不存在");
            }
            // 身份校验：只有当前待办处理人才能发起沟通，避免任意用户代他人发起
            if (initiator == null || !initiator.equals(rootTask.getAssignee())) {
                throw new BusinessException("仅当前待办处理人可发起沟通");
            }
            if (targets == null || targets.isEmpty()) {
                throw new BusinessException("请至少选择一个沟通对象");
            }

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

            if (BizTaskExt.STATUS_COMMUNICATING.equals(taskExt.getBizStatus())) {
                throw new BusinessException("当前待办已有进行中的沟通，请勿重复发起");
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

            for (Map<String, String> t : targets) {
                String commRecordId = UUID.randomUUID().toString().replace("-", "");
                String targetType = t.get("type");
                String targetId = t.get("id");
                String targetName = t.get("name");

                // 创建Camunda原生旁路沟通任务（taskDefinitionKey=COMM，无executionId）
                TaskEntity commTask = (TaskEntity) taskService.newTask();
                commTask.setName("沟通：请反馈意见 - " + content);
                commTask.setTaskDefinitionKey("COMM");
                commTask.setParentTaskId(rootTaskId);
                commTask.setProcessInstanceId(rootTask.getProcessInstanceId());
                // 必须带上流程定义 ID：standalone 任务若 processDefinitionId 为空，
                // Camunda 解析 taskDefinition 时会退化为按 caseDefinitionId 查 CMMN 定义并抛错
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
                ct.setId(commRecordId);
                ct.setSessionId(sessionId);
                ct.setParentCommTaskId(null);
                ct.setSourceTaskId(rootTaskId);
                ct.setRootTaskId(rootTaskId);
                ct.setTargetType(targetType);
                ct.setTargetId(targetId);
                ct.setTargetName(targetName);
                ct.setContent(content);
                ct.setStatus(BizCommTask.STATUS_PENDING);
                ct.setCreateTime(new Date());
                // 保存Camunda任务ID到deptProcInstId字段临时存储，用于后续删除
                ct.setDeptProcInstId(camundaTaskId);
                taskMgr.insert(ct);
            }

            taskExt.setBizStatus(BizTaskExt.STATUS_COMMUNICATING);
            taskExt.setActiveSessionId(sessionId);
            taskExt.setUpdateTime(new Date());
            extMgr.update(taskExt);

            result[0] = sessionId;
            return null;
        });

        return result[0];
    }

    /**
     * 收回沟通（部分或全部）
     */
    public void withdrawCommunication(String sessionId, String operator, List<String> commTaskIds) {
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        engineConfig.getCommandExecutorTxRequired().execute((Command<Void>) commandContext -> {
            BizCommSessionManager sessionMgr = commandContext.getSession(BizCommSessionManager.class);
            BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);
            BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);

            BizCommSession session = sessionMgr.findById(sessionId);
            if (session == null) throw new BusinessException("会话不存在");
            if (!session.getInitiator().equals(operator)) {
                throw new BusinessException("仅首发人可收回沟通");
            }

            Set<String> toWithdraw = new HashSet<>();
            // 一次性查出会话内全部沟通任务，之后全部在内存中判定状态，
            // 避免同一命令内“改了状态再查库”读到旧值（Camunda 命令内不做中途 flush）
            List<BizCommTask> allTasks = taskMgr.findBySessionId(sessionId);
            if (commTaskIds == null || commTaskIds.isEmpty()) {
                for (BizCommTask t : allTasks) {
                    if (BizCommTask.STATUS_PENDING.equals(t.getStatus())) {
                        collectChildren(allTasks, t.getId(), toWithdraw);
                    }
                }
            } else {
                for (String tid : commTaskIds) collectChildren(allTasks, tid, toWithdraw);
            }

            for (String tid : toWithdraw) {
                BizCommTask ct = taskMgr.findById(tid);
                if (ct == null) continue;
                if (!BizCommTask.STATUS_PENDING.equals(ct.getStatus())) continue;
                ct.setStatus(BizCommTask.STATUS_WITHDRAWN);
                taskMgr.update(ct);
                // 删除对应的Camunda沟通任务（cascade=true级联删除子任务）
                String camundaTaskId = ct.getDeptProcInstId();
                if (camundaTaskId != null) {
                    taskService.deleteTask(camundaTaskId, true);
                }
            }

            // 会话内仍有未处理（PENDING）的沟通任务时保持锁定：直接用内存快照判断
            boolean stillPending = false;
            for (BizCommTask t : allTasks) {
                if (toWithdraw.contains(t.getId())) continue;
                if (BizCommTask.STATUS_PENDING.equals(t.getStatus())) {
                    stillPending = true;
                    break;
                }
            }
            if (!stillPending) {
                session.setStatus(BizCommSession.STATUS_COMPLETED);
                session.setFinishTime(new Date());
                sessionMgr.update(session);

                BizTaskExt ext = extMgr.findByTaskId(session.getRootTaskId());
                if (ext != null) {
                    ext.setBizStatus(BizTaskExt.STATUS_NORMAL);
                    ext.setActiveSessionId(null);
                    ext.setUpdateTime(new Date());
                    extMgr.update(ext);
                }
            }
            return null;
        });
    }

    private void collectChildren(List<BizCommTask> allTasks, String taskId, Set<String> result) {
        if (!result.add(taskId)) return;
        for (BizCommTask c : allTasks) {
            if (taskId.equals(c.getParentCommTaskId())) {
                collectChildren(allTasks, c.getId(), result);
            }
        }
    }

    /**
     * 反馈沟通意见
     */
    public void replyCommTask(String commTaskRecordId, String userId, String replyContent) {
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        engineConfig.getCommandExecutorTxRequired().execute((Command<Void>) commandContext -> {
            BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);
            BizCommSessionManager sessionMgr = commandContext.getSession(BizCommSessionManager.class);
            BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);

            BizCommTask ct = taskMgr.findById(commTaskRecordId);
            if (ct == null) throw new BusinessException("沟通任务不存在");
            // 身份校验：只有该条沟通的接收人本人可反馈，杜绝代填/伪造反馈
            if (userId == null || !userId.equals(ct.getTargetId())) {
                throw new BusinessException("仅沟通对象本人可反馈该条沟通");
            }
            if (!BizCommTask.STATUS_PENDING.equals(ct.getStatus())) {
                throw new BusinessException("该沟通已处理或已收回");
            }

            ct.setStatus(BizCommTask.STATUS_REPLIED);
            ct.setReplyContent(replyContent);
            ct.setReplyUserId(userId);
            ct.setReplyTime(new Date());
            taskMgr.update(ct);

            // 先取会话快照，再基于快照判断是否还有其它未处理的沟通任务（避免写后读）
            List<BizCommTask> allTasks = taskMgr.findBySessionId(ct.getSessionId());
            boolean stillPending = false;
            for (BizCommTask t : allTasks) {
                if (t.getId().equals(ct.getId())) continue;
                if (BizCommTask.STATUS_PENDING.equals(t.getStatus())) {
                    stillPending = true;
                    break;
                }
            }

            // 结束对应的 Camunda 旁路待办，避免接收人待办列表残留
            String camundaTaskId = ct.getDeptProcInstId();
            if (camundaTaskId != null) {
                Task commTask = taskService.createTaskQuery().taskId(camundaTaskId).singleResult();
                if (commTask != null) {
                    taskService.deleteTask(camundaTaskId, "沟通已反馈");
                }
            }

            // 会话内没有待处理沟通任务时（含多轮子沟通）才解锁主待办
            if (!stillPending) {
                BizCommSession session = sessionMgr.findById(ct.getSessionId());
                if (session != null) {
                    session.setStatus(BizCommSession.STATUS_COMPLETED);
                    session.setFinishTime(new Date());
                    sessionMgr.update(session);

                    BizTaskExt ext = extMgr.findByTaskId(session.getRootTaskId());
                    if (ext != null) {
                        ext.setBizStatus(BizTaskExt.STATUS_NORMAL);
                        ext.setActiveSessionId(null);
                        ext.setUpdateTime(new Date());
                        extMgr.update(ext);
                    }
                }
            }
            return null;
        });
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

            // 先确认源待办仍存在：已结束的待办再建旁路任务会得到 processDefinitionId=null，
            // Camunda 解析 taskDefinition 时会退化为按 caseDefinitionId 查 CMMN 并抛错。
            Task rootTask = taskService.createTaskQuery().taskId(session.getRootTaskId()).singleResult();
            if (rootTask == null) {
                throw new BusinessException("源待办已结束，无法继续沟通");
            }
            String procInstId = rootTask.getProcessInstanceId();
            String procDefId = rootTask.getProcessDefinitionId();

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

            for (Map<String, String> t : targets) {
                String newRecordId = UUID.randomUUID().toString().replace("-", "");

                TaskEntity commTask = (TaskEntity) taskService.newTask();
                commTask.setName("沟通追问：" + content);
                commTask.setTaskDefinitionKey("COMM");
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
                ct.setParentCommTaskId(parentCommTaskRecordId);
                ct.setSourceTaskId(parent.getSourceTaskId());
                ct.setRootTaskId(parent.getRootTaskId());
                ct.setTargetType(t.get("type"));
                ct.setTargetId(t.get("id"));
                ct.setTargetName(t.get("name"));
                ct.setContent(content);
                ct.setStatus(BizCommTask.STATUS_PENDING);
                ct.setCreateTime(new Date());
                ct.setDeptProcInstId(newCamundaTaskId);
                taskMgr.insert(ct);
            }
            return null;
        });
    }

    /**
     * 查询待办沟通状态
     */
    public Map<String, Object> getTaskCommStatus(String taskId, String currentUserId) {
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        return engineConfig.getCommandExecutorTxRequired().execute((Command<Map<String, Object>>) commandContext -> {
            BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);
            BizCommSessionManager sessionMgr = commandContext.getSession(BizCommSessionManager.class);
            BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);

            BizTaskExt ext = extMgr.findByTaskId(taskId);
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("taskId", taskId);

            Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
            boolean isCommTask = task != null && "COMM".equals(task.getTaskDefinitionKey());
            vo.put("isCommTask", isCommTask);

            String bizStatus = ext == null ? BizTaskExt.STATUS_NORMAL : ext.getBizStatus();
            String activeSessionId = ext == null ? null : ext.getActiveSessionId();
            vo.put("bizStatus", bizStatus);
            vo.put("canApprove", BizTaskExt.STATUS_NORMAL.equals(bizStatus) && !isCommTask);
            vo.put("canInitiate", !isCommTask && task != null
                    && currentUserId != null && currentUserId.equals(task.getAssignee()));
            vo.put("canWithdraw", false);
            vo.put("activeSessionId", activeSessionId);

            // 会话进行中优先取 activeSession；结束后 activeSessionId 会被清空，
            // 此时回退到该待办最近一次会话，保证沟通内容/对象/回复事后仍可查。
            BizCommSession session = null;
            boolean sessionActive = false;
            if (activeSessionId != null) {
                session = sessionMgr.findById(activeSessionId);
                sessionActive = session != null;
            }
            if (session == null) {
                session = sessionMgr.findLatestByRootTask(taskId);
                sessionActive = false;
            }

            if (session == null) {
                vo.put("sessionId", null);
                vo.put("sessionActive", false);
                vo.put("commTasks", Collections.emptyList());
            } else {
                boolean isActiveSession = sessionActive && activeSessionId != null
                        && activeSessionId.equals(session.getId());
                vo.put("sessionId", session.getId());
                vo.put("sessionStatus", session.getStatus());
                vo.put("sessionActive", isActiveSession);
                vo.put("initiator", session.getInitiator());
                vo.put("initialContent", session.getInitialContent());
                vo.put("currentRound", session.getCurrentRound());
                vo.put("createTime", session.getCreateTime());
                vo.put("finishTime", session.getFinishTime());
                vo.put("canWithdraw", isActiveSession
                        && session.getInitiator().equals(currentUserId)
                        && BizTaskExt.STATUS_COMMUNICATING.equals(bizStatus));

                List<BizCommTask> commTasks = taskMgr.findBySessionId(session.getId());
                List<Map<String, Object>> taskVOList = new ArrayList<>();
                for (BizCommTask ct : commTasks) {
                    Map<String, Object> tvo = new LinkedHashMap<>();
                    tvo.put("id", ct.getId());
                    tvo.put("parentCommTaskId", ct.getParentCommTaskId());
                    tvo.put("targetId", ct.getTargetId());
                    tvo.put("targetName", ct.getTargetName());
                    tvo.put("targetType", ct.getTargetType());
                    tvo.put("status", ct.getStatus());
                    tvo.put("content", ct.getContent());
                    tvo.put("replyContent", ct.getReplyContent());
                    tvo.put("replyUserId", ct.getReplyUserId());
                    tvo.put("replyTime", ct.getReplyTime());
                    tvo.put("createTime", ct.getCreateTime());
                    taskVOList.add(tvo);
                }
                vo.put("commTasks", taskVOList);
            }

            // 只有“真实存在的流程待办”才谈得上继续沟通：
            // 沟通旁路任务本身、以及已完成的待办（task == null）都不应返回可继续沟通
            vo.put("canContinueComm", !isCommTask && task != null);
            return vo;
        });
    }
}
