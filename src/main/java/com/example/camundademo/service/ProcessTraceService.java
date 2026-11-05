package com.example.camundademo.service;

import com.example.camundademo.entity.BizCommTask;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.history.HistoricTaskInstance;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.task.Comment;
import org.camunda.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 流程图跟踪服务：把 Camunda 的 BPMN DI（节点坐标、连线路径）与历史活动实例
 * 合成一份前端可直接绘制的结构，节点带执行状态与执行明细。
 *
 * <p>不依赖 bpmn-js：DI 几何来自部署时保存的 BPMN XML，状态来自
 * {@link HistoricActivityInstance}，前端用 SVG 还原并高亮。</p>
 */
@Service
public class ProcessTraceService {

    private static final Logger LOG = Logger.getLogger(ProcessTraceService.class.getName());

    /** BPMN 2.0 模型命名空间 */
    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /** 只渲染有执行语义的节点类型，注释/数据对象等不参与流程图跟踪 */
    private static final Set<String> RENDERABLE_TYPES = Set.of(
            "startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent",
            "boundaryEvent", "userTask", "serviceTask", "scriptTask", "manualTask",
            "businessRuleTask", "sendTask", "receiveTask", "task", "callActivity",
            "subProcess", "transaction", "adHocSubProcess",
            "exclusiveGateway", "parallelGateway", "inclusiveGateway",
            "eventBasedGateway");

    /**
     * 兜底判定：Camunda 在 ACT_TYPE_ 里写的是具体事件类型（noneEndEvent、timerStartEvent…），
     * 不是 BPMN 元素名（endEvent、startEvent）。所以除通用名外还要按后缀匹配，
     * 否则普通结束事件永远匹配不上。
     *
     * <p>正常路径以 BPMN 图形的 shape id 为准，本方法只在取不到 BPMN XML 时兜底。</p>
     */
    private static boolean isRenderableType(String type) {
        if (type == null) {
            return false;
        }
        if (RENDERABLE_TYPES.contains(type)) {
            return true;
        }
        return type.endsWith("EndEvent") || type.endsWith("StartEvent")
                || type.startsWith("boundary")
                || type.endsWith("Catch") || type.endsWith("Throw");
    }

    private static final Map<String, String> TYPE_LABELS = Map.ofEntries(
            Map.entry("startEvent", "开始事件"),
            Map.entry("endEvent", "结束事件"),
            Map.entry("intermediateCatchEvent", "中间捕获事件"),
            Map.entry("intermediateThrowEvent", "中间抛出事件"),
            Map.entry("boundaryEvent", "边界事件"),
            Map.entry("userTask", "用户任务"),
            Map.entry("serviceTask", "服务任务"),
            Map.entry("scriptTask", "脚本任务"),
            Map.entry("manualTask", "人工任务"),
            Map.entry("businessRuleTask", "规则任务"),
            Map.entry("sendTask", "发送任务"),
            Map.entry("receiveTask", "接收任务"),
            Map.entry("task", "任务"),
            Map.entry("callActivity", "调用活动"),
            Map.entry("subProcess", "子流程"),
            Map.entry("transaction", "事务子流程"),
            Map.entry("adHocSubProcess", "即席子流程"),
            Map.entry("exclusiveGateway", "排他网关"),
            Map.entry("parallelGateway", "并行网关"),
            Map.entry("inclusiveGateway", "包容网关"),
            Map.entry("eventBasedGateway", "事件网关"));

    /**
     * 事件定义类型 → 中文前缀。BPMN 里「定时开始事件」的元素名仍是 startEvent，
     * 真正的类型信息在子元素 {@code <timerEventDefinition/>} 上，必须单独解析，
     * 否则跟踪图只能画出通用空圈，看不出这是定时、消息还是信号触发。
     */
    private static final Map<String, String> EVENT_DEF_LABELS = Map.ofEntries(
            Map.entry("timer", "定时"),
            Map.entry("message", "消息"),
            Map.entry("signal", "信号"),
            Map.entry("conditional", "条件"),
            Map.entry("error", "错误"),
            Map.entry("escalation", "升级"),
            Map.entry("terminate", "终止"),
            Map.entry("compensate", "补偿"),
            Map.entry("link", "链接"),
            Map.entry("cancel", "取消"),
            Map.entry("multiple", "多重"),
            Map.entry("parallelMultiple", "并行多重"));

    /**
     * 多实例类型 → 中文前缀。是否为多实例只能看 {@code <multiInstanceLoopCharacteristics/>}：
     * 元素名仍是 userTask / subProcess，{@code isSequential="true"} 为顺序多实例，
     * 缺省为并行多实例。历史表里多实例体是 {@code userTask1#multiInstanceBody}，
     * 而各实例仍是原 activityId，同样无法用于判定。
     */
    private static final Map<String, String> MULTI_INSTANCE_LABELS = Map.of(
            "parallel", "并行多实例",
            "sequential", "顺序多实例");

    private static final String EVENT_DEF_SUFFIX = "EventDefinition";

    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PENDING = "PENDING";

    @Autowired
    private HistoryService historyService;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private CommunicationService communicationService;

    @Autowired
    private TaskTransferService taskTransferService;

    /** 子流程调用链的最大回溯深度，防止异常数据形成环导致无限递归 */
    private static final int MAX_CALL_DEPTH = 8;

    /**
     * 当前用户能否查看该流程实例的跟踪图。
     *
     * <p>可见范围：流程发起人 + 在该实例上办理过任务的人（含沟通任务的反馈人），
     * 以及当前待办的处理人。这样“我的待办 / 已办 / 我的发起”列表里的记录都能打开跟踪。</p>
     *
     * <p>被调用子流程还要沿调用链向上找：子流程实例的发起人可能为空或不是当前用户，
     * 但对父流程有权限的人必须能下钻查看它，否则点击调用活动上的 + 会直接 403。</p>
     */
    public boolean canView(String processInstanceId, String userId) {
        return canView(processInstanceId, userId, 0);
    }

    private boolean canView(String processInstanceId, String userId, int depth) {
        if (processInstanceId == null || userId == null) {
            return false;
        }
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (hpi == null) {
            return false;
        }
        if (userId.equals(hpi.getStartUserId())) {
            return true;
        }
        long handled = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .taskAssignee(userId)
                .count();
        if (handled > 0) {
            return true;
        }
        // 转办会把历史任务的办理人改写成接收人，转出方在上面两个查询里都查不到自己。
        // 不补这一条，他把待办转出去之后就再也打不开这张跟踪图。
        if (taskTransferService.participatedInTransfer(processInstanceId, userId)) {
            return true;
        }
        if (taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskAssignee(userId)
                .count() > 0) {
            return true;
        }
        if (depth >= MAX_CALL_DEPTH) {
            return false;
        }
        // 该实例若由 callActivity 调起，则继承父实例的可见权限
        for (String parentId : parentProcessInstanceIds(processInstanceId)) {
            if (canView(parentId, userId, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 找出直接调起该实例的父流程实例。
     *
     * <p>直接读子实例的 {@code getSuperProcessInstanceId()}：引擎在 callActivity 调起子流程时
     * 写入该字段。注意 {@code HistoricProcessInstanceQuery.superProcessInstanceId(x)} 的语义是
     * “父实例为 x 的子实例”，方向相反，不能拿来反查父级。</p>
     */
    private List<String> parentProcessInstanceIds(String processInstanceId) {
        if (processInstanceId == null) {
            return Collections.emptyList();
        }
        try {
            HistoricProcessInstance child = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(processInstanceId).singleResult();
            String parentId = child == null ? null : child.getSuperProcessInstanceId();
            return parentId == null ? Collections.emptyList() : List.of(parentId);
        } catch (RuntimeException ignore) {
            // 历史表结构异常时不阻断跟踪图，仅退化为无父级权限继承
            return Collections.emptyList();
        }
    }

    /** 组装流程图跟踪数据 */
    public Map<String, Object> trace(String processInstanceId) {
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (hpi == null) {
            throw new BusinessException("流程实例不存在：" + processInstanceId);
        }

        List<HistoricActivityInstance> activities = historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricActivityInstanceStartTime().asc()
                .list();

        // 图上的节点集合，是「一条历史活动该不该算到某个节点上」的唯一判据。
        // 不能用 ACT_TYPE_ 白名单来判：Camunda 在 ACT_HI_ACTINST 里记录的是具体事件类型，
        // 例如普通结束事件是 noneEndEvent、终止结束事件是 terminateEndEvent，
        // 而 BPMN 元素名统一是 endEvent。按元素名过滤会把已结束流程的结束节点整条丢掉，
        // 节点因为拿不到实例被判成“未开始”，流程图与明细就会同时显示流程还没走完。
        Diagram diagram = parseDiagram(hpi.getProcessDefinitionId());
        Set<String> shapeIds = new HashSet<>();
        for (NodeShape shape : diagram.shapes) {
            if (shape.id != null) {
                shapeIds.add(shape.id);
            }
        }

        // 流程节点的执行实例，按节点 key 聚合
        Map<String, List<HistoricActivityInstance>> byNode = new LinkedHashMap<>();
        for (HistoricActivityInstance a : activities) {
            if (a.getActivityId() == null) {
                continue;
            }
            if (!shapeIds.isEmpty()) {
                // 只保留图上真实存在的节点；multiInstanceBody 之类派生执行的 activityId
                // 形如 "userTask1#multiInstanceBody"，不在 shapeIds 中，会被自然排除。
                if (!shapeIds.contains(a.getActivityId())) {
                    continue;
                }
            } else if (!isRenderableType(a.getActivityType())) {
                // BPMN XML 取不到时退回按类型兜底，避免整张图全部灰掉
                continue;
            }
            byNode.computeIfAbsent(a.getActivityId(), k -> new ArrayList<>()).add(a);
        }

        // 沟通任务是 standalone 任务（无 executionId），不产生历史活动记录，
        // 只写 ACT_HI_TASKINST，因此必须从历史任务表取，再按来源节点归集。
        Map<String, List<Map<String, Object>>> commByNode = new LinkedHashMap<>();
        List<HistoricTaskInstance> historicTasks = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .list();
        // 沟通的发起人是「源待办的处理人」，需要按 sourceTaskId 回查历史任务
        Map<String, HistoricTaskInstance> taskById = new LinkedHashMap<>();
        for (HistoricTaskInstance t : historicTasks) {
            taskById.put(t.getId(), t);
        }
        // 源待办的节点 key / 处理人：历史任务已归档或运行时任务被删时，从历史活动实例兜底
        Map<String, String> sourceDefKeyByTaskId = new LinkedHashMap<>();
        Map<String, String> sourceAssigneeByTaskId = new LinkedHashMap<>();
        for (HistoricTaskInstance t : historicTasks) {
            if (t.getId() == null) {
                continue;
            }
            sourceDefKeyByTaskId.put(t.getId(), t.getTaskDefinitionKey());
            if (t.getAssignee() != null && !t.getAssignee().isBlank()) {
                sourceAssigneeByTaskId.put(t.getId(), t.getAssignee());
            }
        }
        for (HistoricActivityInstance a : activities) {
            if (a.getTaskId() == null) {
                continue;
            }
            sourceDefKeyByTaskId.putIfAbsent(a.getTaskId(), a.getActivityId());
            if (a.getAssignee() != null && !a.getAssignee().isBlank()) {
                sourceAssigneeByTaskId.putIfAbsent(a.getTaskId(), a.getAssignee());
            }
        }
        for (HistoricTaskInstance t : historicTasks) {
            if (!BizCommTask.isCommTaskKey(t.getTaskDefinitionKey())) {
                continue;
            }
            BizCommTask record = null;
            try {
                record = communicationService.getCommRecordByCamundaTaskId(t.getId());
            } catch (RuntimeException ignore) {
                // 历史数据缺少业务记录时仍可按引擎任务展示
            }
            String sourceKey = record != null && record.getSourceTaskDefKey() != null
                    ? record.getSourceTaskDefKey()
                    : BizCommTask.parseSourceTaskDefKey(t.getTaskDefinitionKey());
            if (sourceKey == null) {
                continue;
            }
            String initiator = null;
            if (record != null && record.getSourceTaskId() != null) {
                HistoricTaskInstance sourceTask = taskById.get(record.getSourceTaskId());
                if (sourceTask != null) {
                    initiator = sourceTask.getAssignee();
                }
            }
            commByNode.computeIfAbsent(sourceKey, k -> new ArrayList<>())
                    .add(toCommTaskVO(t, record, initiator));
        }

        // 已收回的沟通必须从业务表补：收回走 taskService.deleteTask(...)，
        // Camunda 会把对应的 ACT_HI_TASKINST 历史任务一并删除，历史任务查询根本拿不到这些记录。
        // 这里按「该实例上出现过的任务 ID」反查 BIZ_COMM_TASK_，把历史里缺失的记录还原出来。
        // 兜底查询失败（如老库缺 BIZ_COMM_TASK_ 表）时不能拖垮整张跟踪图，只损失已收回记录。
        List<BizCommTask> withdrawnRecords = Collections.emptyList();
        try {
            withdrawnRecords = communicationService
                    .getCommRecordsBySourceTaskIds(sourceDefKeyByTaskId.keySet());
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "跟踪图补全已收回沟通失败，processInstanceId=" + processInstanceId, ex);
        }
        for (BizCommTask record : withdrawnRecords) {
            if (!BizCommTask.STATUS_WITHDRAWN.equals(record.getStatus())) {
                continue;
            }
            // 历史任务还在的已由上面的循环展示（如收回后又重新发起），避免同一条记录出现两次
            if (record.getCamundaTaskId() != null && taskById.containsKey(record.getCamundaTaskId())) {
                continue;
            }
            String sourceKey = record.getSourceTaskDefKey() != null
                    ? record.getSourceTaskDefKey()
                    : sourceDefKeyByTaskId.get(record.getSourceTaskId());
            if (sourceKey == null) {
                continue;
            }
            String initiator = record.getSourceTaskId() == null
                    ? null : sourceAssigneeByTaskId.get(record.getSourceTaskId());
            commByNode.computeIfAbsent(sourceKey, k -> new ArrayList<>())
                    .add(toWithdrawnCommVO(record, initiator, sourceKey));
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<String> activeNodeIds = new ArrayList<>();
        List<String> completedNodeIds = new ArrayList<>();
        String firstName = null;
        int completedCount = 0;
        int activeCount = 0;
        int commTotal = 0;
        int commPending = 0;

        for (NodeShape shape : diagram.shapes) {
            List<HistoricActivityInstance> instances = byNode.getOrDefault(shape.id, Collections.emptyList());
            List<Map<String, Object>> commTasks = commByNode.getOrDefault(shape.id, Collections.emptyList());

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("nodeId", shape.id);
            node.put("name", resolveName(shape.name, instances, shape.id));
            node.put("type", shape.type);
            node.put("typeLabel", typeLabel(shape));
            // 事件定义（timer / message / signal …），前端据此绘制 BPMN 标准图标
            node.put("eventDefinition", shape.eventDefinition);
            // 边界事件是否中断（cancelActivity=false 为非中断，画虚线双圈）
            node.put("interrupting", shape.interrupting);
            // 多实例类型：parallel（三条竖线）/ sequential（三条横线），非多实例为 null
            node.put("multiInstance", shape.multiInstance);
            node.put("x", shape.x);
            node.put("y", shape.y);
            node.put("width", shape.width);
            node.put("height", shape.height);

            String status = resolveStatus(instances);
            node.put("status", status);
            if (STATUS_ACTIVE.equals(status)) {
                activeNodeIds.add(shape.id);
                activeCount++;
                if (firstName == null) {
                    firstName = (String) node.get("name");
                }
            } else if (STATUS_COMPLETED.equals(status)) {
                completedNodeIds.add(shape.id);
                completedCount++;
            }

            List<Map<String, Object>> executions = new ArrayList<>();
            Set<String> assignees = new LinkedHashSet<>();
            List<Map<String, Object>> comments = new ArrayList<>();
            Date firstStart = null;
            Date lastEnd = null;
            long duration = 0L;
            boolean anyRunning = false;

            for (HistoricActivityInstance a : instances) {
                Map<String, Object> exec = new LinkedHashMap<>();
                exec.put("taskId", a.getTaskId());
                exec.put("assignee", a.getAssignee());
                exec.put("startTime", a.getStartTime());
                exec.put("endTime", a.getEndTime());
                exec.put("durationInMillis", a.getDurationInMillis());
                exec.put("canceled", a.isCanceled());
                if (a.getTaskId() != null) {
                    List<Map<String, Object>> cs = commentsOf(a.getTaskId());
                    exec.put("comments", cs);
                    comments.addAll(cs);
                }
                executions.add(exec);

                if (a.getAssignee() != null && !a.getAssignee().isBlank()) {
                    assignees.add(a.getAssignee());
                }
                if (a.getStartTime() != null && (firstStart == null || a.getStartTime().before(firstStart))) {
                    firstStart = a.getStartTime();
                }
                if (a.getEndTime() == null) {
                    anyRunning = true;
                } else if (lastEnd == null || a.getEndTime().after(lastEnd)) {
                    lastEnd = a.getEndTime();
                }
                if (a.getDurationInMillis() != null) {
                    duration += a.getDurationInMillis();
                }
            }

            node.put("assignees", new ArrayList<>(assignees));
            node.put("startTime", firstStart);
            node.put("endTime", lastEnd);
            node.put("durationInMillis", instances.isEmpty() ? null : duration);
            node.put("running", anyRunning);
            node.put("executionCount", instances.size());
            node.put("executions", executions);
            node.put("comments", comments);

            // 调用子流程下钻：callActivity 的历史活动上记录了被调起的子流程实例 ID。
            // 父子关系只存在于 ACT_HI_ACTINST.CALL_PROC_INST_ID_，ACT_HI_PROCINST 没有父实例字段，
            // 被调用实例的 startUserId 也常常为空，靠它反推不可行。
            // 同一个调用活动可能执行多次（循环 / 多实例），因此返回列表，前端据此选择要下钻的实例。
            List<Map<String, Object>> calledInstances = calledInstances(instances);
            node.put("calledInstances", calledInstances);
            node.put("calledProcessInstanceId", calledInstances.isEmpty()
                    ? null : calledInstances.get(0).get("processInstanceId"));

            // 兜底：源任务已归档查不到处理人时，用该节点的处理人作为沟通发起人
            if (!commTasks.isEmpty() && !assignees.isEmpty()) {
                String nodeInitiator = assignees.iterator().next();
                for (Map<String, Object> c : commTasks) {
                    if (c.get("initiator") == null) {
                        c.put("initiator", nodeInitiator);
                    }
                }
            }

            int pendingComm = 0;
            int withdrawnComm = 0;
            for (Map<String, Object> c : commTasks) {
                if (Boolean.TRUE.equals(c.get("pending"))) {
                    pendingComm++;
                }
                if (BizCommTask.STATUS_WITHDRAWN.equals(c.get("status"))) {
                    withdrawnComm++;
                }
            }
            commTotal += commTasks.size();
            commPending += pendingComm;
            node.put("commTasks", commTasks);
            node.put("pendingCommCount", pendingComm);
            // 已收回的条数单列，前端据此在“沟通 N 条”上标注，避免收回记录被当成仍在进行
            node.put("withdrawnCommCount", withdrawnComm);
            node.put("commLocked", pendingComm > 0);

            nodes.add(node);
        }

        // 连线：必须由两端节点的执行时序推断，不能只看“目标节点有实例”
        List<Map<String, Object>> edges = new ArrayList<>();
        for (EdgeShape e : diagram.edges) {
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id", e.id);
            edge.put("source", e.source);
            edge.put("target", e.target);
            String edgeState = resolveEdgeState(e, byNode);
            edge.put("taken", !EDGE_NONE.equals(edgeState));
            // 已到达但下游仍在执行：前端渲染为“流转中”的蓝色，与已走完（绿）/未到达（灰）区分
            edge.put("active", EDGE_ACTIVE.equals(edgeState));
            edge.put("waypoints", e.waypoints);
            edges.add(edge);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processInstanceId", hpi.getId());
        result.put("processDefinitionId", hpi.getProcessDefinitionId());
        ProcessDefinition pd = definition(hpi.getProcessDefinitionId());
        result.put("processDefinitionKey", pd == null ? null : pd.getKey());
        result.put("processDefinitionName", pd == null ? null : pd.getName());
        result.put("processTitle", processTitle(hpi));
        result.put("state", hpi.getEndTime() == null ? "RUNNING" : "FINISHED");
        // 子流程实例可能被直接打开（刷新页面、分享链接），带上父实例便于前端提示“返回上级”
        result.put("parentProcessInstanceId", hpi.getSuperProcessInstanceId());
        result.put("startUserId", hpi.getStartUserId());
        result.put("startTime", hpi.getStartTime());
        result.put("endTime", hpi.getEndTime());
        result.put("durationInMillis", hpi.getDurationInMillis());
        result.put("deleteReason", hpi.getDeleteReason());
        result.put("currentNodeNames", activeNodeNames(nodes, activeNodeIds));
        result.put("activeNodeIds", activeNodeIds);
        result.put("completedNodeIds", completedNodeIds);
        result.put("nodes", nodes);
        result.put("edges", edges);
        result.put("diagramWidth", diagram.width);
        result.put("diagramHeight", diagram.height);
        result.put("diagramAvailable", !diagram.shapes.isEmpty());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("nodeCount", nodes.size());
        stats.put("completed", completedCount);
        stats.put("active", activeCount);
        stats.put("pending", nodes.size() - completedCount - activeCount);
        stats.put("commTotal", commTotal);
        stats.put("commPending", commPending);
        result.put("stats", stats);
        return result;
    }

    /**
     * 收集某个调用活动调起的全部子流程实例。
     *
     * <p>父子关系只能从 {@code ACT_HI_ACTINST.CALL_PROC_INST_ID_} 取：{@code ACT_HI_PROCINST}
     * 本身不带父实例字段，被调起实例的 startUserId 也经常为空，无法反推。
     * 同一个调用活动可能执行多次（循环、多实例），所以按出现顺序去重后返回列表；
     * 前端在只有一个实例时直接下钻，多个时让用户选择。</p>
     */
    private List<Map<String, Object>> calledInstances(List<HistoricActivityInstance> instances) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (HistoricActivityInstance a : instances) {
            String calledId = a.getCalledProcessInstanceId();
            if (calledId == null || calledId.isBlank() || result.containsKey(calledId)) {
                continue;
            }
            HistoricProcessInstance child = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(calledId).singleResult();
            if (child == null) {
                continue;
            }
            ProcessDefinition childDefinition = definition(child.getProcessDefinitionId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("processInstanceId", child.getId());
            item.put("processDefinitionKey", child.getProcessDefinitionKey());
            item.put("processDefinitionName", childDefinition == null ? null : childDefinition.getName());
            item.put("processTitle", childDefinition == null ? null : childDefinition.getName());
            // state 与主跟踪数据保持一致：RUNNING / FINISHED
            item.put("state", child.getEndTime() == null ? "RUNNING" : "FINISHED");
            item.put("startTime", child.getStartTime());
            item.put("endTime", child.getEndTime());
            item.put("durationInMillis", child.getDurationInMillis());
            item.put("deleteReason", child.getDeleteReason());
            result.put(calledId, item);
        }
        return new ArrayList<>(result.values());
    }

    private List<String> activeNodeNames(List<Map<String, Object>> nodes, List<String> activeIds) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> n : nodes) {
            if (activeIds.contains(n.get("nodeId"))) {
                names.add((String) n.get("name"));
            }
        }
        return names;
    }

    /** 连线执行态：未到达 */
    private static final String EDGE_NONE = "NONE";
    /** 连线执行态：已走过，且下游节点已结束 */
    private static final String EDGE_TAKEN = "TAKEN";
    /** 连线执行态：已到达，但下游节点仍在执行（进行中） */
    private static final String EDGE_ACTIVE = "ACTIVE";

    /**
     * 推断一条连线的执行态。
     *
     * <p>Camunda 的 ACT_HI_ACTINST 只记录节点活动，不记录连线（full 历史级别也不写
     * sequenceFlow），所以只能由两端节点的执行时序推断。早期实现只看
     * “目标节点是否存在活动实例”，在并行/汇聚网关处会失真：网关一挂起就已有
     * 活动实例，于是所有指向它的入边（包括尚未到达的分支）都会被点亮。</p>
     *
     * <p>这里补上源侧约束：源节点必须真正走完（有结束时间且未被取消），并且目标节点
     * 必须在源节点结束之后才开始（允许同毫秒）。这样仍在办理中的分支不会提前点亮。</p>
     *
     * <p>在此基础上再区分「已走完」与「进行中」：同一连线两端都满足时序关系时，
     * 若目标节点仍有未结束、未取消的活动实例，说明流转已到达但下游还没办完，
     * 返回 {@link #EDGE_ACTIVE}；目标已全部结束才返回 {@link #EDGE_TAKEN}。
     * 多实例场景下一旦存在进行中的实例，以进行中优先，避免“部分完成”被画成已完成。</p>
     */
    private String resolveEdgeState(EdgeShape edge, Map<String, List<HistoricActivityInstance>> byNode) {
        List<HistoricActivityInstance> sources = byNode.getOrDefault(edge.source, Collections.emptyList());
        List<HistoricActivityInstance> targets = byNode.getOrDefault(edge.target, Collections.emptyList());
        if (sources.isEmpty() || targets.isEmpty()) {
            return EDGE_NONE;
        }
        boolean taken = false;
        for (HistoricActivityInstance source : sources) {
            // 源还停在原地（endTime 为空），或者该活动被取消，都不代表走过它的出边
            if (source.getEndTime() == null || source.isCanceled()) {
                continue;
            }
            for (HistoricActivityInstance target : targets) {
                if (target.getStartTime() == null) {
                    continue;
                }
                // 同一事务内前一节点结束与后一节点开始可能是同一毫秒，留 1ms 容差
                if (target.getStartTime().getTime() + 1L < source.getEndTime().getTime()) {
                    continue;
                }
                if (target.getEndTime() == null && !target.isCanceled()) {
                    return EDGE_ACTIVE;
                }
                taken = true;
            }
        }
        return taken ? EDGE_TAKEN : EDGE_NONE;
    }

    private String resolveStatus(List<HistoricActivityInstance> instances) {
        if (instances.isEmpty()) {
            return STATUS_PENDING;
        }
        for (HistoricActivityInstance a : instances) {
            if (a.getEndTime() == null && !a.isCanceled()) {
                return STATUS_ACTIVE;
            }
        }
        return STATUS_COMPLETED;
    }

    private String resolveName(String bpmnName, List<HistoricActivityInstance> instances, String fallback) {
        if (bpmnName != null && !bpmnName.isBlank()) {
            return bpmnName;
        }
        for (HistoricActivityInstance a : instances) {
            if (a.getActivityName() != null && !a.getActivityName().isBlank()) {
                return a.getActivityName();
            }
        }
        return fallback;
    }

    /**
     * 沟通旁路任务：状态以业务表为准，业务记录缺失时按引擎任务是否结束兜底。
     *
     * <p>除执行状态外，这里补齐「沟通明细」需要的会话字段：沟通对象、沟通内容、
     * 反馈内容/反馈人/反馈时间、创建时间，以及会话归属（rootTaskId）和追问关系
     * （parentCommTaskId），前端据此按会话分组并缩进展示追问。</p>
     */
    private Map<String, Object> toCommTaskVO(HistoricTaskInstance t, BizCommTask record, String initiator) {
        String bizStatus = record != null
                ? record.getStatus()
                : (t.getEndTime() == null ? BizCommTask.STATUS_PENDING : "FINISHED");

        Map<String, Object> vo = new LinkedHashMap<>();
        // 已反馈但业务记录缺字段时，用引擎任务兜底：反馈人即沟通对象，反馈时间取任务结束时间
        String replyUserId = record == null ? null : record.getReplyUserId();
        Date replyTime = record == null ? null : record.getReplyTime();
        if (BizCommTask.STATUS_REPLIED.equals(bizStatus)) {
            if (replyUserId == null || replyUserId.isEmpty()) {
                replyUserId = t.getAssignee();
            }
            if (replyTime == null) {
                replyTime = t.getEndTime();
            }
        }
        vo.put("taskId", t.getId());
        vo.put("taskDefKey", t.getTaskDefinitionKey());
        vo.put("name", t.getName());
        // 沟通任务的 assignee 是沟通对象（接收方），发起人另放 initiator
        vo.put("assignee", t.getAssignee());
        vo.put("initiator", initiator);
        vo.put("targetId", record == null ? null : record.getTargetId());
        vo.put("targetName", record == null ? null : record.getTargetName());
        vo.put("targetType", record == null ? null : record.getTargetType());
        vo.put("isFollowUp", record != null && record.getParentCommTaskId() != null);
        vo.put("parentCommTaskId", record == null ? null : record.getParentCommTaskId());
        vo.put("rootTaskId", record == null ? null : record.getRootTaskId());
        // 沟通旁路任务ID 读 CAMUNDA_TASK_ID_；DEPT_PROC_INST_ID_ 是部门子流程实例ID，
        // 当前恒为 null，按 deptProcInstId 原样外发即可（预留字段，前端据此展示部门流转）。
        vo.put("camundaTaskId", record == null ? null : record.getCamundaTaskId());
        vo.put("deptProcInstId", record == null ? null : record.getDeptProcInstId());
        vo.put("status", bizStatus);
        vo.put("pending", BizCommTask.STATUS_PENDING.equals(bizStatus));
        vo.put("content", record == null ? null : record.getContent());
        vo.put("replyContent", record == null ? null : record.getReplyContent());
        vo.put("replyUserId", replyUserId);
        vo.put("replyTime", replyTime);
        vo.put("withdrawTime", record == null ? null : record.getWithdrawTime());
        vo.put("startTime", t.getStartTime());
        vo.put("endTime", t.getEndTime());
        vo.put("createTime", record != null && record.getCreateTime() != null
                ? record.getCreateTime() : t.getStartTime());
        vo.put("durationInMillis", t.getDurationInMillis());
        return vo;
    }

    /**
     * 已收回的沟通：引擎任务已被删除，{@link HistoricTaskInstance} 不存在，
     * 只能由业务记录 BIZ_COMM_TASK_ 还原成与 {@link #toCommTaskVO} 同构的 VO，
     * 前端因此无需为“已收回”单独写一套渲染分支。
     */
    private Map<String, Object> toWithdrawnCommVO(BizCommTask record, String initiator, String sourceKey) {
        Map<String, Object> vo = new LinkedHashMap<>();
        // 历史任务已删，taskId 用业务记录 ID 兜底：它同样是稳定的唯一键，前端用它做 key
        vo.put("taskId", record.getId());
        vo.put("taskDefKey", BizCommTask.buildCommTaskKey(sourceKey));
        vo.put("name", "沟通（已收回）");
        vo.put("assignee", record.getTargetId());
        vo.put("initiator", initiator);
        vo.put("targetId", record.getTargetId());
        vo.put("targetName", record.getTargetName());
        vo.put("targetType", record.getTargetType());
        vo.put("isFollowUp", record.getParentCommTaskId() != null);
        vo.put("parentCommTaskId", record.getParentCommTaskId());
        vo.put("rootTaskId", record.getRootTaskId() != null ? record.getRootTaskId() : record.getId());
        vo.put("camundaTaskId", record.getCamundaTaskId());
        vo.put("deptProcInstId", record.getDeptProcInstId());
        vo.put("status", BizCommTask.STATUS_WITHDRAWN);
        vo.put("pending", false);
        vo.put("withdrawn", true);
        vo.put("content", record.getContent());
        vo.put("replyContent", record.getReplyContent());
        vo.put("replyUserId", record.getReplyUserId());
        vo.put("replyTime", record.getReplyTime());
        vo.put("withdrawTime", record.getWithdrawTime());
        vo.put("startTime", record.getCreateTime());
        vo.put("endTime", null);
        vo.put("createTime", record.getCreateTime());
        vo.put("durationInMillis", null);
        return vo;
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
        } catch (RuntimeException ignore) {
            // 注释表缺失或任务已归档：不阻断跟踪图渲染
        }
        return result;
    }

    private ProcessDefinition definition(String processDefinitionId) {
        if (processDefinitionId == null) {
            return null;
        }
        return repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId).singleResult();
    }

    /** 流程标题：优先运行期变量，流程已结束后退化为历史变量 */
    private String processTitle(HistoricProcessInstance hpi) {
        try {
            Map<String, Object> vars = runtimeService.getVariables(hpi.getId());
            if (vars != null && vars.get("title") != null) {
                return String.valueOf(vars.get("title"));
            }
        } catch (RuntimeException ignore) {
            // 实例已结束，运行期变量取不到
        }
        try {
            HistoricVariableInstance title = historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(hpi.getId()).variableName("title").singleResult();
            if (title != null && title.getValue() != null) {
                return String.valueOf(title.getValue());
            }
        } catch (RuntimeException ignore) {
            // 历史变量不可用
        }
        ProcessDefinition pd = definition(hpi.getProcessDefinitionId());
        return pd == null ? "审批流程" : pd.getName();
    }

    // ===== BPMN DI 解析 =====

    private Diagram parseDiagram(String processDefinitionId) {
        Diagram diagram = new Diagram();
        if (processDefinitionId == null) {
            return diagram;
        }
        try (InputStream in = repositoryService.getProcessModel(processDefinitionId)) {
            if (in == null) {
                return diagram;
            }
            Document doc = newBuilderFactory().newDocumentBuilder().parse(in);
            Map<String, ElementMeta> metas = collectElements(doc);

            NodeList shapeNodes = doc.getElementsByTagNameNS("*", "BPMNShape");
            for (int i = 0; i < shapeNodes.getLength(); i++) {
                Element shape = (Element) shapeNodes.item(i);
                String ref = attr(shape, "bpmnElement");
                ElementMeta meta = metas.get(ref);
                if (meta == null || !RENDERABLE_TYPES.contains(meta.type)) {
                    continue;
                }
                Element bounds = firstDescendant(shape, "Bounds");
                if (bounds == null) {
                    continue;
                }
                NodeShape ns = new NodeShape();
                ns.id = ref;
                ns.name = meta.name;
                ns.type = meta.type;
                ns.eventDefinition = meta.eventDefinition;
                ns.interrupting = meta.interrupting;
                ns.multiInstance = meta.multiInstance;
                ns.x = num(bounds, "x");
                ns.y = num(bounds, "y");
                ns.width = num(bounds, "width");
                ns.height = num(bounds, "height");
                diagram.shapes.add(ns);
                diagram.width = Math.max(diagram.width, ns.x + ns.width);
                diagram.height = Math.max(diagram.height, ns.y + ns.height);
            }

            NodeList edgeNodes = doc.getElementsByTagNameNS("*", "BPMNEdge");
            for (int i = 0; i < edgeNodes.getLength(); i++) {
                Element edge = (Element) edgeNodes.item(i);
                String ref = attr(edge, "bpmnElement");
                ElementMeta meta = metas.get(ref);
                if (meta == null || !"sequenceFlow".equals(meta.type)) {
                    continue;
                }
                EdgeShape es = new EdgeShape();
                es.id = ref;
                es.source = meta.sourceRef;
                es.target = meta.targetRef;
                NodeList points = edge.getElementsByTagNameNS("*", "waypoint");
                for (int p = 0; p < points.getLength(); p++) {
                    Element point = (Element) points.item(p);
                    Map<String, Object> wp = new LinkedHashMap<>();
                    wp.put("x", num(point, "x"));
                    wp.put("y", num(point, "y"));
                    es.waypoints.add(wp);
                    diagram.width = Math.max(diagram.width, num(point, "x"));
                    diagram.height = Math.max(diagram.height, num(point, "y"));
                }
                diagram.edges.add(es);
            }
            // 四周留白，避免描边与阴影被裁切
            diagram.width += 24;
            diagram.height += 24;
        } catch (Exception e) {
            // 模型解析失败不阻断：前端会提示“流程图不可用”，仍可看节点执行明细
            diagram.shapes.clear();
            diagram.edges.clear();
        }
        return diagram;
    }

    private DocumentBuilderFactory newBuilderFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setFeatureQuietly(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        return factory;
    }

    private void setFeatureQuietly(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignore) {
            // 不同 XML 实现支持的特性不同，不支持时保持默认即可（下面已关闭实体展开）
        }
    }

    private Map<String, ElementMeta> collectElements(Document doc) {
        Map<String, ElementMeta> metas = new LinkedHashMap<>();
        NodeList elements = doc.getElementsByTagNameNS(BPMN_NS, "*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element el = (Element) elements.item(i);
            String id = attr(el, "id");
            if (id.isEmpty()) {
                continue;
            }
            ElementMeta meta = new ElementMeta();
            meta.id = id;
            meta.type = el.getLocalName();
            meta.name = attr(el, "name");
            meta.sourceRef = attr(el, "sourceRef");
            meta.targetRef = attr(el, "targetRef");
            meta.eventDefinition = resolveEventDefinition(el);
            // cancelActivity 缺省为 true（中断型）；显式 false 才是非中断边界事件
            meta.interrupting = !"false".equalsIgnoreCase(attr(el, "cancelActivity"));
            meta.multiInstance = resolveMultiInstance(el);
            metas.put(id, meta);
        }
        return metas;
    }

    /**
     * 从事件元素的子节点里取事件定义类型，例如
     * {@code <timerEventDefinition/>} → timer。
     *
     * <p>注意不能用 activityId 或 ACT_TYPE_ 判断：定时开始事件在 BPMN 里元素名仍是
     * startEvent，在历史表里的 ACT_TYPE_ 才是 timerStartEvent，两者都不能直接用于取图标。</p>
     */
    private String resolveEventDefinition(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String local = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
            if (local.endsWith(EVENT_DEF_SUFFIX) && local.length() > EVENT_DEF_SUFFIX.length()) {
                String key = local.substring(0, local.length() - EVENT_DEF_SUFFIX.length());
                return key.substring(0, 1).toLowerCase() + key.substring(1);
            }
        }
        return null;
    }

    /**
     * 从活动元素的子节点里取多实例类型，例如
     * {@code <multiInstanceLoopCharacteristics isSequential="true"/>} → sequential。
     *
     * <p>不能用历史表的 activityId 判断：多实例体被记为
     * {@code userTask1#multiInstanceBody}，而每个实例记录的仍是 {@code userTask1}，
     * 两者都看不出这个活动本身是不是多实例。</p>
     */
    private String resolveMultiInstance(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String local = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
            if ("multiInstanceLoopCharacteristics".equals(local)) {
                Element mi = (Element) child;
                return "true".equalsIgnoreCase(attr(mi, "isSequential")) ? "sequential" : "parallel";
            }
        }
        return null;
    }

    /** 事件节点带上事件定义前缀，多实例节点再带上并行/顺序前缀 */
    private String typeLabel(NodeShape shape) {
        String base = TYPE_LABELS.getOrDefault(shape.type, shape.type);
        String prefix = shape.eventDefinition == null ? null : EVENT_DEF_LABELS.get(shape.eventDefinition);
        String label = prefix == null ? base : prefix + base;
        String miPrefix = shape.multiInstance == null ? null : MULTI_INSTANCE_LABELS.get(shape.multiInstance);
        return miPrefix == null ? label : miPrefix + label;
    }

    private Element firstDescendant(Element parent, String localName) {
        NodeList list = parent.getElementsByTagNameNS("*", localName);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    private String attr(Element el, String name) {
        if (el.hasAttribute(name)) {
            return el.getAttribute(name);
        }
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String local = a.getLocalName() == null ? a.getNodeName() : a.getLocalName();
            if (name.equals(local) || name.equals(a.getNodeName())) {
                return a.getNodeValue();
            }
        }
        return "";
    }

    private double num(Element el, String name) {
        String v = attr(el, name);
        if (v == null || v.isBlank()) {
            return 0d;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private static class Diagram {
        private final List<NodeShape> shapes = new ArrayList<>();
        private final List<EdgeShape> edges = new ArrayList<>();
        private double width = 0d;
        private double height = 0d;
    }

    private static class ElementMeta {
        private String id;
        private String type;
        private String name;
        private String sourceRef;
        private String targetRef;
        private String eventDefinition;
        private boolean interrupting = true;
        private String multiInstance;
    }

    private static class NodeShape {
        private String id;
        private String name;
        private String type;
        private String eventDefinition;
        private boolean interrupting = true;
        private String multiInstance;
        private double x;
        private double y;
        private double width;
        private double height;
    }

    private static class EdgeShape {
        private String id;
        private String source;
        private String target;
        private final List<Map<String, Object>> waypoints = new ArrayList<>();
    }
}
