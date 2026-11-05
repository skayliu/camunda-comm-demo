package com.example.camundademo;

import com.example.camundademo.service.ProcessTraceService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调用子流程下钻：跟踪图必须给出被调用实例 ID，并且权限能沿调用链继承。
 *
 * <p>父子关系只存在于 {@code ACT_HI_ACTINST.CALL_PROC_INST_ID_}：
 * {@code ACT_HI_PROCINST} 没有父实例字段，被调起实例的 startUserId 也常为空。
 * 因此下钻数据必须由历史活动补齐，前端才有 ID 可加载子实例的跟踪图。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:tracecalldrilldb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class ProcessTraceCallActivityDrillTest {

    private static final String CHILD_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="Definitions_drill_child" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="traceDrillChild" name="被调用子流程" isExecutable="true"
                            camunda:historyTimeToLive="180">
                <bpmn:startEvent id="childStart" name="开始">
                  <bpmn:outgoing>cf1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:receiveTask id="childWait" name="等待外部消息">
                  <bpmn:incoming>cf1</bpmn:incoming>
                  <bpmn:outgoing>cf2</bpmn:outgoing>
                </bpmn:receiveTask>
                <bpmn:endEvent id="childEnd" name="结束">
                  <bpmn:incoming>cf2</bpmn:incoming>
                </bpmn:endEvent>
                <bpmn:sequenceFlow id="cf1" sourceRef="childStart" targetRef="childWait"/>
                <bpmn:sequenceFlow id="cf2" sourceRef="childWait" targetRef="childEnd"/>
              </bpmn:process>

              <bpmndi:BPMNDiagram id="BPMNDiagram_drill_child">
                <bpmndi:BPMNPlane id="BPMNPlane_drill_child" bpmnElement="traceDrillChild">
                  <bpmndi:BPMNShape id="Shape_childStart" bpmnElement="childStart">
                    <dc:Bounds x="40" y="82" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_childWait" bpmnElement="childWait">
                    <dc:Bounds x="120" y="60" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_childEnd" bpmnElement="childEnd">
                    <dc:Bounds x="260" y="82" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNEdge id="Edge_cf1" bpmnElement="cf1">
                    <di:waypoint x="76" y="100"/><di:waypoint x="120" y="100"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_cf2" bpmnElement="cf2">
                    <di:waypoint x="220" y="100"/><di:waypoint x="260" y="100"/>
                  </bpmndi:BPMNEdge>
                </bpmndi:BPMNPlane>
              </bpmndi:BPMNDiagram>
            </bpmn:definitions>
            """;

    private static final String PARENT_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="Definitions_drill_parent" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="traceDrillParent" name="主流程" isExecutable="true"
                            camunda:historyTimeToLive="180">
                <bpmn:startEvent id="start" name="开始">
                  <bpmn:outgoing>f1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:userTask id="apply" name="发起申请" camunda:assignee="parentUser">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:callActivity id="call" name="调用子流程" calledElement="traceDrillChild">
                  <bpmn:incoming>f2</bpmn:incoming>
                  <bpmn:outgoing>f3</bpmn:outgoing>
                </bpmn:callActivity>
                <bpmn:endEvent id="end" name="结束">
                  <bpmn:incoming>f3</bpmn:incoming>
                </bpmn:endEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="apply"/>
                <bpmn:sequenceFlow id="f2" sourceRef="apply" targetRef="call"/>
                <bpmn:sequenceFlow id="f3" sourceRef="call" targetRef="end"/>
              </bpmn:process>

              <bpmndi:BPMNDiagram id="BPMNDiagram_drill_parent">
                <bpmndi:BPMNPlane id="BPMNPlane_drill_parent" bpmnElement="traceDrillParent">
                  <bpmndi:BPMNShape id="Shape_start" bpmnElement="start">
                    <dc:Bounds x="40" y="82" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_apply" bpmnElement="apply">
                    <dc:Bounds x="110" y="60" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_call" bpmnElement="call">
                    <dc:Bounds x="250" y="60" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_end" bpmnElement="end">
                    <dc:Bounds x="390" y="82" width="36" height="36"/>
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
                </bpmndi:BPMNPlane>
              </bpmndi:BPMNDiagram>
            </bpmn:definitions>
            """;

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
    void callActivityShouldExposeCalledInstanceForDrillDown() {
        repositoryService.createDeployment()
                .addString("trace-drill-child.bpmn", CHILD_BPMN)
                .name("trace-drill-child")
                .deploy();
        repositoryService.createDeployment()
                .addString("trace-drill-parent.bpmn", PARENT_BPMN)
                .name("trace-drill-parent")
                .deploy();

        ProcessInstance parent = runtimeService.startProcessInstanceByKey("traceDrillParent");
        Task task = taskService.createTaskQuery().processInstanceId(parent.getId()).singleResult();
        assertNotNull(task, "父流程应停在发起申请节点");
        taskService.complete(task.getId());

        // 父流程此刻停在 callActivity 上，子流程停在 receiveTask
        ProcessInstance child = runtimeService.createProcessInstanceQuery()
                .superProcessInstanceId(parent.getId()).singleResult();
        assertNotNull(child, "调用活动应已调起子流程实例");

        Map<String, Object> parentTrace = processTraceService.trace(parent.getId());
        Map<String, Object> callNode = node(parentTrace, "call");

        List<Map<String, Object>> called =
                (List<Map<String, Object>>) callNode.get("calledInstances");
        assertNotNull(called, "调用活动节点缺少 calledInstances");
        assertEquals(1, called.size(), "应恰好调起一个子流程实例");
        Map<String, Object> calledInstance = called.get(0);
        assertEquals(child.getId(), calledInstance.get("processInstanceId"),
                "calledInstances 必须给出真实的子实例 ID，前端据此下钻");
        assertEquals(child.getId(), callNode.get("calledProcessInstanceId"));
        assertEquals("RUNNING", calledInstance.get("state"));
        assertEquals("traceDrillChild", calledInstance.get("processDefinitionKey"));
        assertEquals("被调用子流程", calledInstance.get("processDefinitionName"));

        // 子实例的跟踪图要能反向看到父实例，刷新/直接打开子流程时仍可提示返回上级
        Map<String, Object> childTrace = processTraceService.trace(child.getId());
        assertEquals(parent.getId(), childTrace.get("parentProcessInstanceId"));
        assertEquals("被调用子流程", childTrace.get("processDefinitionName"));
        assertEquals("ACTIVE", node(childTrace, "childWait").get("status"));

        // 权限沿调用链继承：parentUser 只在父流程办过任务，子流程里没有任何任务
        assertEquals(0, historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(child.getId()).taskAssignee("parentUser").count());
        assertTrue(processTraceService.canView(child.getId(), "parentUser"),
                "父流程办理人必须能下钻查看子流程，否则点击 + 会 403");
        assertFalse(processTraceService.canView(child.getId(), "outsider"),
                "与调用链无关的用户不可查看");
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
