package com.example.camundademo.service;

import com.example.camundademo.entity.BizSubProcessDef;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.identity.User;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部门沟通子流程的办理能力：任务详情、文书分发、经办人提交、汇总提交。
 *
 * <p>前端没有渲染 Camunda 生成表单，而是用自研待办抽屉；因此这些节点的
 * 表单语义（可选人、必填校验、三种汇总操作）都在这里统一实现，
 * 完成动作仍然走引擎的 {@code complete}，监听器负责落变量与回写。</p>
 */
@Service
public class DeptSubTaskService {

    private static final Logger log = LoggerFactory.getLogger(DeptSubTaskService.class);

    public static final String NODE_DISPATCH = "DISPATCH";
    public static final String NODE_HANDLER = "HANDLER";
    public static final String NODE_SUMMARY = "SUMMARY";
    public static final String NODE_OPINION = "NODE";

    private final TaskService taskService;
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final SubProcessDefService subProcessDefService;
    private final DeptCommProcessService deptCommProcessService;
    private final IdentityService identityService;

    public DeptSubTaskService(TaskService taskService, RuntimeService runtimeService,
                              RepositoryService repositoryService,
                              SubProcessDefService subProcessDefService,
                              DeptCommProcessService deptCommProcessService,
                              IdentityService identityService) {
        this.taskService = taskService;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.subProcessDefService = subProcessDefService;
        this.deptCommProcessService = deptCommProcessService;
        this.identityService = identityService;
    }

    /** 任务所属的部门子流程节点类型 */
    public String nodeType(Task task) {
        if (task == null) {
            return null;
        }
        String key = task.getTaskDefinitionKey();
        if (SubProcessDefService.GENERAL_DISPATCH_KEY.equals(key)) {
            return NODE_DISPATCH;
        }
        if (SubProcessDefService.GENERAL_HANDLER_KEY.equals(key)) {
            return NODE_HANDLER;
        }
        if (key != null && (key.equals(SubProcessDefService.GENERAL_SUMMARY_KEY)
                || key.endsWith("Summary"))) {
            return NODE_SUMMARY;
        }
        return NODE_OPINION;
    }

    /**
     * 待办详情：前端据 nodeType 渲染不同面板。
     *
     * <p>只有该任务的办理人/候选组才能看到详情，避免部门外人员浏览沟通内容。</p>
     */
    public Map<String, Object> detail(String taskId, String userId) {
        Task task = requireTask(taskId);
        if (!isDeptSubProcessTask(task)) {
            throw new BusinessException("该待办不属于部门沟通子流程");
        }
        if (!canOperate(task, userId)) {
            throw new BusinessException("你不是该部门待办的处理人，无法查看");
        }

        Map<String, Object> vars = runtimeService.getVariables(task.getProcessInstanceId());
        String deptId = asString(vars.get(DeptCommProcessService.VAR_DEPT_ID));
        String nodeType = nodeType(task);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", task.getId());
        m.put("taskName", task.getName());
        m.put("taskDefKey", task.getTaskDefinitionKey());
        m.put("processInstanceId", task.getProcessInstanceId());
        m.put("processDefinitionKey", definitionKey(task.getProcessDefinitionId()));
        m.put("nodeType", nodeType);
        m.put("deptId", deptId);
        m.put("deptName", vars.get(DeptCommProcessService.VAR_DEPT_NAME));
        m.put("clerkUserId", vars.get("clerkUserId"));
        m.put("commRecordId", vars.get(DeptCommProcessService.VAR_COMM_RECORD_ID));
        m.put("content", vars.get(DeptCommProcessService.VAR_CONTENT));
        m.put("opinionRule", vars.get("opinionRule"));
        m.put("handlerUserIds", vars.get("handlerUserIds"));
        m.put("finalOpinion", vars.get("finalOpinion"));
        m.put("opinionMap", vars.get("opinionMap"));
        m.put("assignee", task.getAssignee());
        m.put("canOperate", true);

        if (NODE_DISPATCH.equals(nodeType)) {
            m.put("deptMembers", deptMembers(deptId));
        }
        if (NODE_SUMMARY.equals(nodeType)) {
            // 汇总面板需要展示经办意见；经办人姓名一并带上，避免前端再查一次
            m.put("handlerNames", userNames(asStringList(vars.get("handlerUserIds"))));
        }
        return m;
    }

    /** 文书分发：选本部门经办人 + 意见规则 */
    public Map<String, Object> dispatch(String taskId, String userId, List<String> handlerUserIds,
                                        String opinionRule, String remark) {
        Task task = requireTask(taskId);
        requireNode(task, NODE_DISPATCH);
        claimIfNeeded(task, userId);
        List<String> ids = new ArrayList<>();
        if (handlerUserIds != null) {
            for (String id : handlerUserIds) {
                if (id != null && !id.isBlank()) {
                    ids.add(id.trim());
                }
            }
        }
        if (ids.isEmpty()) {
            throw new BusinessException("请至少选择一名经办人");
        }
        String deptId = (String) runtimeService.getVariable(task.getProcessInstanceId(),
                DeptCommProcessService.VAR_DEPT_ID);
        for (String id : ids) {
            if (!subProcessDefService.isDeptMember(id, deptId)) {
                throw new BusinessException("经办人 " + id + " 不属于本部门，不能分发");
            }
        }
        taskService.setVariable(taskId, "handlerUserIds", ids);
        taskService.setVariable(taskId, "opinionRule",
                "ANY".equalsIgnoreCase(opinionRule) ? "ANY" : "ALL");
        taskService.setVariable(taskId, "remark", remark == null ? "" : remark);
        complete(taskId);
        return ok("已分发，经办人待办已生成");
    }

    /** 通用流程：经办人提交意见（可退回文书重新分发） */
    public Map<String, Object> submitOpinion(String taskId, String userId, String opinion,
                                             String attachments, boolean returnToClerk) {
        Task task = requireTask(taskId);
        requireNode(task, NODE_HANDLER);
        claimIfNeeded(task, userId);
        if (!returnToClerk && (opinion == null || opinion.isBlank())) {
            throw new BusinessException("请填写意见内容");
        }
        taskService.setVariable(taskId, "opinionContent", opinion == null ? "" : opinion);
        taskService.setVariable(taskId, "attachments", attachments == null ? "" : attachments);
        taskService.setVariable(taskId, "returnToClerk", returnToClerk);
        complete(taskId);
        return ok(returnToClerk ? "已退回文书重新分发" : "意见已提交");
    }

    /** 专属流程：中间审批节点填写本节点意见 */
    public Map<String, Object> submitNodeOpinion(String taskId, String userId, String opinion) {
        Task task = requireTask(taskId);
        requireNode(task, NODE_OPINION);
        claimIfNeeded(task, userId);
        if (opinion == null || opinion.isBlank()) {
            throw new BusinessException("请填写本节点意见");
        }
        taskService.setVariable(taskId, "nodeOpinion", opinion);
        complete(taskId);
        return ok("意见已提交");
    }

    /**
     * 汇总节点：提交 / 退回补充 / 重新分发。
     *
     * <p>三种操作都走同一个完成动作，由流程变量 actionType 决定网关走向，
     * 这样 BPMN 与业务代码不会各自实现一套分支。</p>
     */
    public Map<String, Object> submitSummary(String taskId, String userId, String finalOpinion,
                                             String actionType, List<String> returnHandlerIds) {
        Task task = requireTask(taskId);
        requireNode(task, NODE_SUMMARY);
        claimIfNeeded(task, userId);

        String action = (actionType == null || actionType.isBlank()) ? "SUBMIT" : actionType;
        if (!"SUBMIT".equals(action) && !"RETURN_SUPPLEMENT".equals(action)
                && !"REDISPATCH".equals(action)) {
            throw new BusinessException("不支持的汇总操作：" + action);
        }
        if ("SUBMIT".equals(action) && (finalOpinion == null || finalOpinion.isBlank())) {
            throw new BusinessException("请填写部门最终汇总意见");
        }
        if ("RETURN_SUPPLEMENT".equals(action)
                && (returnHandlerIds == null || returnHandlerIds.isEmpty())) {
            throw new BusinessException("请选择需要补充意见的经办人");
        }
        if (finalOpinion != null && !finalOpinion.isBlank()) {
            taskService.setVariable(taskId, "finalOpinion", finalOpinion);
        }
        taskService.setVariable(taskId, "actionType", action);
        if (returnHandlerIds != null && !returnHandlerIds.isEmpty()) {
            taskService.setVariable(taskId, "returnHandlerIds", returnHandlerIds);
        }
        complete(taskId);

        switch (action) {
            case "RETURN_SUPPLEMENT":
                return ok("已退回指定经办人补充意见");
            case "REDISPATCH":
                return ok("已回到文书分发节点");
            default:
                return ok("部门意见已提交，主流程待办已解锁");
        }
    }

    // ===== 内部方法 =====

    private boolean isDeptSubProcessTask(Task task) {
        return deptCommProcessService.isDeptProcessDefinition(task.getProcessDefinitionId());
    }

    private void requireNode(Task task, String expected) {
        if (!isDeptSubProcessTask(task)) {
            throw new BusinessException("该待办不属于部门沟通子流程");
        }
        String actual = nodeType(task);
        if (!expected.equals(actual)) {
            throw new BusinessException("当前节点不支持该操作（节点类型：" + actual + "）");
        }
    }

    private Task requireTask(String taskId) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            throw new BusinessException("任务不存在或已处理");
        }
        return task;
    }

    /** 候选组成员可办理；已被他人认领时明确拒绝，不静默抢占 */
    private void claimIfNeeded(Task task, String userId) {
        if (task.getAssignee() == null) {
            if (!taskService.createTaskQuery().taskId(task.getId()).taskCandidateUser(userId).list().isEmpty()) {
                taskService.claim(task.getId(), userId);
                return;
            }
            throw new BusinessException("你不是该部门待办的候选人");
        }
        if (!userId.equals(task.getAssignee())) {
            throw new BusinessException("该待办已由 " + task.getAssignee() + " 认领");
        }
    }

    private boolean canOperate(Task task, String userId) {
        if (task.getAssignee() != null) {
            return task.getAssignee().equals(userId);
        }
        return !taskService.createTaskQuery().taskId(task.getId())
                .taskCandidateUser(userId).list().isEmpty();
    }

    private void complete(String taskId) {
        taskService.complete(taskId);
        log.info("部门子流程任务已完成：{}", taskId);
    }

    private String definitionKey(String processDefinitionId) {
        ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId).singleResult();
        return pd == null ? null : pd.getKey();
    }

    private List<Map<String, Object>> deptMembers(String deptId) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (deptId == null) {
            return list;
        }
        for (String id : subProcessDefService.deptMembers(deptId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", displayName(id));
            list.add(m);
        }
        return list;
    }

    private Map<String, String> userNames(List<String> ids) {
        Map<String, String> names = new LinkedHashMap<>();
        for (String id : ids) {
            names.put(id, displayName(id));
        }
        return names;
    }

    /** 演示环境的用户显示名在 firstName，取不到时退回账号 */
    private String displayName(String userId) {
        User user = identityService.createUserQuery().userId(userId).singleResult();
        if (user != null && user.getFirstName() != null && !user.getFirstName().isBlank()) {
            return user.getFirstName();
        }
        return userId;
    }

    private List<String> asStringList(Object value) {
        List<String> list = new ArrayList<>();
        if (value instanceof Collection<?> c) {
            for (Object o : c) {
                if (o != null) {
                    list.add(String.valueOf(o));
                }
            }
        }
        return list;
    }

    private String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private Map<String, Object> ok(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("result", "ok");
        m.put("message", message);
        m.put("time", new Date());
        return m;
    }
}
