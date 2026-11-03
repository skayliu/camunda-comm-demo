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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流程图跟踪服务：把 Camunda 的 BPMN DI（节点坐标、连线路径）与历史活动实例
 * 合成一份前端可直接绘制的结构，节点带执行状态与执行明细。
 *
 * <p>不依赖 bpmn-js：DI 几何来自部署时保存的 BPMN XML，状态来自
 * {@link HistoricActivityInstance}，前端用 SVG 还原并高亮。</p>
 */
@Service
public class ProcessTraceService {

    /** BPMN 2.0 模型命名空间 */
    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /** 只渲染有执行语义的节点类型，注释/数据对象等不参与流程图跟踪 */
    private static final Set<String> RENDERABLE_TYPES = Set.of(
            "startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent",
            "boundaryEvent", "userTask", "serviceTask", "scriptTask", "manualTask",
            "businessRuleTask", "sendTask", "receiveTask", "task", "callActivity",
            "subProcess", "exclusiveGateway", "parallelGateway", "inclusiveGateway",
            "eventBasedGateway");

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
            Map.entry("exclusiveGateway", "排他网关"),
            Map.entry("parallelGateway", "并行网关"),
            Map.entry("inclusiveGateway", "包容网关"),
            Map.entry("eventBasedGateway", "事件网关"));

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

    /**
     * 当前用户能否查看该流程实例的跟踪图。
     *
     * <p>可见范围：流程发起人 + 在该实例上办理过任务的人（含沟通任务的反馈人），
     * 以及当前待办的处理人。这样“我的待办 / 已办 / 我的发起”列表里的记录都能打开跟踪。</p>
     */
    public boolean canView(String processInstanceId, String userId) {
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
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskAssignee(userId)
                .count() > 0;
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

        // 流程节点的执行实例，按节点 key 聚合
        Map<String, List<HistoricActivityInstance>> byNode = new LinkedHashMap<>();
        for (HistoricActivityInstance a : activities) {
            if (a.getActivityId() == null || !RENDERABLE_TYPES.contains(a.getActivityType())) {
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
            commByNode.computeIfAbsent(sourceKey, k -> new ArrayList<>())
                    .add(toCommTaskVO(t, record));
        }

        Diagram diagram = parseDiagram(hpi.getProcessDefinitionId());

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
            node.put("typeLabel", TYPE_LABELS.getOrDefault(shape.type, shape.type));
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

            int pendingComm = 0;
            for (Map<String, Object> c : commTasks) {
                if (Boolean.TRUE.equals(c.get("pending"))) {
                    pendingComm++;
                }
            }
            commTotal += commTasks.size();
            commPending += pendingComm;
            node.put("commTasks", commTasks);
            node.put("pendingCommCount", pendingComm);
            node.put("commLocked", pendingComm > 0);

            nodes.add(node);
        }

        // 连线：目标的节点只要有执行实例，说明这条路径被走过
        List<Map<String, Object>> edges = new ArrayList<>();
        for (EdgeShape e : diagram.edges) {
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id", e.id);
            edge.put("source", e.source);
            edge.put("target", e.target);
            edge.put("taken", byNode.containsKey(e.target));
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

    private List<String> activeNodeNames(List<Map<String, Object>> nodes, List<String> activeIds) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> n : nodes) {
            if (activeIds.contains(n.get("nodeId"))) {
                names.add((String) n.get("name"));
            }
        }
        return names;
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

    /** 沟通旁路任务：状态以业务表为准，业务记录缺失时按引擎任务是否结束兜底 */
    private Map<String, Object> toCommTaskVO(HistoricTaskInstance t, BizCommTask record) {
        String bizStatus = record != null
                ? record.getStatus()
                : (t.getEndTime() == null ? BizCommTask.STATUS_PENDING : "FINISHED");

        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("taskId", t.getId());
        vo.put("taskDefKey", t.getTaskDefinitionKey());
        vo.put("name", t.getName());
        vo.put("assignee", t.getAssignee());
        vo.put("isFollowUp", record != null && record.getParentCommTaskId() != null);
        vo.put("status", bizStatus);
        vo.put("pending", BizCommTask.STATUS_PENDING.equals(bizStatus));
        vo.put("content", record == null ? null : record.getContent());
        vo.put("startTime", t.getStartTime());
        vo.put("endTime", t.getEndTime());
        vo.put("durationInMillis", t.getDurationInMillis());
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
            metas.put(id, meta);
        }
        return metas;
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
    }

    private static class NodeShape {
        private String id;
        private String name;
        private String type;
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
