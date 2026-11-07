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
import org.camunda.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 沟通查询：待办沟通状态聚合、会话/记录只读查询、展示用的发起人回溯。
 *
 * <p>只读，不改任何状态；但仍在引擎命令内执行，保证读取的 Manager 与当前
 * CommandContext 一致（Manager 是引擎的 Session，不能脱离命令自行开库连接）。</p>
 */
@Service
public class CommQueryService {

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private org.camunda.bpm.engine.IdentityService identityService;

    private ProcessEngineConfigurationImpl getEngineConfig() {
        return (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
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

            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("taskId", taskId);

            Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
            boolean isCommTask = task != null && BizCommTask.isCommTaskKey(task.getTaskDefinitionKey());
            vo.put("isCommTask", isCommTask);
            // 待办本身是追问任务时直接给出标识，前端无需再解析 taskDefinitionKey
            boolean isFollowUp = false;
            BizCommTask selfRecord = null;
            if (isCommTask) {
                selfRecord = resolveCommRecord(taskMgr, taskId, task);
                isFollowUp = selfRecord != null && selfRecord.getParentCommTaskId() != null;
            }
            vo.put("isFollowUp", isFollowUp);

            // 沟通待办的真实根待办不一定是它自己：部门沟通由独立子流程承接，
            // dept_handle 是子流程里的任务，会话与扩展记录都挂在源待办上。
            // 后面一律按根待办查会话与扩展记录，否则部门任务永远查不到（表现为 commTasks 为空）。
            String rootTaskId = selfRecord != null && selfRecord.getRootTaskId() != null
                    ? selfRecord.getRootTaskId() : taskId;

            BizTaskExt ext = extMgr.findByTaskId(taskId);
            if (ext == null && !taskId.equals(rootTaskId)) {
                ext = extMgr.findByTaskId(rootTaskId);
            }
            String bizStatus = ext == null ? BizTaskExt.STATUS_NORMAL : ext.getBizStatus();
            String activeSessionId = ext == null ? null : ext.getActiveSessionId();
            vo.put("bizStatus", bizStatus);
            vo.put("canApprove", BizTaskExt.STATUS_NORMAL.equals(bizStatus) && !isCommTask);
            vo.put("canInitiate", !isCommTask && task != null
                    && currentUserId != null && currentUserId.equals(task.getAssignee()));
            // 沟通待办本身也能“再发起沟通”：由当前沟通对象把问题转给其他人补充核实，
            // 新记录挂在当前沟通记录下，仍属于同一会话（见 CommTaskFactory#forwardCommunication）。
            // 部门沟通的“当前对象”是整个部门，任何部门成员都可以再发起。
            vo.put("canForwardComm", isCommTask && selfRecord != null
                    && BizCommTask.STATUS_PENDING.equals(selfRecord.getStatus())
                    && isTargetOperator(selfRecord, currentUserId));
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
                session = sessionMgr.findLatestByRootTask(rootTaskId);
                sessionActive = false;
            }

            // 同一待办可以先后发起多次沟通，每次“发起沟通”都会生成一个新会话。
            // 审批界面不能只回“当前/最新一次”，否则前面几轮沟通会整体消失。
            List<BizCommSession> allSessions = sessionMgr.findByRootTask(rootTaskId);
            if (allSessions == null || allSessions.isEmpty()) {
                allSessions = session == null
                        ? Collections.emptyList() : Collections.singletonList(session);
            }

            // 先一次性取出全部会话的沟通记录，父记录跨会话时也能找到
            Map<String, List<BizCommTask>> tasksBySession = new LinkedHashMap<>();
            Map<String, BizCommTask> recordById = new HashMap<>();
            for (BizCommSession s : allSessions) {
                List<BizCommTask> records = taskMgr.findBySessionId(s.getId());
                tasksBySession.put(s.getId(), records);
                for (BizCommTask ct : records) {
                    if (ct.getId() != null) {
                        recordById.put(ct.getId(), ct);
                    }
                }
            }

            List<Map<String, Object>> sessionVOList = new ArrayList<>();
            List<Map<String, Object>> allTaskVOList = new ArrayList<>();
            for (int i = 0; i < allSessions.size(); i++) {
                BizCommSession s = allSessions.get(i);
                int seq = i + 1;
                boolean sessionIsActive = sessionActive && activeSessionId != null
                        && activeSessionId.equals(s.getId());
                List<Map<String, Object>> sessionTaskVOList = new ArrayList<>();
                for (BizCommTask ct : tasksBySession.getOrDefault(s.getId(), Collections.emptyList())) {
                    Map<String, Object> tvo = toCommTaskVO(ct, s, seq, sessionIsActive,
                            recordById, currentUserId, rootTaskId);
                    sessionTaskVOList.add(tvo);
                    allTaskVOList.add(tvo);
                }
                Map<String, Object> svo = new LinkedHashMap<>();
                svo.put("sessionId", s.getId());
                svo.put("seq", seq);
                svo.put("status", s.getStatus());
                svo.put("active", sessionIsActive);
                svo.put("initiator", s.getInitiator());
                svo.put("initialContent", s.getInitialContent());
                svo.put("currentRound", s.getCurrentRound());
                svo.put("createTime", s.getCreateTime());
                svo.put("finishTime", s.getFinishTime());
                svo.put("commTasks", sessionTaskVOList);
                sessionVOList.add(svo);
            }
            vo.put("sessions", sessionVOList);

            if (session == null) {
                vo.put("sessionId", null);
                vo.put("sessionActive", false);
                vo.put("sessionStatus", null);
                vo.put("initiator", null);
                vo.put("initialContent", null);
                vo.put("currentRound", null);
                vo.put("createTime", null);
                vo.put("finishTime", null);
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
            }
            // 顶层 commTasks 返回全部会话的记录合集，保证“多次沟通”一条都不丢；
            // 每条记录自带 sessionId/sessionSeq，前端可按会话分组展示。
            vo.put("commTasks", allTaskVOList);

            // 沟通待办能否“提交反馈完成”：本人、待反馈、且下游没有仍在等待反馈的沟通。
            // 这里与 CommStateService#replyCommTask 的校验保持同一口径，前端按钮据此禁用并给出原因。
            boolean canReply = false;
            String replyBlockedBy = null;
            if (isCommTask && selfRecord != null
                    && BizCommTask.STATUS_PENDING.equals(selfRecord.getStatus())
                    && isTargetOperator(selfRecord, currentUserId)) {
                List<BizCommTask> selfSessionTasks = tasksBySession.get(selfRecord.getSessionId());
                if (selfSessionTasks == null) {
                    selfSessionTasks = taskMgr.findBySessionId(selfRecord.getSessionId());
                }
                BizCommTask pendingChild =
                        CommChainRules.findPendingDescendant(selfRecord.getId(), selfSessionTasks);
                canReply = pendingChild == null;
                replyBlockedBy = pendingChild == null ? null : CommChainRules.commTargetLabel(pendingChild);
            }
            vo.put("canReply", canReply);
            vo.put("replyBlockedBy", replyBlockedBy);

            // 只有“真实存在的流程待办”才谈得上继续沟通：
            // 沟通旁路任务本身、以及已完成的待办（task == null）都不应返回可继续沟通
            vo.put("canContinueComm", !isCommTask && task != null);
            return vo;
        });
    }

    /**
     * 构建单条沟通记录 VO，并带上所属会话信息。
     * <p>前端据此按“第 N 次沟通”分组渲染，同一待办多次沟通不会互相覆盖。</p>
     */
    private Map<String, Object> toCommTaskVO(BizCommTask ct, BizCommSession session, int seq,
                                             boolean sessionActive, Map<String, BizCommTask> recordById,
                                             String currentUserId, String rootTaskId) {
        Map<String, Object> tvo = new LinkedHashMap<>();
        tvo.put("id", ct.getId());
        tvo.put("sessionId", session.getId());
        tvo.put("sessionSeq", seq);
        tvo.put("sessionStatus", session.getStatus());
        tvo.put("sessionActive", sessionActive);
        tvo.put("sessionInitiator", session.getInitiator());
        tvo.put("sessionInitialContent", session.getInitialContent());
        tvo.put("sessionCreateTime", session.getCreateTime());
        tvo.put("parentCommTaskId", ct.getParentCommTaskId());
        // 有父沟通记录即为追问，便于前端列表直接区分“首轮沟通 / 追问”
        tvo.put("isFollowUp", ct.getParentCommTaskId() != null);
        // 父级快照：追问关系不能只靠前端按 id 自行匹配。父记录可能不在本次
        // 会话返回范围内（跨会话追问、父记录被清理），前端就还原不出层级。
        // 这里直接带回父级的对象名与沟通内容，前端无父记录时也能说明“追问自谁”。
        BizCommTask parentRecord = ct.getParentCommTaskId() == null
                ? null : recordById.get(ct.getParentCommTaskId());
        tvo.put("parentTargetName", parentRecord == null ? null : parentRecord.getTargetName());
        tvo.put("parentContent", parentRecord == null ? null : parentRecord.getContent());
        tvo.put("parentInList", parentRecord != null);
        tvo.put("sourceTaskDefKey", resolveSourceTaskDefKey(ct, rootTaskId));
        tvo.put("targetId", ct.getTargetId());
        tvo.put("targetName", ct.getTargetName());
        tvo.put("targetType", ct.getTargetType());
        tvo.put("status", ct.getStatus());
        tvo.put("content", ct.getContent());
        tvo.put("replyContent", ct.getReplyContent());
        tvo.put("replyUserId", ct.getReplyUserId());
        tvo.put("replyTime", ct.getReplyTime());
        tvo.put("withdrawTime", ct.getWithdrawTime());
        tvo.put("createTime", ct.getCreateTime());
        // 收回必须落到记录所属的会话上：历史会话的记录不能拿当前会话 id 去收回。
        // 权限口径与 withdrawCommunication 一致：发起人可收回任意记录，
        // 上游沟通对象可收回自己转出去的整棵下游子树（含隔级记录）。
        tvo.put("canWithdrawOne", sessionActive
                && CommChainRules.canWithdrawRecord(ct, session, currentUserId, recordById));
        return tvo;
    }

    /**
     * 取沟通记录的来源节点 key。
     * 新记录直接读字段；历史记录（字段为空）从旁路任务 key 还原，再退到根待办的节点 key。
     */
    private String resolveSourceTaskDefKey(BizCommTask ct, String rootTaskId) {
        if (ct.getSourceTaskDefKey() != null) {
            return ct.getSourceTaskDefKey();
        }
        String fromCommTask = BizCommTask.parseSourceTaskDefKey(taskDefinitionKeyOf(ct.getCamundaTaskId()));
        if (fromCommTask != null) {
            return fromCommTask;
        }
        return BizCommTask.parseSourceTaskDefKey(taskDefinitionKeyOf(rootTaskId));
    }

    /** 查询任务节点 key，任务不存在时返回 null（不抛异常） */
    private String taskDefinitionKeyOf(String taskId) {
        if (taskId == null) {
            return null;
        }
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        return task == null ? null : task.getTaskDefinitionKey();
    }

    /**
     * 当前用户是否就是这条沟通的“接收方”。
     * <p>USER 是接收人本人；DEPT 是部门成员（部门内任意成员都有反馈权）。</p>
     */
    private boolean isTargetOperator(BizCommTask record, String userId) {
        if (record == null || userId == null) {
            return false;
        }
        if (BizCommTask.TARGET_DEPT.equals(record.getTargetType())) {
            return record.getTargetId() != null && identityService.createUserQuery()
                    .userId(userId)
                    .memberOfGroup(record.getTargetId())
                    .count() > 0;
        }
        return userId.equals(record.getTargetId());
    }

    // ===== 待办中心用的只读查询 =====

    /**
     * 按 Camunda 任务ID 查沟通记录（待办中心识别“这是一条沟通待办”后取上下文用）。
     * 任务ID 存在 {@code CAMUNDA_TASK_ID_}；{@code DEPT_PROC_INST_ID_} 已成部门子流程实例ID 专用列。
     */
    public BizCommTask getCommRecordByCamundaTaskId(String camundaTaskId) {
        if (camundaTaskId == null || camundaTaskId.isEmpty()) {
            return null;
        }
        // 任务在命令外先取出来：resolveCommRecord 需要它的流程实例ID 做兜底
        Task task = taskService.createTaskQuery().taskId(camundaTaskId).singleResult();
        return getEngineConfig().getCommandExecutorTxRequired().execute(
                (Command<BizCommTask>) commandContext -> resolveCommRecord(
                        commandContext.getSession(BizCommTaskManager.class), camundaTaskId, task));
    }

    /**
     * 按任务ID 取沟通记录；取不到时用任务所在流程实例ID 再查一次。
     *
     * <p>部门沟通走独立子流程，沟通记录是在<b>同一个 Camunda 命令内</b>插入业务表的，
     * 此时 dept_handle 任务还没 flush，拿不到任务ID，{@code CAMUNDA_TASK_ID_} 只能落空。
     * mapper 同时按 {@code DEPT_PROC_INST_ID_} 匹配，所以用子流程实例ID 即可反查到这条记录。
     * 没有这层兜底，部门成员在沟通待办上「再发起沟通」永远不可用、待办里的
     * {@code targetType} 也为空。</p>
     */
    private BizCommTask resolveCommRecord(BizCommTaskManager taskMgr, String taskId, Task task) {
        BizCommTask record = taskMgr.findByCamundaTaskId(taskId);
        if (record != null) {
            return record;
        }
        String procInstId = task == null ? null : task.getProcessInstanceId();
        // 业务主流程的任务也带实例ID，但那里没有部门记录，多查一次不会有副作用；
        // 仅排除实例ID 与任务ID 相同这种无意义查询
        if (procInstId == null || procInstId.equals(taskId)) {
            return null;
        }
        return taskMgr.findByCamundaTaskId(procInstId);
    }

    /** 查会话内全部沟通记录（用于展示同一轮其他对象的反馈进度） */
    public List<BizCommTask> getCommRecordsBySessionId(String sessionId) {
        return getEngineConfig().getCommandExecutorTxRequired().execute(
                (Command<List<BizCommTask>>) commandContext -> commandContext
                        .getSession(BizCommTaskManager.class)
                        .findBySessionId(sessionId));
    }

    /**
     * 按来源待办任务批量查沟通记录（跨会话）。
     *
     * <p>流程跟踪图补全「已收回」记录时用。收回走 {@code taskService.deleteTask(...)}：
     * 引擎会保留 ACT_HI_TASKINST 并把 {@code DELETE_REASON_} 写成 {@code deleted}，
     * 但历史里只有“删除”这一个事实，拿不到业务状态（WITHDRAWN / 待反馈）、收回时间和沟通链关系。
     * 业务表才是这些字段的唯一来源；同一 taskId 会同时出现在历史与业务表中，
     * 调用方按 taskId 去重后再合并，不能只取其一。</p>
     */
    public List<BizCommTask> getCommRecordsBySourceTaskIds(Collection<String> sourceTaskIds) {
        if (sourceTaskIds == null || sourceTaskIds.isEmpty()) {
            return new ArrayList<>();
        }
        return getEngineConfig().getCommandExecutorTxRequired().execute(
                (Command<List<BizCommTask>>) commandContext -> commandContext
                        .getSession(BizCommTaskManager.class)
                        .findBySourceTaskIds(sourceTaskIds));
    }

    /** 按ID查会话 */
    public BizCommSession getSessionById(String sessionId) {
        return getEngineConfig().getCommandExecutorTxRequired().execute(
                (Command<BizCommSession>) commandContext -> commandContext
                        .getSession(BizCommSessionManager.class)
                        .findById(sessionId));
    }

    /** 按业务记录ID查沟通记录（父链回溯用） */
    public BizCommTask getCommRecordById(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return getEngineConfig().getCommandExecutorTxRequired().execute(
                (Command<BizCommTask>) commandContext -> commandContext
                        .getSession(BizCommTaskManager.class)
                        .findById(id));
    }

    /**
     * 一条沟通记录真正的“发起人”（把这条沟通发出去的人）。
     *
     * <p>不能拿源待办处理人代替：转交/追问记录挂在父记录下，发出去的人是父记录的
     * 沟通对象（如 Carol 把沟通转给 Erin），而源待办处理人始终是审批人（manager）。
     * 首轮与“补充沟通对象”没有父记录，发起人就是会话发起人。</p>
     */
    public String resolveInitiatorId(BizCommTask record) {
        if (record == null) {
            return null;
        }
        if (record.getParentCommTaskId() != null) {
            BizCommTask parent = getCommRecordById(record.getParentCommTaskId());
            if (parent != null) {
                return parent.getTargetId();
            }
        }
        BizCommSession session = getSessionById(record.getSessionId());
        return session == null ? null : session.getInitiator();
    }

    /**
     * 沟通发起人的显示名。转交/追问取父记录的沟通对象名；
     * 首轮会话只落库了账号，没有显示名，返回 null 由调用方回退到账号。
     */
    public String resolveInitiatorName(BizCommTask record) {
        if (record == null || record.getParentCommTaskId() == null) {
            return null;
        }
        BizCommTask parent = getCommRecordById(record.getParentCommTaskId());
        return parent == null ? null : parent.getTargetName();
    }
}
