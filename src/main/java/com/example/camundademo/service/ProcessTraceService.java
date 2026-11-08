package com.example.camundademo.service;

import com.example.camundademo.entity.BizCommTask;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.history.HistoricTaskInstance;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.identity.Group;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.VariableInstance;
import org.camunda.bpm.engine.task.Comment;
import org.camunda.bpm.engine.task.IdentityLink;
import org.camunda.bpm.engine.task.IdentityLinkType;
import org.camunda.bpm.engine.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.HashMap;
import java.util.HashSet;
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

    private static final Logger LOG = LoggerFactory.getLogger(ProcessTraceService.class);

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
    private IdentityService identityService;

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
        // 部门沟通的 dept_handle 是候选组任务（candidateGroups=${deptId}），未认领时
        // 历史任务上没有 assignee。部门成员在待办里看得到、点开跟踪图却会被判 403，
        // 因此候选人与办理人享受同一可见范围。
        if (taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskCandidateUser(userId)
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
        // 部门沟通是业务侧独立启动的实例，父子关系不在引擎表里，只在
        // BIZ_COMM_TASK_.DEPT_PROC_INST_ID_ 上。不沿业务记录找回发起沟通的实例，
        // 父流程里点下钻同样会 403。
        for (String parentId : commParentProcessInstanceIds(hpi)) {
            if (canView(parentId, userId, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 部门沟通子流程的业务父实例。
     *
     * <p>部门沟通不是 callActivity 调起的，{@code getSuperProcessInstanceId()} 为空；
     * 唯一的父子线索是业务记录的 businessKey（= BIZ_COMM_TASK_.ID_）与
     * {@code SOURCE_TASK_ID_}。由此反查发起沟通的那个历史任务，再取其所属流程实例。</p>
     *
     * <p>源任务被删除（沟通已收回）时查不到，此时退化为“不继承权限”，不影响其它判定。</p>
     */
    private List<String> commParentProcessInstanceIds(HistoricProcessInstance hpi) {
        if (hpi == null || hpi.getBusinessKey() == null || hpi.getBusinessKey().isBlank()) {
            return Collections.emptyList();
        }
        try {
            BizCommTask record = communicationService.getCommRecordById(hpi.getBusinessKey());
            if (record == null || record.getSourceTaskId() == null) {
                return Collections.emptyList();
            }
            HistoricTaskInstance sourceTask = historyService.createHistoricTaskInstanceQuery()
                    .taskId(record.getSourceTaskId()).singleResult();
            if (sourceTask == null || sourceTask.getProcessInstanceId() == null
                    || sourceTask.getProcessInstanceId().equals(hpi.getId())) {
                return Collections.emptyList();
            }
            return List.of(sourceTask.getProcessInstanceId());
        } catch (RuntimeException ex) {
            LOG.warn("按沟通记录回溯父实例失败，businessKey=" + hpi.getBusinessKey(), ex);
            return Collections.emptyList();
        }
    }

    /**
     * 当前实例的父实例引用，供前端呈现「返回上级流程」。
     *
     * <p>两条父子链路：</p>
     * <ul>
     *   <li>callActivity 调起的子流程：{@code superProcessInstanceId} 直接有值；</li>
     *   <li>部门沟通子流程：由 {@code startProcessInstanceByKey} 独立启动，引擎里没有父子关系，
     *       只能沿 {@code BIZ_COMM_TASK_.SOURCE_TASK_ID_} 找回发起沟通的那个历史任务，
     *       再取其所属流程实例。并发起节点（{@code activityId}）也一并带回，
     *       前端可显示“来自 xxx 节点的沟通”。</li>
     * </ul>
     *
     * <p>父实例已被清理时返回空 map：前端不显示入口，避免点进去只得到“流程实例不存在”。</p>
     */
    private Map<String, Object> parentInstanceRef(HistoricProcessInstance hpi) {
        if (hpi == null) {
            return Collections.emptyMap();
        }
        String parentId = hpi.getSuperProcessInstanceId();
        String activityId = null;
        String source = SOURCE_CALL_ACTIVITY;
        if (parentId == null) {
            String businessKey = hpi.getBusinessKey();
            if (businessKey == null || businessKey.isBlank()) {
                return Collections.emptyMap();
            }
            try {
                BizCommTask record = communicationService.getCommRecordById(businessKey);
                if (record == null || record.getSourceTaskId() == null) {
                    return Collections.emptyMap();
                }
                HistoricTaskInstance sourceTask = historyService.createHistoricTaskInstanceQuery()
                        .taskId(record.getSourceTaskId()).singleResult();
                if (sourceTask == null || sourceTask.getProcessInstanceId() == null
                        || sourceTask.getProcessInstanceId().equals(hpi.getId())) {
                    return Collections.emptyMap();
                }
                parentId = sourceTask.getProcessInstanceId();
                activityId = sourceTask.getTaskDefinitionKey();
                source = SOURCE_DEPT_COMM;
            } catch (RuntimeException ex) {
                // 业务记录缺失或源任务已删除（沟通被收回）时不阻断跟踪图，只是没有返回入口
                LOG.warn("回溯父实例失败，businessKey=" + businessKey, ex);
                return Collections.emptyMap();
            }
        }
        HistoricProcessInstance parent = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(parentId).singleResult();
        if (parent == null) {
            return Collections.emptyMap();
        }
        ProcessDefinition parentDef = definition(parent.getProcessDefinitionId());
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("processInstanceId", parentId);
        ref.put("processTitle", processTitle(parent));
        ref.put("processDefinitionName", parentDef == null ? null : parentDef.getName());
        ref.put("activityId", activityId);
        ref.put("source", source);
        return ref;
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

        // 候选组名称在本实例内查一次、缓存一次：同一部门可能出现在多个节点/多条执行上
        Map<String, String> groupNameCache = new HashMap<>();
        // 部门沟通子流程的候选组写成 ${deptId}，BPMN 里是表达式而不是部门ID，
        // 历史身份链接缺失时退回 deptName 变量，明细才不会只写“待认领”却看不到该找谁。
        // 同时带回“值取自哪一级”，诊断日志才能区分“兜底命中”与“两级都空”。
        DeptCandidates deptCandidates = deptCandidates(hpi, groupNameCache);
        // 实例用的 BPMN 版本：候选链接缺失的常见成因之一是“实例跑在旧版本定义上”，
        // 光看代码改了没用，必须能从日志一眼对上是哪个版本。
        String procDefId = hpi == null ? null : hpi.getProcessDefinitionId();

        // 运行期任务集合：查询任务身份链接前必须先确认任务还在运行。
        // WorkbenchController.trace 在每次打开跟踪图时都会走一遍，这里只查一次实例下的运行任务，
        // 避免每个历史活动各查一次、也避免对已结束任务调用 getIdentityLinksForTask。
        Set<String> runningTaskIds = new HashSet<>();
        try {
            for (Task t : taskService.createTaskQuery().processInstanceId(processInstanceId).list()) {
                runningTaskIds.add(t.getId());
            }
        } catch (RuntimeException ex) {
            // 引擎查询失败时退化为“无运行期任务”，只损失待认领标记，不能让跟踪图打不开
            LOG.warn("加载运行期任务失败，processInstanceId=" + processInstanceId, ex);
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

        // 业务表兜底：引擎历史里缺失的沟通记录只能从这里补回来。
        // 两种记录必须补：
        // 1) 已收回的沟通 —— 收回走 taskService.deleteTask(...)，Camunda 会把对应的
        //    ACT_HI_TASKINST 历史任务一并删除，历史任务查询根本拿不到；
        // 2) 部门沟通 —— 任务来自独立的 dept_communication 子流程，属于另一个流程实例，
        //    按当前 processInstanceId 查历史任务永远扫不到它（表现为同时给人、给部门
        //    发沟通时，跟踪图和明细里只剩人员那一条）。
        // 查询失败（如老库缺 BIZ_COMM_TASK_ 表）时不能拖垮整张跟踪图，只损失这两类记录。
        List<BizCommTask> sourceRecords = Collections.emptyList();
        try {
            sourceRecords = communicationService
                    .getCommRecordsBySourceTaskIds(sourceDefKeyByTaskId.keySet());
        } catch (RuntimeException ex) {
            LOG.warn("跟踪图补全沟通记录失败，processInstanceId=" + processInstanceId, ex);
        }

        // 部门沟通按业务记录还原，归到发起它的源节点上。
        // 历史任务里已存在同一条记录时跳过，避免与上面的引擎侧展示重复。
        for (BizCommTask record : sourceRecords) {
            if (!BizCommTask.TARGET_DEPT.equals(record.getTargetType())) {
                continue;
            }
            // 已收回的记录交给下面的统一分支处理，避免同一条记录被两套逻辑各加一次
            if (BizCommTask.STATUS_WITHDRAWN.equals(record.getStatus())) {
                continue;
            }
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
                    .add(toDeptCommVO(record, initiator, sourceKey));
        }

        for (BizCommTask record : sourceRecords) {
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

        // 转办记录：转办只改 assignee，任务 ID 不变，按 taskId 归集即可精确落到节点上。
        // 任务被删除（如收回沟通）时历史任务里查不到，再用 taskDefKey 兜底。
        // 整个实例只查一次，避免每个节点都访问业务表。
        List<Map<String, Object>> instanceTransfers = Collections.emptyList();
        try {
            instanceTransfers = taskTransferService.listByProcessInstanceId(processInstanceId);
        } catch (RuntimeException ex) {
            // 老库缺 BIZ_TASK_TRANSFER_ 表时不能让整张跟踪图挂掉，仅损失转办信息
            LOG.warn("加载转办记录失败，processInstanceId=" + processInstanceId, ex);
        }
        Map<String, List<Map<String, Object>>> transferByTaskId = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> transferByDefKey = new LinkedHashMap<>();
        for (Map<String, Object> t : instanceTransfers) {
            Object taskId = t.get("taskId");
            if (taskId != null) {
                transferByTaskId.computeIfAbsent(String.valueOf(taskId), k -> new ArrayList<>()).add(t);
            }
            Object defKey = t.get("taskDefKey");
            if (defKey != null) {
                transferByDefKey.computeIfAbsent(String.valueOf(defKey), k -> new ArrayList<>()).add(t);
            }
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<String> activeNodeIds = new ArrayList<>();
        List<String> completedNodeIds = new ArrayList<>();
        String firstName = null;
        int completedCount = 0;
        int activeCount = 0;
        int commTotal = 0;
        int commPending = 0;
        int transferTotal = 0;

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
            // 候选组/候选人：未认领时明细要能说清“还在等谁”，而不是只留一个「—」
            Set<String> candidateGroups = new LinkedHashSet<>();
            Set<String> candidateGroupNames = new LinkedHashSet<>();
            Set<String> candidateUsers = new LinkedHashSet<>();
            boolean pendingClaim = false;
            Date firstStart = null;
            Date lastEnd = null;
            long duration = 0L;
            boolean anyRunning = false;

            for (HistoricActivityInstance a : instances) {
                // 办理人优先取历史任务表。Camunda 对「先 claim 再 complete」的候选组任务
                // 只把 assignee 写进 ACT_HI_TASKINST，ACT_HI_ACTINST.ASSIGNEE_ 仍为 null，
                // 而早期实现只读后者，导致部门沟通这类任务明明有人办理，明细里却是「—」。
                String assignee = assigneeOf(a, taskById);
                // 进行中且无人办理 = 尚未认领；已结束（含被取消）的任务不再标“待认领”
                boolean running = a.getEndTime() == null && !a.isCanceled();
                CandidateInfo candidate = candidateInfoOf(a.getTaskId(), a.getActivityId(),
                        processInstanceId, procDefId, assignee, running,
                        runningTaskIds, groupNameCache, deptCandidates);
                Map<String, Object> exec = new LinkedHashMap<>();
                exec.put("taskId", a.getTaskId());
                exec.put("assignee", assignee);
                exec.put("pendingClaim", candidate.pendingClaim);
                exec.put("candidateGroups", candidate.groupIds);
                exec.put("candidateGroupNames", candidate.groupNames);
                exec.put("candidateUsers", candidate.userIds);
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

                if (candidate.pendingClaim) {
                    pendingClaim = true;
                }
                candidateGroups.addAll(candidate.groupIds);
                candidateGroupNames.addAll(candidate.groupNames);
                candidateUsers.addAll(candidate.userIds);

                if (assignee != null && !assignee.isBlank()) {
                    assignees.add(assignee);
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
            // 未认领标记与候选范围：前端据此把「—」显示成「待认领 · 候选部门：xxx」，
            // 避免把“还没人接手”误读成“办理人漏写”。
            node.put("pendingClaim", pendingClaim);
            node.put("candidateGroups", new ArrayList<>(candidateGroups));
            node.put("candidateGroupNames", new ArrayList<>(candidateGroupNames));
            node.put("candidateUsers", new ArrayList<>(candidateUsers));
            node.put("startTime", firstStart);
            node.put("endTime", lastEnd);
            node.put("durationInMillis", instances.isEmpty() ? null : duration);
            node.put("running", anyRunning);
            node.put("executionCount", instances.size());
            node.put("executions", executions);
            node.put("comments", comments);

            // 该节点的转办记录：先按任务 ID 精确归集（同一节点多次执行时各是各的链），
            // 再补只在业务表里留下记录、历史任务已被删除的，按记录 ID 去重。
            List<Map<String, Object>> nodeTransfers = new ArrayList<>();
            Set<String> seenTransferIds = new LinkedHashSet<>();
            for (HistoricActivityInstance a : instances) {
                if (a.getTaskId() == null) {
                    continue;
                }
                for (Map<String, Object> t : transferByTaskId.getOrDefault(a.getTaskId(), Collections.emptyList())) {
                    if (seenTransferIds.add(String.valueOf(t.get("id")))) {
                        nodeTransfers.add(t);
                    }
                }
            }
            for (Map<String, Object> t : transferByDefKey.getOrDefault(shape.id, Collections.emptyList())) {
                if (seenTransferIds.add(String.valueOf(t.get("id")))) {
                    nodeTransfers.add(t);
                }
            }
            nodeTransfers.sort((a, b) -> {
                Object ta = a.get("createTime");
                Object tb = b.get("createTime");
                if (ta instanceof Date && tb instanceof Date) {
                    return ((Date) ta).compareTo((Date) tb);
                }
                return 0;
            });
            node.put("transfers", nodeTransfers);
            node.put("transferCount", nodeTransfers.size());
            // 「原办理人 → 接收人 → …」，前端明细与悬停直接展示，不必自己拼
            node.put("transferPath", transferPath(nodeTransfers));
            transferTotal += nodeTransfers.size();

            // 调用子流程下钻：callActivity 的历史活动上记录了被调起的子流程实例 ID。
            // 父子关系只存在于 ACT_HI_ACTINST.CALL_PROC_INST_ID_，ACT_HI_PROCINST 没有父实例字段，
            // 被调用实例的 startUserId 也常常为空，靠它反推不可行。
            // 同一个调用活动可能执行多次（循环 / 多实例），因此返回列表，前端据此选择要下钻的实例。
            List<Map<String, Object>> calledInstances = calledInstances(instances);
            // 部门沟通走的是业务侧独立启动的 dept_communication 实例，引擎不会在
            // CALL_PROC_INST_ID_ 上留下父子关系，单靠历史活动永远拿不到它。
            // 实例 ID 本来就存在沟通记录上（DEPT_PROC_INST_ID_），并进同一份下钻列表，
            // 前端沿用现有的徽标 / 面包屑 / 多实例选择，不必再写第二套下钻。
            mergeDeptCommInstances(calledInstances, commTasks);
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
        // 子流程实例可能被直接打开（刷新页面、从待办/列表进来），带上父实例前端才能给“返回上级”。
        // callActivity 的父子关系在 superProcessInstanceId 上；部门沟通是业务侧 startProcessInstanceByKey
        // 独立启动的，引擎表里没有父子关系，只能沿 BIZ_COMM_TASK_.SOURCE_TASK_ID_ 回溯。
        Map<String, Object> parentRef = parentInstanceRef(hpi);
        result.put("parentProcessInstanceId", parentRef.get("processInstanceId"));
        result.put("parentProcessTitle", parentRef.get("processTitle"));
        result.put("parentProcessDefinitionName", parentRef.get("processDefinitionName"));
        result.put("parentActivityId", parentRef.get("activityId"));
        result.put("parentSource", parentRef.get("source"));
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
        stats.put("transferTotal", transferTotal);
        result.put("stats", stats);
        return result;
    }

    /**
     * 把转办记录拼成「原办理人 → 接收人 → …」。
     *
     * <p>每条转办记录同时带 from / to，按 taskId 分组后顺序串联即可得到完整链条；
     * 同一节点被执行多次（循环、多实例）时，各组之间用「；」分隔，避免两条链首尾错误相连。</p>
     */
    private String transferPath(List<Map<String, Object>> transfers) {
        if (transfers == null || transfers.isEmpty()) {
            return null;
        }
        Map<String, List<Map<String, Object>>> byTask = new LinkedHashMap<>();
        for (Map<String, Object> t : transfers) {
            byTask.computeIfAbsent(String.valueOf(t.get("taskId")), k -> new ArrayList<>()).add(t);
        }
        List<String> chains = new ArrayList<>();
        for (List<Map<String, Object>> chain : byTask.values()) {
            List<String> path = new ArrayList<>();
            for (Map<String, Object> t : chain) {
                String from = t.get("from") == null ? null : String.valueOf(t.get("from"));
                String to = t.get("to") == null ? null : String.valueOf(t.get("to"));
                if (path.isEmpty() && from != null) {
                    path.add(from);
                }
                if (to != null && (path.isEmpty() || !to.equals(path.get(path.size() - 1)))) {
                    path.add(to);
                }
            }
            if (!path.isEmpty()) {
                chains.add(String.join(" → ", path));
            }
        }
        return chains.isEmpty() ? null : String.join("；", chains);
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
            // 来源决定前端文案：调用活动是 BPMN 语义，部门沟通是业务侧启动的子流程实例
            item.put("source", SOURCE_CALL_ACTIVITY);
            result.put(calledId, item);
        }
        return new ArrayList<>(result.values());
    }

    /** 下钻实例来源：BPMN 调用活动 */
    private static final String SOURCE_CALL_ACTIVITY = "callActivity";
    /** 下钻实例来源：业务侧启动的部门沟通子流程 */
    private static final String SOURCE_DEPT_COMM = "deptComm";

    /**
     * 把部门沟通子流程实例并入节点的下钻列表。
     *
     * <p>部门沟通由 {@code CommTaskFactory} 用 {@code startProcessInstanceByKey} 独立启动，
     * 不是 callActivity，因此 {@code ACT_HI_ACTINST.CALL_PROC_INST_ID_} 为空，
     * 按历史活动收集永远收不到它——这正是跟踪图上「部门沟通」节点没有下钻入口的原因。</p>
     *
     * <p>实例 ID 已经落在业务记录 {@code DEPT_PROC_INST_ID_} 上，直接取用即可，
     * 不需要新增父子表，也不用回填历史数据：存量记录本来就有这一列。</p>
     */
    private void mergeDeptCommInstances(List<Map<String, Object>> target,
                                        List<Map<String, Object>> commTasks) {
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> item : target) {
            seen.add(String.valueOf(item.get("processInstanceId")));
        }
        for (Map<String, Object> comm : commTasks) {
            Object raw = comm.get("deptProcInstId");
            if (raw == null) {
                continue;
            }
            String deptInstId = String.valueOf(raw);
            if (deptInstId.isBlank() || !seen.add(deptInstId)) {
                continue;
            }
            HistoricProcessInstance child = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(deptInstId).singleResult();
            if (child == null) {
                // 实例被物理清理（老库、手工删数据）时无图可下钻；保留沟通记录本身即可
                LOG.debug("部门沟通实例不存在，跳过下钻，processInstanceId=" + deptInstId);
                continue;
            }
            ProcessDefinition childDefinition = definition(child.getProcessDefinitionId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("processInstanceId", child.getId());
            item.put("processDefinitionKey", child.getProcessDefinitionKey());
            item.put("processDefinitionName", childDefinition == null
                    ? "部门沟通子流程" : childDefinition.getName());
            item.put("processTitle", childDefinition == null
                    ? "部门沟通子流程" : childDefinition.getName());
            item.put("state", child.getEndTime() == null ? "RUNNING" : "FINISHED");
            item.put("startTime", child.getStartTime());
            item.put("endTime", child.getEndTime());
            item.put("durationInMillis", child.getDurationInMillis());
            item.put("deleteReason", child.getDeleteReason());
            item.put("source", SOURCE_DEPT_COMM);
            // 前端在选择列表里用部门名区分“给谁发的这一趟沟通”
            item.put("commTaskId", comm.get("taskId"));
            item.put("targetName", comm.get("targetName"));
            target.add(item);
        }
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

    /**
     * 取某个活动实例的办理人。
     *
     * <p>{@link HistoricActivityInstance#getAssignee()} 读的是 {@code ACT_HI_ACTINST.ASSIGNEE_}，
     * 它只在任务创建时就带有办理人（如 BPMN 里写死 assignee）时才有值。候选组任务
     * （{@code camunda:candidateGroups}）先由成员认领、再办结的路径，Camunda 只更新
     * {@code ACT_HI_TASKINST.ASSIGNEE_}，活动实例上始终是 null。部门沟通的 dept_handle
     * 正是这种任务，所以必须优先取历史任务表，活动实例仅作兜底（历史任务已被删除时）。</p>
     */
    private String assigneeOf(HistoricActivityInstance a, Map<String, HistoricTaskInstance> taskById) {
        if (a.getTaskId() != null) {
            HistoricTaskInstance task = taskById.get(a.getTaskId());
            if (task != null && task.getAssignee() != null && !task.getAssignee().isBlank()) {
                return task.getAssignee();
            }
        }
        return a.getAssignee() == null || a.getAssignee().isBlank() ? null : a.getAssignee();
    }

    /**
     * 解析任务实例的候选范围，并判定是否仍处于「待认领」。
     *
     * <p>候选组必须取 {@code ACT_RU_IDENTITYLINK}（type = candidate）：部门沟通的
     * {@code dept_handle} 在 BPMN 里写的是 {@code camunda:candidateGroups="${deptId}"}，
     * 是表达式而不是部门ID，静态解析 BPMN 拿不到具体部门；身份链接在任务创建时即写入，
     * 认领后依然保留，是判断“还在等谁认领”的直接来源。</p>
     *
     * <p><b>只能对运行中的任务查</b>：{@code getIdentityLinksForTask} 在任务不存在时
     * 不是返回空列表，而是抛 {@code NullValueException("Cannot find task with id ...")}。
     * 跟踪页遍历的是 {@code ACT_HI_ACTINST} 的全部历史活动，绝大多数活动的任务早已办结、
     * 被收回或随流程结束而删除，逐条直接查会在每次打开跟踪图时刷出一整屏异常堆栈。
     * 因此这里先用运行期任务集合过滤，只有任务确实还在运行才查身份链接。</p>
     *
     * <p>{@code pendingClaim} 只对进行中且任务确实存在的活动成立：已结束（含被取消）
     * 的任务即便当初无人认领，也已经是历史；而历史活动 endTime 为空、运行任务却已不存在
     * （如沟通被收回）时同样不能标“待认领”，否则会指向一个已经没人能接的任务。</p>
     *
     * <p>「待认领」在页面上只有一种显示，但底层有两种完全不同的成因：候选组压根没生成
     * （{@code ${deptId}} 没解析出值，任何人都不具备认领资格），以及候选组正常、只是还没人认领。
     * 两者必须能从日志上分开，因此这里在判定为待认领时输出一条诊断：
     * 身份链接里的 candidate 链接数、解析出的组ID、兜底变量的来源与命中情况。</p>
     */
    private CandidateInfo candidateInfoOf(String taskId, String activityId, String processInstanceId,
                                         String processDefinitionId,
                                         String assignee, boolean running,
                                         Set<String> runningTaskIds,
                                         Map<String, String> groupNameCache,
                                         DeptCandidates fallback) {
        CandidateInfo info = new CandidateInfo();
        if (taskId == null) {
            return info;
        }
        boolean taskRunning = runningTaskIds != null && runningTaskIds.contains(taskId);
        boolean unassigned = assignee == null || assignee.isBlank();
        // 候选信息只有一个用途：未认领时告诉用户“还在等谁”。
        // 已认领、已结束的任务不需要候选范围，也就不必碰运行期任务表 —— 这是本次
        // NullValueException 的直接触发点，把查询条件收紧到“确实待认领”后自然消除。
        info.pendingClaim = running && taskRunning && unassigned;
        if (info.pendingClaim) {
            int candidateLinks = 0;
            try {
                List<IdentityLink> links = taskService.getIdentityLinksForTask(taskId);
                for (IdentityLink link : links) {
                    if (!IdentityLinkType.CANDIDATE.equals(link.getType())) {
                        continue;
                    }
                    candidateLinks++;
                    String groupId = link.getGroupId();
                    if (groupId != null && !groupId.isBlank() && info.groupIds.add(groupId)) {
                        info.groupNames.add(groupName(groupId, groupNameCache));
                    }
                    String userId = link.getUserId();
                    if (userId != null && !userId.isBlank()) {
                        info.userIds.add(userId);
                    }
                }
            } catch (RuntimeException ex) {
                // 任务恰好在查询前被办结或收回：只损失候选信息，不能因此让整张跟踪图打不开。
                // 但这条不是“没有候选”，必须留在日志里，否则又会被当成待认领漏配。
                LOG.warn("[待认领候选] 查询身份链接失败 taskId=" + taskId
                        + " activityId=" + activityId + " processInstanceId=" + processInstanceId, ex);
            }
            if (info.groupNames.isEmpty() && fallback != null) {
                info.groupNames.addAll(fallback.names);
            }
            logCandidateDiagnosis(taskId, activityId, processInstanceId, processDefinitionId,
                    candidateLinks, info, fallback);
        }
        return info;
    }

    /**
     * 待认领任务的候选诊断日志。
     *
     * <p>三类结论不能用同一句话概括：</p>
     * <ul>
     *   <li>{@code 候选组已生成}：链接里有候选组，页面显示“待认领 · 候选：xx”即可，属正常；</li>
     *   <li>{@code 候选组未生成}：链接里一条 candidate 都没有，但 {@code deptName}/{@code deptId}
     *       变量在，页面能显示部门名 —— <b>只是展示被兜底了，任务实际没有候选人，谁都认领不了</b>；</li>
     *   <li>{@code 无任何候选}：链接与变量两级都空，页面显示“待认领”却没有任何可显示的对象。</li>
     * </ul>
     *
     * <p>{@code candidateLinks} 为 0 且兜底为空时，必须继续查变量是否被写进了作用的子执行实例
     * （{@code ACT_RU_VARIABLE} 查不到不等于没写），否则容易把“作用域放错”误判为“启动入口没写”。</p>
     */
    private void logCandidateDiagnosis(String taskId, String activityId, String processInstanceId,
                                       String processDefinitionId,
                                       int candidateLinks, CandidateInfo info, DeptCandidates fallback) {
        String scope = "taskId=" + taskId
                + " activityId=" + activityId
                + " processInstanceId=" + processInstanceId
                + " procDefId=" + processDefinitionId;
        boolean hasGroup = !info.groupIds.isEmpty();

        if (hasGroup) {
            // 正常路径：候选组已生成，页面能显示具体部门。放在 INFO，便于和异常情况一眼区分。
            LOG.info("[待认领候选] " + scope + " 候选组已生成 candidateLinks=" + candidateLinks
                    + " groups=" + info.groupIds + " names=" + info.groupNames
                    + " users=" + info.userIds);
            return;
        }

        if (candidateLinks > 0) {
            // 有 candidate 链接却没有组ID：任务配的是候选人而不是候选组，
            // 这与“候选组解析失败”不同，页面应显示候选人而不是候选部门。
            LOG.warn("[待认领候选] " + scope + " 有候选人无候选组 candidateLinks=" + candidateLinks
                    + " users=" + info.userIds + "，请确认 BPMN 用的是 candidateUsers 而非 candidateGroups");
            return;
        }

        boolean fallbackHit = fallback != null && !fallback.names.isEmpty();
        if (fallbackHit) {
            LOG.warn("[待认领候选] " + scope
                    + " 候选组未生成 candidateLinks=0 groups=[] 兜底显示 names=" + fallback.names
                    + "（来源 " + fallback.source + "）"
                    + "；兜底只解决展示，任务实际没有候选范围，任何用户都无法认领，"
                    + "请核对 BPMN 版本与 ${deptId} 是否在任务创建前已写入");
        } else {
            String source = fallback == null ? "非部门沟通子流程" : fallback.source;
            LOG.warn("[待认领候选] " + scope
                    + " 无任何候选 candidateLinks=0 groups=[] names=[]"
                    + "（兜底变量来源 " + source + "）"
                    + "；该任务没有任何候选范围，需检查启动入口是否写入 deptId/deptName"
                    + "，以及变量是否落在可见的作用域内");
        }
    }

    /** 组ID → 组名称；查不到（如外部用户体系未同步）时原样返回组ID，不留空 */
    private String groupName(String groupId, Map<String, String> cache) {
        if (cache.containsKey(groupId)) {
            return cache.get(groupId);
        }
        String name = groupId;
        try {
            Group group = identityService.createGroupQuery().groupId(groupId).singleResult();
            if (group != null && group.getName() != null && !group.getName().isBlank()) {
                name = group.getName();
            }
        } catch (RuntimeException ignore) {
            // 身份服务不可用时退回组ID，明细仍可读
        }
        cache.put(groupId, name);
        return name;
    }

    /**
     * 部门沟通子流程的候选部门名称兜底。
     *
     * <p>只有 {@code dept_communication} 实例才需要：其它流程的候选组来自 BPMN 静态配置，
     * 历史身份链接足以覆盖；而这个子流程的候选组是 {@code ${deptId}}，一旦身份链接缺失，
     * 就只能从启动时写入的 {@code deptName} / {@code deptId} 变量还原。</p>
     *
     * <p>返回值同时带上来源说明：诊断日志需要说清“显示出来的部门名是变量兜底的，
     * 而不是身份链接解析出来的”，否则会把“能看见候选”误判成“候选可用”。</p>
     */
    private DeptCandidates deptCandidates(HistoricProcessInstance hpi, Map<String, String> groupNameCache) {
        if (hpi == null || !DeptCommProcessService.PROCESS_KEY.equals(hpi.getProcessDefinitionKey())) {
            return DeptCandidates.none("非部门沟通子流程");
        }
        try {
            HistoricVariableInstance deptName = historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(hpi.getId())
                    .variableName(DeptCommProcessService.VAR_DEPT_NAME)
                    .singleResult();
            if (deptName != null && deptName.getValue() != null
                    && !String.valueOf(deptName.getValue()).isBlank()) {
                String name = String.valueOf(deptName.getValue());
                return new DeptCandidates(List.of(name), "deptName=" + name);
            }
            HistoricVariableInstance deptId = historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(hpi.getId())
                    .variableName(DeptCommProcessService.VAR_DEPT_ID)
                    .singleResult();
            if (deptId != null && deptId.getValue() != null) {
                String raw = String.valueOf(deptId.getValue());
                return new DeptCandidates(List.of(groupName(raw, groupNameCache)), "deptId=" + raw);
            }
            return DeptCandidates.none("deptName 与 deptId 均未写入变量表");
        } catch (RuntimeException ex) {
            // 变量缺失时退化为无候选信息，不影响其它节点；但异常本身要留痕，
            // 否则“读不到变量”和“变量不存在”在页面上是同一个「待认领」。
            LOG.warn("读取部门沟通候选变量失败，processInstanceId=" + hpi.getId(), ex);
            return DeptCandidates.none("读取变量异常：" + ex.getMessage());
        }
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
        // 仅 TARGET_TYPE_ = DEPT 时有值，按 deptProcInstId 原样外发，前端据此展示部门沟通实例。
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
     * 部门沟通：任务在独立子流程实例里，主实例的历史任务查询取不到，
     * 只能由业务记录 {@link BizCommTask} 还原成与 {@link #toCommTaskVO} 同构的 VO。
     *
     * <p>子流程里的 {@code dept_handle} 历史任务用来补办理人、开始/结束时间；
     * 查不到（旧数据实例已归档或物理删除）时退回业务记录的创建时间，
     * 前端仍能正常展示，不会因为少一个字段整行消失。</p>
     */
    private Map<String, Object> toDeptCommVO(BizCommTask record, String initiator, String sourceKey) {
        HistoricTaskInstance deptTask = null;
        if (record.getDeptProcInstId() != null) {
            try {
                List<HistoricTaskInstance> tasks = historyService.createHistoricTaskInstanceQuery()
                        .processInstanceId(record.getDeptProcInstId())
                        .taskDefinitionKey(BizCommTask.DEPT_TASK_KEY)
                        .list();
                // 一个子流程只会有一个 dept_handle；循环/重试可能留下多条，
                // 优先取仍在办理中的那条，否则取结束时间最晚的
                for (HistoricTaskInstance t : tasks) {
                    if (deptTask == null) {
                        deptTask = t;
                    } else if (deptTask.getEndTime() != null
                            && (t.getEndTime() == null || t.getEndTime().after(deptTask.getEndTime()))) {
                        deptTask = t;
                    }
                }
            } catch (RuntimeException ignore) {
                // 子流程实例已被清理时不能拖垮整张跟踪图，退回业务记录展示
            }
        }
        // 部门子流程的发起人以子流程实例上的 START_USER_ID_ 为准（再发起时是实际操作人），
        // 它比“源待办处理人”更准确；取不到时再用源待办处理人兜底
        if (record.getDeptProcInstId() != null) {
            try {
                HistoricProcessInstance sub = historyService.createHistoricProcessInstanceQuery()
                        .processInstanceId(record.getDeptProcInstId()).singleResult();
                if (sub != null && sub.getStartUserId() != null && !sub.getStartUserId().isBlank()) {
                    initiator = sub.getStartUserId();
                }
            } catch (RuntimeException ignore) {
                // 同上：发起人缺失不影响记录本身展示
            }
        }

        Date startTime = deptTask != null && deptTask.getStartTime() != null
                ? deptTask.getStartTime() : record.getCreateTime();
        String bizStatus = record.getStatus();

        Map<String, Object> vo = new LinkedHashMap<>();
        // 任务ID 缺失（回填前的历史数据）时用业务记录ID 兜底：前端只要求稳定唯一键
        vo.put("taskId", record.getCamundaTaskId() != null ? record.getCamundaTaskId() : record.getId());
        vo.put("taskDefKey", BizCommTask.buildCommTaskKey(sourceKey));
        vo.put("name", deptTask != null && deptTask.getName() != null ? deptTask.getName() : "部门沟通");
        // 部门沟通的办理人由部门成员认领，未认领时为空；前端回落显示 targetName
        vo.put("assignee", deptTask == null ? null : deptTask.getAssignee());
        vo.put("initiator", initiator);
        vo.put("targetId", record.getTargetId());
        vo.put("targetName", record.getTargetName());
        vo.put("targetType", record.getTargetType());
        vo.put("isFollowUp", record.getParentCommTaskId() != null);
        vo.put("parentCommTaskId", record.getParentCommTaskId());
        vo.put("rootTaskId", record.getRootTaskId() != null
                ? record.getRootTaskId() : record.getSourceTaskId());
        vo.put("camundaTaskId", record.getCamundaTaskId());
        vo.put("deptProcInstId", record.getDeptProcInstId());
        vo.put("status", bizStatus);
        vo.put("pending", BizCommTask.STATUS_PENDING.equals(bizStatus));
        // 部门任务还在、但没人认领：沟通面板与节点明细用同一口径，
        // 否则一边写「待认领」、另一边只显示部门名，仍会被当成“已有人在处理”。
        boolean deptUnassigned = deptTask != null && deptTask.getEndTime() == null
                && (deptTask.getAssignee() == null || deptTask.getAssignee().isBlank());
        vo.put("pendingClaim", deptUnassigned);
        List<String> candidateNames = new ArrayList<>();
        if (record.getTargetName() != null && !record.getTargetName().isBlank()) {
            candidateNames.add(record.getTargetName());
        } else if (record.getTargetId() != null && !record.getTargetId().isBlank()) {
            candidateNames.add(record.getTargetId());
        }
        vo.put("candidateGroupNames", candidateNames);
        vo.put("withdrawn", false);
        vo.put("content", record.getContent());
        vo.put("replyContent", record.getReplyContent());
        vo.put("replyUserId", record.getReplyUserId());
        vo.put("replyTime", record.getReplyTime());
        vo.put("withdrawTime", record.getWithdrawTime());
        vo.put("startTime", startTime);
        vo.put("endTime", deptTask == null ? null : deptTask.getEndTime());
        vo.put("createTime", record.getCreateTime() != null ? record.getCreateTime() : startTime);
        vo.put("durationInMillis", deptTask == null ? null : deptTask.getDurationInMillis());
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
        if (hpi == null) {
            return "审批流程";
        }
        String liveTitle = runningTitle(hpi.getId());
        if (liveTitle != null) {
            return liveTitle;
        }
        try {
            List<HistoricVariableInstance> titles = historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(hpi.getId()).variableName("title").list();
            for (HistoricVariableInstance title : titles) {
                if (title.getValue() != null) {
                    return String.valueOf(title.getValue());
                }
            }
        } catch (RuntimeException ignore) {
            // 历史变量不可用
        }
        ProcessDefinition pd = definition(hpi.getProcessDefinitionId());
        return pd == null ? "审批流程" : pd.getName();
    }

    /**
     * 运行期 title 变量；实例不在运行期时返回 null，由调用方退到历史变量。
     *
     * <p>不能用 {@code runtimeService.getVariables(executionId)}：实例结束后（已归档的子流程、
     * 被删除的实例）引擎会抛 {@code NullValueException: execution ... doesn't exist}。
     * 这个异常发生在 command context 内部，引擎收尾时会打印 ENGINE-16004 并把当前
     * Spring 事务标记为 rollback-only，因此即使外面 catch 住，请求仍会在提交阶段以 500 结束
     * ——表现就是「下钻一个已结束的子流程，接口直接报错」。</p>
     *
     * <p>变量查询在实例不存在时只返回 null，不抛异常、不污染事务、不产生 ERROR 日志。</p>
     */
    private String runningTitle(String processInstanceId) {
        try {
            // VariableInstanceQuery 只有 processInstanceIdIn（无单值重载）；
            // 同名变量可能存在于多个执行作用域，故用 list 而不是 singleResult，避免多结果异常
            List<VariableInstance> titles = runtimeService.createVariableInstanceQuery()
                    .processInstanceIdIn(processInstanceId)
                    .variableName("title")
                    .list();
            for (VariableInstance title : titles) {
                if (title.getValue() != null) {
                    return String.valueOf(title.getValue());
                }
            }
        } catch (RuntimeException ex) {
            // 引擎查询失败时退回历史变量，不让跟踪图因为标题而打不开
            LOG.debug("读取运行期 title 失败，processInstanceId=" + processInstanceId, ex);
        }
        return null;
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

    /** 一个任务实例的候选范围与待认领状态 */
    private static class CandidateInfo {
        private final Set<String> groupIds = new LinkedHashSet<>();
        private final Set<String> groupNames = new LinkedHashSet<>();
        private final Set<String> userIds = new LinkedHashSet<>();
        private boolean pendingClaim;
    }

    /**
     * 候选部门的兜底结果：名称列表 + 值取自哪一级。
     *
     * <p>单独带 {@code source} 是为了让诊断日志能区分“身份链接给出来的候选”和
     * “变量兜底补上的候选” —— 后者只解决展示，任务本身未必可认领。</p>
     */
    private static class DeptCandidates {
        private final List<String> names;
        private final String source;

        private DeptCandidates(List<String> names, String source) {
            this.names = names == null ? Collections.emptyList() : names;
            this.source = source;
        }

        private static DeptCandidates none(String reason) {
            return new DeptCandidates(Collections.emptyList(), reason);
        }
    }
}
