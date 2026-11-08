package com.example.camundademo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.camundademo.service.ProcessTraceService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 父子实例各持同名 {@code title} 时，跟踪图标题必须严格按实例隔离：
 * 子流程页面显示子标题、父流程页面显示父标题、「返回上级」显示父标题。
 *
 * <p><b>为什么要单独守这一条</b>：修 ENGINE-16004 时把标题读取改成了
 * 「运行期变量 → 历史变量 → 流程定义名」。这条链路里 {@code processTitle(hpi)} 一旦
 * 误用父实例 ID、或退化成不带实例过滤的全局 {@code variableName("title")} 查询，
 * <b>返回的标题依然非空、依然像模像样</b>，只有父子实例恰好同名同值时才看不出来。
 * 单实例用例（{@code RunningInstanceVariableTest}、{@code ProcessTraceCallActivityDrillTest}）
 * 全都会照常通过——它们没有第二份同名变量可以串。</p>
 *
 * <p><b>怎么制造真实的同名现场</b>：调用活动上写 {@code <camunda:in variables="all"/>}，
 * 子实例启动时会复制父实例的全部变量（含 {@code title}），因此父子一开始就持有同名同值的变量；
 * 随后子流程在自己的任务上把 title 改成独占值，两者名字相同、值不同。
 * 这正是最容易串、也最容易被误判为正常的场景。</p>
 *
 * <p><b>别写成 {@code camunda:inheritVariables="true"}</b>：这不是 Camunda 7 的扩展属性，
 * 7.24 上会被<b>静默忽略</b>——不报错、不告警，只是子实例完全没有变量，
 * 于是“同名现场”根本造不出来。变量传递只有 {@code camunda:in} / {@code camunda:out} 这一套。</p>
 *
 * <p><b>为什么让子流程先结束</b>：此时父实例仍在运行、子实例已归档，两个标题分别来自
 * 运行期变量与历史变量。若实现把「父运行期标题」当成子实例标题返回，就是本条用例要拦的错；
 * 同时也顺带覆盖「已结束子流程 + 该子流程有 title」这一组合——{@code ProcessTraceCallActivityDrillTest}
 * 里已结束的子流程没有 title，走的是回落到定义名那条分支，覆盖不到这里。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 独立内存库，避免与其它用例共享实例
        "spring.datasource.url=jdbc:h2:mem:parentchildtitledb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class ParentChildTitleIsolationTest {

    /** 子流程：两个用户任务，便于在中间把 title 改成独占值，再跑到结束 */
    private static final String CHILD_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="Definitions_pc_child" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="pcChildProcess" name="父子同名子流程" isExecutable="true"
                            camunda:historyTimeToLive="180">
                <bpmn:startEvent id="childStart" name="开始">
                  <bpmn:outgoing>cf1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:userTask id="childApprove" name="子流程审批" camunda:assignee="childUser">
                  <bpmn:incoming>cf1</bpmn:incoming>
                  <bpmn:outgoing>cf2</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:userTask id="childReview" name="子流程复核" camunda:assignee="childUser">
                  <bpmn:incoming>cf2</bpmn:incoming>
                  <bpmn:outgoing>cf3</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:endEvent id="childEnd" name="结束">
                  <bpmn:incoming>cf3</bpmn:incoming>
                </bpmn:endEvent>
                <bpmn:sequenceFlow id="cf1" sourceRef="childStart" targetRef="childApprove"/>
                <bpmn:sequenceFlow id="cf2" sourceRef="childApprove" targetRef="childReview"/>
                <bpmn:sequenceFlow id="cf3" sourceRef="childReview" targetRef="childEnd"/>
              </bpmn:process>

              <bpmndi:BPMNDiagram id="BPMNDiagram_pc_child">
                <bpmndi:BPMNPlane id="BPMNPlane_pc_child" bpmnElement="pcChildProcess">
                  <bpmndi:BPMNShape id="Shape_childStart" bpmnElement="childStart">
                    <dc:Bounds x="40" y="82" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_childApprove" bpmnElement="childApprove">
                    <dc:Bounds x="120" y="60" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_childReview" bpmnElement="childReview">
                    <dc:Bounds x="270" y="60" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_childEnd" bpmnElement="childEnd">
                    <dc:Bounds x="420" y="82" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNEdge id="Edge_cf1" bpmnElement="cf1">
                    <di:waypoint x="76" y="100"/><di:waypoint x="120" y="100"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_cf2" bpmnElement="cf2">
                    <di:waypoint x="220" y="100"/><di:waypoint x="270" y="100"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_cf3" bpmnElement="cf3">
                    <di:waypoint x="370" y="100"/><di:waypoint x="420" y="100"/>
                  </bpmndi:BPMNEdge>
                </bpmndi:BPMNPlane>
              </bpmndi:BPMNDiagram>
            </bpmn:definitions>
            """;

    /** 父流程：apply → callActivity（继承变量）→ parentReview → end */
    private static final String PARENT_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="Definitions_pc_parent" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="pcParentProcess" name="父子同名主流程" isExecutable="true"
                            camunda:historyTimeToLive="180">
                <bpmn:startEvent id="start" name="开始">
                  <bpmn:outgoing>f1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:userTask id="apply" name="发起申请" camunda:assignee="parentUser">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:callActivity id="call" name="调用子流程" calledElement="pcChildProcess">
                  <bpmn:extensionElements>
                    <!-- 显式把父实例变量整体传入子实例：camunda:inheritVariables 在 7.24 上未生效，
                         用 variables="all" 这个语义更明确、各版本都稳定的写法 -->
                    <camunda:in variables="all"/>
                  </bpmn:extensionElements>
                  <bpmn:incoming>f2</bpmn:incoming>
                  <bpmn:outgoing>f3</bpmn:outgoing>
                </bpmn:callActivity>
                <bpmn:userTask id="parentReview" name="主流程复核" camunda:assignee="parentUser">
                  <bpmn:incoming>f3</bpmn:incoming>
                  <bpmn:outgoing>f4</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:endEvent id="end" name="结束">
                  <bpmn:incoming>f4</bpmn:incoming>
                </bpmn:endEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="apply"/>
                <bpmn:sequenceFlow id="f2" sourceRef="apply" targetRef="call"/>
                <bpmn:sequenceFlow id="f3" sourceRef="call" targetRef="parentReview"/>
                <bpmn:sequenceFlow id="f4" sourceRef="parentReview" targetRef="end"/>
              </bpmn:process>

              <bpmndi:BPMNDiagram id="BPMNDiagram_pc_parent">
                <bpmndi:BPMNPlane id="BPMNPlane_pc_parent" bpmnElement="pcParentProcess">
                  <bpmndi:BPMNShape id="Shape_start" bpmnElement="start">
                    <dc:Bounds x="40" y="82" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_apply" bpmnElement="apply">
                    <dc:Bounds x="110" y="60" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_call" bpmnElement="call">
                    <dc:Bounds x="250" y="60" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_parentReview" bpmnElement="parentReview">
                    <dc:Bounds x="390" y="60" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_end" bpmnElement="end">
                    <dc:Bounds x="530" y="82" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNEdge id="Edge_f1" bpmnElement="f1">
                    <di:waypoint x="76" y="100"/><di:waypoint x="110" y="100"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f2" bpmnElement="f2">
                    <di:waypoint x="210" y="100"/><di:waypoint x="250" y="100"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f3" bpmnElement="f3">
                    <di:waypoint x="350" y="100"/><di:waypoint x="390" y="100"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f4" bpmnElement="f4">
                    <di:waypoint x="490" y="100"/><di:waypoint x="530" y="100"/>
                  </bpmndi:BPMNEdge>
                </bpmndi:BPMNPlane>
              </bpmndi:BPMNDiagram>
            </bpmn:definitions>
            """;

    private static final String PARENT_TITLE = "标题-父流程-发起";
    private static final String PARENT_TITLE_AFTER = "标题-父流程-已提交";
    private static final String CHILD_TITLE = "标题-子流程-独占";

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private ProcessTraceService processTraceService;

    @Test
    @SuppressWarnings("unchecked")
    void parentAndChildTitlesShouldNotLeakAcrossInstances() {
        repositoryService.createDeployment()
                .addString("pc-child.bpmn", CHILD_BPMN)
                .name("pc-child").deploy();
        repositoryService.createDeployment()
                .addString("pc-parent.bpmn", PARENT_BPMN)
                .name("pc-parent").deploy();

        ProcessInstance parent = runtimeService.startProcessInstanceByKey(
                "pcParentProcess", Map.of("title", PARENT_TITLE, "applicant", "parentUser"));
        String parentId = parent.getId();

        Task apply = taskService.createTaskQuery().processInstanceId(parentId)
                .taskDefinitionKey("apply").singleResult();
        assertNotNull(apply, "父流程应停在发起申请节点");
        // 父标题在发起阶段就更新一次：子实例继承的是「更新后」的值，两者起点相同
        taskService.complete(apply.getId(), Map.of("title", PARENT_TITLE_AFTER));

        ProcessInstance child = runtimeService.createProcessInstanceQuery()
                .superProcessInstanceId(parentId).singleResult();
        assertNotNull(child, "调用活动应已调起子流程实例");
        String childId = child.getId();

        // ===== 前置条件：确认真的造出了「同名变量」=====
        assertEquals(PARENT_TITLE_AFTER, runtimeService.getVariable(childId, "title"),
                "子实例应通过 camunda:in variables=\"all\" 继承父实例的 title；若不成立，本用例测不到同名场景");
        assertEquals(PARENT_TITLE_AFTER, runtimeService.getVariable(parentId, "title"),
                "父实例运行期变量应保持自己的值");

        Task childApprove = taskService.createTaskQuery().processInstanceId(childId)
                .taskDefinitionKey("childApprove").singleResult();
        assertNotNull(childApprove, "子流程应停在子流程审批节点");
        // 子流程改成独占标题：名字仍是 title，值与父流程不同
        taskService.complete(childApprove.getId(), Map.of("title", CHILD_TITLE));

        Task childReview = taskService.createTaskQuery().processInstanceId(childId)
                .taskDefinitionKey("childReview").singleResult();
        assertNotNull(childReview, "子流程应已推进到子流程复核节点");
        taskService.complete(childReview.getId());

        // ===== 前置条件：子已结束，父仍在运行 → 两个标题分别来自历史变量与运行期变量 =====
        assertNull(runtimeService.createProcessInstanceQuery().processInstanceId(childId).singleResult(),
                "子流程应已结束");
        assertNotNull(taskService.createTaskQuery().processInstanceId(parentId)
                        .taskDefinitionKey("parentReview").singleResult(),
                "父流程应已回到主流程复核节点并仍在运行");
        assertNull(runtimeService.createVariableInstanceQuery()
                        .processInstanceIdIn(childId).variableName("title").singleResult(),
                "子实例已结束，运行期不应再有它的 title 变量");
        assertEquals(CHILD_TITLE, historicTitle(childId),
                "历史变量中子实例的 title 应为独占值");
        assertEquals(PARENT_TITLE_AFTER, historicTitle(parentId),
                "历史变量中父实例的 title 仍应是父自己的值");

        // ===== 关键断言：两个页面的标题各取各的，返回上级取父标题 =====
        ch.qos.logback.classic.Logger engineLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.camunda.bpm.engine.context");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        engineLogger.addAppender(appender);
        Map<String, Object> parentTrace;
        Map<String, Object> childTrace;
        try {
            parentTrace = processTraceService.trace(parentId);
            childTrace = processTraceService.trace(childId);
        } finally {
            engineLogger.detachAppender(appender);
        }

        assertEquals("RUNNING", parentTrace.get("state"));
        assertEquals(PARENT_TITLE_AFTER, parentTrace.get("processTitle"),
                "父流程跟踪图标题必须是父自己的标题，不能被子实例的 title 覆盖");

        assertEquals("FINISHED", childTrace.get("state"));
        assertEquals(CHILD_TITLE, childTrace.get("processTitle"),
                "子流程跟踪图标题必须是子实例的 title；显示父标题说明标题按实例过滤失效");

        assertEquals(parentId, childTrace.get("parentProcessInstanceId"),
                "子流程应能反查到父实例，前端据此显示「返回上级」");
        assertEquals(PARENT_TITLE_AFTER, childTrace.get("parentProcessTitle"),
                "「返回上级」必须显示父标题；显示子标题说明 parentRef 传错了实例");

        // ===== 下钻入口：ID 指向子实例，且不能把父标题当成子标题展示 =====
        Map<String, Object> callNode = node(parentTrace, "call");
        List<Map<String, Object>> called =
                (List<Map<String, Object>>) callNode.get("calledInstances");
        assertNotNull(called, "调用活动节点缺少 calledInstances");
        assertEquals(1, called.size(), "应恰好调起一个子流程实例");
        assertEquals(childId, called.get(0).get("processInstanceId"),
                "下钻列表必须给出子实例 ID");
        assertEquals("FINISHED", called.get(0).get("state"),
                "子实例已结束，下钻列表状态应为 FINISHED");
        assertNotEquals(PARENT_TITLE_AFTER, called.get(0).get("processTitle"),
                "下钻列表不能显示父实例标题，否则用户会误以为将要打开的是父流程");

        // ===== 引擎日志：不得为取标题付出 ERROR 代价 =====
        List<ILoggingEvent> engineErrors = appender.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR))
                .toList();
        assertTrue(engineErrors.isEmpty(),
                "父子实例读取标题不应触发引擎 ERROR，实际："
                        + engineErrors.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    private Object historicTitle(String processInstanceId) {
        List<HistoricVariableInstance> titles = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId).variableName("title").list();
        assertEquals(1, titles.size(),
                "实例 " + processInstanceId + " 的历史 title 应恰好一条，实际：" + titles.size());
        return titles.get(0).getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> node(Map<String, Object> trace, String nodeId) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) trace.get("nodes");
        assertNotNull(nodes, "跟踪数据缺少节点");
        for (Map<String, Object> node : nodes) {
            if (nodeId.equals(node.get("nodeId"))) {
                return node;
            }
        }
        throw new AssertionError("未找到节点 " + nodeId);
    }
}
