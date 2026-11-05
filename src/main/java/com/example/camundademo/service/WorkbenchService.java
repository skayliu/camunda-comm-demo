package com.example.camundademo.service;

import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.entity.BizTaskExt;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.history.HistoricTaskInstance;
import org.camunda.bpm.engine.identity.User;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Comment;
import org.camunda.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 待办中心只读查询服务。
 *
 * <p>把“我的待办 / 已办 / 我的发起”三张列表的组装逻辑收在一处，
 * Controller 只做鉴权与参数校验。所有“我是谁”一律由调用方从认证上下文取得后传入。</p>
 */
@Service
public class WorkbenchService {

    /** 单页上限，避免前端传入过大 size 把引擎查爆 */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * Camunda 正常完成任务时，会把历史任务的 DELETE_REASON_ 写成 "completed"
     * （见 TaskEntity.DELETE_REASON_COMPLETED），而不是留空。
     * 对外必须归一化成“无删除原因”，否则“已完成”会被调用方当成“已收回/删除”。
     */
    private static final String CAMUNDA_DELETE_REASON_COMPLETED = "completed";

    @Autowired
    private TaskService taskService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private CommunicationService communicationService;

    @Autowired
    private TaskTransferService taskTransferService;

    // ===== 当前用户 =====

    public Map<String, Object> currentUserInfo(String userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", userId);
        User user = identityService.createUserQuery().userId(userId).singleResult();
        String displayName = userId;
        if (user != null) {
            String first = user.getFirstName();
            String last = user.getLastName();
            StringBuilder sb = new StringBuilder();
            if (first != null && !first.isBlank()) {
                sb.append(first);
            }
            if (last != null && !last.isBlank() && !"(demo)".equals(last)) {
                sb.append(sb.length() > 0 ? " " : "").append(last);
            }
            if (sb.length() > 0) {
                displayName = sb.toString();
            }
        }
        m.put("displayName", displayName);
        return m;
    }

    // ===== 我的待办 =====

    /**
     * @param type all / approval / comm / followup
     *             comm 为全部沟通（含追问），followup 仅追问任务
     */
    public Map<String, Object> listTodo(String userId, String keyword, String type, int page, int size) {
        List<Task> tasks = taskService.createTaskQuery()
                .taskAssignee(userId)
                .orderByTaskCreateTime().desc()
                .list();

        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        int approvalCount = 0;
        int commCount = 0;
        int followUpCount = 0;
        int todayCount = 0;
        long todayStart = java.time.LocalDate.now()
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Task t : tasks) {
            boolean isComm = BizCommTask.isCommTaskKey(t.getTaskDefinitionKey());
            // 每个任务只解析一次沟通记录：既用于统计/筛选，也复用到 VO 组装
            BizCommTask record = isComm ? communicationService.getCommRecordByCamundaTaskId(t.getId()) : null;
            boolean isFollowUp = record != null && record.getParentCommTaskId() != null;
            if (isComm) {
                commCount++;
            } else {
                approvalCount++;
            }
            if (isFollowUp) {
                followUpCount++;
            }
            if (t.getCreateTime() != null && t.getCreateTime().getTime() >= todayStart) {
                todayCount++;
            }
            if ("approval".equalsIgnoreCase(type) && isComm) {
                continue;
            }
            if ("comm".equalsIgnoreCase(type) && !isComm) {
                continue;
            }
            if ("followup".equalsIgnoreCase(type) && !isFollowUp) {
                continue;
            }
            Map<String, Object> vo = toTodoVO(t, isComm, record);
            if (!kw.isEmpty() && !matches(vo, kw)) {
                continue;
            }
            filtered.add(vo);
        }
        // 转办信息整页一次批量取，避免每行单独查库；同时把“这条待办是转来的”标在列表上，
        // 否则新办理人只知道有活干，不知道是谁转给他的。
        Map<String, List<Map<String, Object>>> transfers = taskTransferService.groupByTaskIds(
                filtered.stream().map(vo -> String.valueOf(vo.get("taskId"))).toList());
        for (Map<String, Object> vo : filtered) {
            List<Map<String, Object>> chain = transfers.get(String.valueOf(vo.get("taskId")));
            if (chain == null || chain.isEmpty()) {
                continue;
            }
            vo.put("transferCount", chain.size());
            vo.put("lastTransfer", chain.get(chain.size() - 1));
        }
        Map<String, Object> result = pageResult(filtered, page, size);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", tasks.size());
        stats.put("approval", approvalCount);
        stats.put("comm", commCount);
        stats.put("followup", followUpCount);
        stats.put("today", todayCount);
        result.put("stats", stats);
        return result;
    }

    /** 单条待办详情：基础信息 + 流程变量 + 沟通状态 + 审批意见 */
    public Map<String, Object> taskDetail(String taskId, String userId) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            throw new BusinessException("任务不存在或已被处理");
        }
        boolean isComm = BizCommTask.isCommTaskKey(task.getTaskDefinitionKey());
        Map<String, Object> vo = toTodoVO(task, isComm);
        vo.put("variables", safeVariables(task.getProcessInstanceId()));
        Map<String, Object> commStatus = communicationService.getTaskCommStatus(
                isComm ? resolveRootTaskId(task) : taskId, userId);
        vo.put("commStatus", commStatus);
        if (isComm) {
            vo.put("commStatusOfSelf", communicationService.getTaskCommStatus(taskId, userId));
        }
        vo.put("comments", commentsOf(taskId));
        // 转办链与可转办标记：沟通中/沟通待办不可转办，后端与前端共用同一口径
        vo.put("transfers", taskTransferService.listByTaskId(taskId));
        // 必须再带上「当前是否为办理人」：未签收的候选任务 assignee 为空，
        // 任务详情对任何登录用户开放，只判沟通状态会给出一个点了必然失败的转办按钮。
        boolean currentAssignee = task.getAssignee() != null && task.getAssignee().equals(userId);
        vo.put("canTransfer", !isComm
                && currentAssignee
                && !BizTaskExt.STATUS_COMMUNICATING.equals(commStatus.get("bizStatus")));

        // 沟通待办：把同一会话内其他对象的反馈进度一并返回，前端可直接还原上下文
        if (isComm) {
            BizCommTask self = communicationService.getCommRecordByCamundaTaskId(taskId);
            if (self != null) {
                vo.put("commRecordId", self.getId());
                vo.put("sessionId", self.getSessionId());
                List<BizCommTask> siblings = communicationService.getCommRecordsBySessionId(self.getSessionId());
                // 每条记录分别标注“当前用户能否收回”：沟通对象把问题转给他人后，
                // 下级不反馈时上级会被阻塞，需要能自己收回这段转交（与 withdrawCommunication 同口径）。
                Map<String, Boolean> withdrawable =
                        communicationService.withdrawableRecordMap(self.getSessionId(), userId);
                List<Map<String, Object>> siblingsVO = new ArrayList<>();
                for (BizCommTask s : siblings) {
                    Map<String, Object> sv = new LinkedHashMap<>();
                    sv.put("id", s.getId());
                    // 父链是沟通反馈界面还原“转交/追问”层级的依据，必须一起返回
                    sv.put("parentCommTaskId", s.getParentCommTaskId());
                    sv.put("targetId", s.getTargetId());
                    sv.put("targetName", s.getTargetName());
                    sv.put("status", s.getStatus());
                    sv.put("content", s.getContent());
                    sv.put("replyContent", s.getReplyContent());
                    sv.put("replyTime", s.getReplyTime());
                    sv.put("canWithdraw", Boolean.TRUE.equals(withdrawable.get(s.getId())));
                    siblingsVO.add(sv);
                }
                vo.put("sessionTasks", siblingsVO);
                vo.put("myCommStatus", self.getStatus());
            }
        }
        return vo;
    }

    private String resolveRootTaskId(Task commTask) {
        if (commTask.getParentTaskId() != null) {
            return commTask.getParentTaskId();
        }
        BizCommTask record = communicationService.getCommRecordByCamundaTaskId(commTask.getId());
        return record == null ? commTask.getId() : record.getRootTaskId();
    }

    private Map<String, Object> toTodoVO(Task t, boolean isComm) {
        return toTodoVO(t, isComm, isComm
                ? communicationService.getCommRecordByCamundaTaskId(t.getId())
                : null);
    }

    /**
     * @param record 已加载的沟通业务记录，非沟通任务传 null；由调用方预加载以避免重复查询
     */
    private Map<String, Object> toTodoVO(Task t, boolean isComm, BizCommTask record) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("taskId", t.getId());
        vo.put("taskName", t.getName());
        vo.put("taskDefKey", t.getTaskDefinitionKey());
        vo.put("isCommTask", isComm);
        vo.put("isFollowUp", false);
        vo.put("processInstanceId", t.getProcessInstanceId());
        vo.put("assignee", t.getAssignee());
        vo.put("createTime", t.getCreateTime());
        vo.put("parentTaskId", t.getParentTaskId());
        vo.put("description", t.getDescription());

        ProcessDefinition pd = definition(t.getProcessDefinitionId());
        vo.put("processDefinitionKey", pd == null ? null : pd.getKey());
        vo.put("processDefinitionName", pd == null ? null : pd.getName());

        Map<String, Object> vars = safeVariables(t.getProcessInstanceId());
        vo.put("applicant", vars.get("applicant"));
        vo.put("processTitle", vars.get("title") != null
                ? vars.get("title")
                : (pd == null ? "审批流程" : pd.getName()));

        if (isComm && record != null) {
            vo.put("commRecordId", record.getId());
            vo.put("sessionId", record.getSessionId());
            // 有父沟通记录的是追问任务，没有的是本轮首次沟通
            vo.put("isFollowUp", record.getParentCommTaskId() != null);
            vo.put("sourceTaskDefKey", record.getSourceTaskDefKey() != null
                    ? record.getSourceTaskDefKey()
                    : BizCommTask.parseSourceTaskDefKey(t.getTaskDefinitionKey()));
            vo.put("commInitiator", commInitiator(record.getSessionId()));
            vo.put("commContent", record.getContent());
            vo.put("myCommStatus", record.getStatus());
        }
        return vo;
    }

    private String commInitiator(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        var session = communicationService.getSessionById(sessionId);
        return session == null ? null : session.getInitiator();
    }

    /**
     * 沟通任务是否为“追问”：业务记录存在父沟通记录即为追问，否则为本轮首轮沟通。
     * 目前系统只支持两层（首轮 → 追问），空父ID即首轮。
     */
    private boolean isFollowUpCommTask(String camundaTaskId) {
        BizCommTask record = communicationService.getCommRecordByCamundaTaskId(camundaTaskId);
        return record != null && record.getParentCommTaskId() != null;
    }

    // ===== 我的已办 =====

    public Map<String, Object> listDone(String userId, String keyword, int page, int size) {
        List<HistoricTaskInstance> tasks = historyService.createHistoricTaskInstanceQuery()
                .taskAssignee(userId)
                .finished()
                .orderByHistoricTaskInstanceEndTime().desc()
                .list();

        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<Map<String, Object>> list = new ArrayList<>();
        for (HistoricTaskInstance t : tasks) {
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("taskId", t.getId());
            vo.put("taskName", t.getName());
            vo.put("taskDefKey", t.getTaskDefinitionKey());
            boolean isComm = BizCommTask.isCommTaskKey(t.getTaskDefinitionKey());
            vo.put("isCommTask", isComm);
            vo.put("isFollowUp", isComm && isFollowUpCommTask(t.getId()));
            vo.put("processInstanceId", t.getProcessInstanceId());
            vo.put("processDefinitionId", t.getProcessDefinitionId());
            ProcessDefinition pd = definition(t.getProcessDefinitionId());
            vo.put("processDefinitionKey", pd == null ? null : pd.getKey());
            vo.put("processDefinitionName", pd == null ? null : pd.getName());
            vo.put("assignee", t.getAssignee());
            vo.put("startTime", t.getStartTime());
            vo.put("endTime", t.getEndTime());
            vo.put("durationInMillis", t.getDurationInMillis());
            vo.put("deleteReason", normalizeTaskDeleteReason(t.getDeleteReason()));
            Map<String, Object> vars = safeVariables(t.getProcessInstanceId());
            vo.put("applicant", vars.get("applicant"));
            vo.put("processTitle", vars.get("title") != null
                    ? vars.get("title")
                    : (pd == null ? "审批流程" : pd.getName()));
            List<Map<String, Object>> comments = commentsOf(t.getId());
            vo.put("comments", comments);
            // commentsOf 里统一的字段名是 message（见其实现），此处不能取 fullMessage，
            // 否则即使意见已落库，列表/详情也永远显示为空
            vo.put("comment", comments.isEmpty() ? null : comments.get(0).get("message"));

            if (!kw.isEmpty() && !matches(vo, kw)) {
                continue;
            }
            list.add(vo);
        }
        return pageResult(list, page, size);
    }

    // ===== 我的发起 =====

    public Map<String, Object> listStarted(String userId, String keyword, int page, int size) {
        List<HistoricProcessInstance> instances = historyService.createHistoricProcessInstanceQuery()
                .startedBy(userId)
                .orderByProcessInstanceStartTime().desc()
                .list();

        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<Map<String, Object>> list = new ArrayList<>();
        for (HistoricProcessInstance hpi : instances) {
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("processInstanceId", hpi.getId());
            vo.put("businessKey", hpi.getBusinessKey());
            vo.put("processDefinitionId", hpi.getProcessDefinitionId());
            ProcessDefinition pd = definition(hpi.getProcessDefinitionId());
            vo.put("processDefinitionKey", pd == null ? null : pd.getKey());
            vo.put("processDefinitionName", pd == null ? null : pd.getName());
            vo.put("startUserId", hpi.getStartUserId());
            vo.put("startTime", hpi.getStartTime());
            vo.put("endTime", hpi.getEndTime());
            vo.put("state", hpi.getEndTime() == null ? "RUNNING" : "FINISHED");
            vo.put("deleteReason", hpi.getDeleteReason());

            Map<String, Object> vars = safeVariables(hpi.getId());
            vo.put("applicant", vars.get("applicant"));
            vo.put("processTitle", vars.get("title") != null
                    ? vars.get("title")
                    : (pd == null ? "审批流程" : pd.getName()));

            // 运行中：当前停在哪几个待办节点
            vo.put("currentTasks", currentTasks(hpi.getId()));

            if (!kw.isEmpty() && !matches(vo, kw)) {
                continue;
            }
            list.add(vo);
        }
        return pageResult(list, page, size);
    }

    private List<Map<String, Object>> currentTasks(String processInstanceId) {
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (pi == null) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Task t : taskService.createTaskQuery().processInstanceId(processInstanceId).list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", t.getId());
            m.put("taskName", t.getName());
            m.put("assignee", t.getAssignee());
            boolean isComm = BizCommTask.isCommTaskKey(t.getTaskDefinitionKey());
            m.put("isCommTask", isComm);
            m.put("isFollowUp", isComm && isFollowUpCommTask(t.getId()));
            result.add(m);
        }
        return result;
    }

    // ===== 工具 =====

    private ProcessDefinition definition(String processDefinitionId) {
        if (processDefinitionId == null) {
            return null;
        }
        return repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId).singleResult();
    }

    private Map<String, Object> safeVariables(String processInstanceId) {
        if (processInstanceId == null) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> vars = runtimeService.getVariables(processInstanceId);
            return vars == null ? Collections.emptyMap() : vars;
        } catch (RuntimeException e) {
            // 流程已结束：运行期变量取不到，返回空即可，不阻断列表渲染
            return Collections.emptyMap();
        }
    }

    /**
     * 历史任务的删除原因归一化：引擎用 "completed" 表示“正常办结”，
     * 对外一律按“无删除原因”返回，前端/跟踪图才能把“完成”和“收回/删除”区分开。
     */
    private String normalizeTaskDeleteReason(String deleteReason) {
        return CAMUNDA_DELETE_REASON_COMPLETED.equals(deleteReason) ? null : deleteReason;
    }

    private List<Map<String, Object>> commentsOf(String taskId) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<Comment> comments = taskService.getTaskComments(taskId);
            for (Comment c : comments) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("userId", c.getUserId());
                m.put("time", c.getTime());
                m.put("message", c.getFullMessage());
                result.add(m);
            }
        } catch (RuntimeException e) {
            // 历史任务已归档或注释表缺失时忽略，不影响主信息
        }
        return result;
    }

    private boolean matches(Map<String, Object> vo, String keyword) {
        for (String field : new String[]{"taskName", "processTitle", "applicant",
                "processDefinitionName", "taskDefKey", "processInstanceId"}) {
            Object v = vo.get(field);
            if (v != null && String.valueOf(v).toLowerCase().contains(keyword)) {
                return true;
            }
        }
        return false;
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
        result.put("serverTime", new Date());
        return result;
    }
}
