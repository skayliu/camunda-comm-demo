package com.example.camundademo.service;

import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.entity.BizSubProcessDef;
import com.example.camundademo.persistence.BizCommTaskManager;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.identity.Group;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 部门沟通子流程：启动、取消、部门任务识别与候选成员判定。
 *
 * <p>沟通对象类型为 {@code DEPT} 时，不再像人一样建一条 standalone 旁路任务
 * （旁路任务的 assignee 只能是一个人，把部门ID当账号写进去，部门里没有任何人能收到），
 * 而是启动 {@value #PROCESS_KEY} 子流程：{@code dept_handle} 用候选组承接，
 * 部门内任意成员认领后提交意见，由 {@link DeptCommReplyListener} 回写沟通记录。</p>
 *
 * <p>本类只做“子流程生命周期”，不碰业务表，避免与 {@link CommTaskFactory}、
 * {@link CommStateService} 形成循环依赖。</p>
 */
@Service
public class DeptCommProcessService {

    private static final Logger log = LoggerFactory.getLogger(DeptCommProcessService.class);

    /** 部门沟通子流程的流程定义 key（与 dept_communication.bpmn 中的 process id 一致） */
    public static final String PROCESS_KEY = "dept_communication";

    /** 部门处理节点 key（与 dept_communication.bpmn 中的 userTask id 一致） */
    public static final String TASK_KEY = BizCommTask.DEPT_TASK_KEY;

    /** 沟通类型标注：启动子流程时写入，查历史/报表据此 100% 区分“部门沟通”与“业务子流程” */
    public static final String COMM_TYPE_DEPT = "DEPT";

    public static final String VAR_DEPT_ID = "deptId";
    public static final String VAR_DEPT_NAME = "deptName";
    public static final String VAR_COMM_TYPE = "commType";
    public static final String VAR_COMM_RECORD_ID = "commRecordId";
    public static final String VAR_SESSION_ID = "sessionId";
    public static final String VAR_ROOT_TASK_ID = "rootTaskId";
    public static final String VAR_SOURCE_TASK_ID = "sourceTaskId";
    public static final String VAR_SOURCE_TASK_DEF_KEY = "sourceTaskDefKey";
    public static final String VAR_CONTENT = "content";
    public static final String VAR_INITIATOR = "initiator";
    /**
     * 发起人变量：待办中心 / 已办 / 我的发起三张列表的「发起人」列都读这个变量
     * （见 WorkbenchService），与引擎的 {@code START_USER_ID_} 是两条独立链路。
     * 只写 {@link #VAR_INITIATOR} 的话，流程实例列表有值、待办中心仍然为空。
     */
    public static final String VAR_APPLICANT = "applicant";
    public static final String VAR_ROUND = "commRound";
    /** 父流程（业务主流程）实例ID：主流程结束时据此级联终止部门子流程 */
    public static final String VAR_PARENT_PROC_INST_ID = "parentProcessInstanceId";
    /** 部门反馈内容，由反馈接口写入，完成监听器读取后落业务表 */
    public static final String VAR_REPLY = "deptReply";
    /** 原始沟通内容（统一契约入参，专属流程内部的节点可自行引用） */
    public static final String VAR_ORIGIN_CONTENT = "originContent";
    /** 沟通会话ID（与 commRecordId 等价，保留文档中的契约名） */
    public static final String VAR_COMMUNICATE_ID = "communicateId";
    /** 本次实际使用的子流程 key，便于跟踪图/排查时直接看出走了哪个部门流程 */
    public static final String VAR_SUB_PROCESS_KEY = "commSubProcessKey";

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private SubProcessDefService subProcessDefService;

    /** 启动子流程后返回的实例与节点信息 */
    public static final class Started {
        private final String processInstanceId;
        private final String taskId;

        Started(String processInstanceId, String taskId) {
            this.processInstanceId = processInstanceId;
            this.taskId = taskId;
        }

        public String getProcessInstanceId() {
            return processInstanceId;
        }

        public String getTaskId() {
            return taskId;
        }
    }

    /**
     * 启动部门沟通子流程。
     *
     * @param deptId 部门ID，同时作为 dept_handle 的候选组
     * @return 子流程实例ID 与当前的部门任务ID（任务ID 用于待办反查，允许为空）
     */
    public Started start(String commRecordId, String sessionId, Task rootTask, String deptId,
                         String deptName, String content, String initiator, int round) {
        return start(commRecordId, sessionId, rootTask, deptId, deptName, content, initiator, round,
                PROCESS_KEY);
    }

    /**
     * 启动部门沟通子流程（按注册表选定的 key）。
     *
     * @param subProcessKey 注册表里的流程定义 key；为空时回退到旧版单节点流程
     */
    public Started start(String commRecordId, String sessionId, Task rootTask, String deptId,
                         String deptName, String content, String initiator, int round,
                         String subProcessKey) {
        requireDept(deptId);
        String key = (subProcessKey == null || subProcessKey.isBlank()) ? PROCESS_KEY : subProcessKey;
        if (subProcessKey != null && !subProcessKey.isBlank() && !PROCESS_KEY.equals(subProcessKey)) {
            // 前端传上来的 key 不能直接信：必须登记且启用、引擎里已部署，
            // 且只能选“全部门通用”或该部门自己的流程，否则可以拿任意流程 key
            // 冒充部门沟通启动实例，越权拿到别的流程数据。
            BizSubProcessDef def = subProcessDefService.requireAvailable(subProcessKey);
            if (!def.isGeneral() && !deptId.equals(def.getDeptId())) {
                throw new BusinessException("流程「" + def.getProcessName() + "」不属于部门 "
                        + deptId + "，请重新选择");
            }
            key = def.getProcessKey();
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put(VAR_DEPT_ID, deptId);
        vars.put(VAR_DEPT_NAME, deptName == null ? deptId : deptName);
        vars.put(VAR_COMM_TYPE, COMM_TYPE_DEPT);
        vars.put(VAR_COMM_RECORD_ID, commRecordId);
        vars.put(VAR_COMMUNICATE_ID, commRecordId);
        vars.put(VAR_SESSION_ID, sessionId);
        vars.put(VAR_ROOT_TASK_ID, rootTask.getId());
        vars.put(VAR_SOURCE_TASK_ID, rootTask.getId());
        vars.put(VAR_SOURCE_TASK_DEF_KEY, rootTask.getTaskDefinitionKey());
        vars.put(VAR_CONTENT, content);
        vars.put(VAR_ORIGIN_CONTENT, content);
        vars.put(VAR_SUB_PROCESS_KEY, key);
        vars.put(VAR_INITIATOR, initiator);
        // 待办中心等列表读的是 applicant，两处都要写：
        // 只写 initiator 会出现「流程实例列表有发起人、待办中心这一列却是空」。
        vars.put(VAR_APPLICANT, initiator);
        vars.put(VAR_ROUND, round);
        // 主流程实例ID：既是审计信息，也是主流程结束时级联终止的唯一索引
        vars.put(VAR_PARENT_PROC_INST_ID, rootTask.getProcessInstanceId());

        ProcessInstance pi = startWithInitiator(commRecordId, key, vars, initiator);
        String taskId = currentTaskId(pi.getId());
        if (taskId == null) {
            // 在 Camunda 命令内启动（首轮/再发起都是在命令里建单）时，新任务还没 flush 到库，
            // 走 DB 的任务查询看不到它，CAMUNDA_TASK_ID_ 会落空——后果是待办中心按任务ID
            // 反查不到沟通记录（targetType 为空），部门成员也无法在沟通待办上「再发起沟通」。
            // 这里补一次命令缓存查询，缓存在任务插入时即已注册，不受 flush 时机影响。
            taskId = taskIdFromCommandCache(pi.getId());
        }
        log.info("部门沟通子流程已启动：record={}, dept={}, pid={}, task={}",
                commRecordId, deptId, pi.getId(), taskId);
        return new Started(pi.getId(), taskId);
    }

    /**
     * 以发起人身份启动子流程。
     *
     * <p>{@code ACT_HI_PROCINST.START_USER_ID_}（流程实例列表里的「发起人」）取自 Camunda
     * 的<b>认证上下文</b>，而不是流程变量。只把发起人写进 {@code initiator} 变量的话，
     * 流程变量查得到、发起人列却永远是空的。这里在启动前把认证用户设为发起人，
     * 启动后再恢复原值，两者都不丢。</p>
     *
     * <p>建单是在命令内完成的（首轮 / 追问 / 再发起都如此），此时 {@code setAuthenticatedUserId}
     * 复用当前命令上下文，立即对紧接着的启动生效；命令外调用时则显式清理，避免污染线程池线程。</p>
     */
    private ProcessInstance startWithInitiator(String businessKey, String processKey,
                                               Map<String, Object> vars, String initiator) {
        if (initiator == null || initiator.isEmpty()) {
            return runtimeService.startProcessInstanceByKey(processKey, businessKey, vars);
        }
        CommandContext commandContext = Context.getCommandContext();
        identityService.setAuthenticatedUserId(initiator);
        try {
            return runtimeService.startProcessInstanceByKey(processKey, businessKey, vars);
        } finally {
            // 命令内设置会随命令上下文一起销毁，正是我们要的；
            // 命令外调用才需要清理，否则线程池里的下一个请求会带着别人的身份。
            if (commandContext == null) {
                identityService.clearAuthentication();
            }
        }
    }

    /**
     * 从当前 Camunda 命令缓存里取部门任务ID。
     *
     * <p>命令之外调用时 {@link Context#getCommandContext()} 为 null，直接返回 null，
     * 由调用方回退到常规查询。</p>
     */
    private String taskIdFromCommandCache(String processInstanceId) {
        org.camunda.bpm.engine.impl.interceptor.CommandContext commandContext =
                org.camunda.bpm.engine.impl.context.Context.getCommandContext();
        if (commandContext == null || processInstanceId == null) {
            return null;
        }
        List<org.camunda.bpm.engine.impl.persistence.entity.TaskEntity> tasks =
                commandContext.getTaskManager().findTasksByProcessInstanceId(processInstanceId);
        if (tasks == null) {
            return null;
        }
        for (org.camunda.bpm.engine.impl.persistence.entity.TaskEntity task : tasks) {
            if (TASK_KEY.equals(task.getTaskDefinitionKey())) {
                return task.getId();
            }
        }
        return null;
    }

    /** 子流程当前待处理的任务ID；流程已结束或查不到时返回 null */
    public String currentTaskId(String processInstanceId) {
        if (processInstanceId == null) {
            return null;
        }
        Task task = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(TASK_KEY)
                .singleResult();
        if (task != null) {
            return task.getId();
        }
        // 三节点通用流程/专属流程的当前节点不叫 dept_handle，
        // 这里按实例兜底取任意活动任务（子流程同时只会有一个活动节点）。
        Task any = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        return any == null ? null : any.getId();
    }

    /** 按沟通记录上登记的任务ID 取部门任务；任务已被处理时返回 null */
    public Task findDeptTask(String deptProcInstId, String camundaTaskId) {
        Task byId = camundaTaskId == null ? null
                : taskService.createTaskQuery().taskId(camundaTaskId).singleResult();
        if (byId != null) {
            return byId;
        }
        if (deptProcInstId == null) {
            return null;
        }
        Task deptTask = taskService.createTaskQuery()
                .processInstanceId(deptProcInstId)
                .taskDefinitionKey(TASK_KEY)
                .singleResult();
        if (deptTask != null) {
            return deptTask;
        }
        // 新子流程的节点随流程不同而不同，按实例取任意活动任务
        return taskService.createTaskQuery()
                .processInstanceId(deptProcInstId)
                .singleResult();
    }

    /** 结束整个部门子流程（收回沟通时调用），流程已结束则忽略 */
    public void cancel(String processInstanceId, String reason) {
        if (processInstanceId == null) {
            return;
        }
        try {
            runtimeService.deleteProcessInstance(processInstanceId, reason);
        } catch (RuntimeException e) {
            // 子流程已经走完（部门已反馈）或实例不存在时无需再取消，
            // 收回动作本身已经写了业务状态，不应因此让整笔收回失败
            log.debug("部门沟通子流程取消失败（可能已结束）：{}", processInstanceId, e);
        }
    }

    /**
     * 主流程结束时级联终止它名下的全部部门沟通子流程，避免留下“孤儿流程”。
     *
     * <p>部门沟通是独立启动的流程实例，引擎不会因为父流程结束而自动清理，
     * 因此按启动时写入的 {@link #VAR_PARENT_PROC_INST_ID} 反查并逐个终止。
     * 业务记录同步置为“已收回”，否则待办没了、记录还显示“等待反馈”。</p>
     *
     * @return 被终止的子流程个数
     */
    public int cancelByParentProcess(String parentProcessInstanceId) {
        if (parentProcessInstanceId == null || parentProcessInstanceId.isEmpty()) {
            return 0;
        }
        List<ProcessInstance> children = runtimeService.createProcessInstanceQuery()
                .variableValueEquals(VAR_PARENT_PROC_INST_ID, parentProcessInstanceId)
                .list();
        if (children.isEmpty()) {
            return 0;
        }
        org.camunda.bpm.engine.impl.interceptor.CommandContext commandContext =
                org.camunda.bpm.engine.impl.context.Context.getCommandContext();
        BizCommTaskManager taskMgr = commandContext == null
                ? null : commandContext.getSession(BizCommTaskManager.class);

        int cancelled = 0;
        java.util.Date now = new java.util.Date();
        for (ProcessInstance pi : children) {
            // 同一个父流程下可能还挂着别的业务子流程，这里只处理部门沟通
            if (!isDeptProcessDefinition(pi.getProcessDefinitionId())) {
                continue;
            }
            Object recordId = runtimeService.getVariable(pi.getId(), VAR_COMM_RECORD_ID);
            if (taskMgr != null && recordId != null) {
                BizCommTask record = taskMgr.findById(String.valueOf(recordId));
                if (record != null && BizCommTask.STATUS_PENDING.equals(record.getStatus())) {
                    record.setStatus(BizCommTask.STATUS_WITHDRAWN);
                    record.setWithdrawTime(now);
                    taskMgr.update(record);
                }
            }
            try {
                runtimeService.deleteProcessInstance(pi.getId(), "主流程已结束，级联终止部门沟通");
                cancelled++;
            } catch (RuntimeException e) {
                log.warn("级联终止部门沟通子流程失败：{}", pi.getId(), e);
            }
        }
        log.info("主流程 {} 结束，级联终止部门沟通子流程 {} 个", parentProcessInstanceId, cancelled);
        return cancelled;
    }

    /** 用户是否为该部门成员（部门沟通的反馈权按部门成员判定，不看 targetId 是否为本人） */
    public boolean isDeptMember(String userId, String deptId) {
        if (userId == null || deptId == null || deptId.isEmpty()) {
            return false;
        }
        return identityService.createUserQuery()
                .userId(userId)
                .memberOfGroup(deptId)
                .count() > 0;
    }

    /** 当前用户是否为该部门任务的候选成员（部门成员） */
    public boolean isCandidate(String taskId, String userId) {
        if (taskId == null || userId == null) {
            return false;
        }
        return taskService.createTaskQuery()
                .taskId(taskId)
                .taskCandidateUser(userId)
                .count() > 0;
    }

    /** 认领部门任务：已由他人认领时返回 false */
    public boolean claim(String taskId, String userId) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            return false;
        }
        if (task.getAssignee() == null) {
            taskService.claim(taskId, userId);
            return true;
        }
        return task.getAssignee().equals(userId);
    }

    /** 任务是否属于部门沟通子流程（按节点 key 判定，够用且不需要额外查流程定义） */
    public static boolean isDeptCommTaskKey(String taskDefinitionKey) {
        return TASK_KEY.equals(taskDefinitionKey);
    }

    /** 流程实例是否属于部门沟通子流程 */
    public boolean isDeptProcessInstance(String processInstanceId) {
        if (processInstanceId == null) {
            return false;
        }
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (pi == null) {
            return false;
        }
        return isDeptProcessDefinition(pi.getProcessDefinitionId());
    }

    public boolean isDeptProcessDefinition(String processDefinitionId) {
        if (processDefinitionId == null) {
            return false;
        }
        ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId).singleResult();
        if (pd == null) {
            return false;
        }
        // 旧版单节点流程 + 注册表登记的全部部门子流程都算
        return PROCESS_KEY.equals(pd.getKey())
                || subProcessDefService.isDeptSubProcessKey(pd.getKey());
    }

    /** 任务是否属于任一部门沟通子流程（待办中心识别用） */
    public boolean isDeptSubProcessTask(Task task) {
        return task != null && isDeptProcessDefinition(task.getProcessDefinitionId());
    }

    /** 部门必须存在且至少有一名成员，否则候选组永远没人能认领 */
    private void requireDept(String deptId) {
        if (deptId == null || deptId.trim().isEmpty()) {
            throw new BusinessException("部门标识不能为空");
        }
        Group group = identityService.createGroupQuery().groupId(deptId).singleResult();
        if (group == null) {
            throw new BusinessException("部门不存在：" + deptId);
        }
        List<org.camunda.bpm.engine.identity.User> members = identityService.createUserQuery()
                .memberOfGroup(deptId).list();
        if (members.isEmpty()) {
            throw new BusinessException("部门 " + (group.getName() == null ? deptId : group.getName())
                    + " 暂无成员，无法承接沟通");
        }
    }
}
