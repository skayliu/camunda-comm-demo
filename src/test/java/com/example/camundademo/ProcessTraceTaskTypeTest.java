package com.example.camundademo;

import com.example.camundademo.service.ProcessTraceService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 跟踪图必须能区分任务的具体类型。
 *
 * <p>回归点：此前所有任务在图上都是同一个圆角矩形，用户任务（人工办理）
 * 和服务任务、脚本任务（自动执行）在视觉上完全一样，只能靠悬停文字分辨。
 * 类型只能从 BPMN 元素名解析——未执行过的节点在 ACT_HI_ACTINST 里没有任何记录，
 * 拿不到 ACT_TYPE_。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:tracetasktypedb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class ProcessTraceTaskTypeTest {

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="Definitions_trace_task_type" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="traceTaskTypeTest" name="任务类型图标测试" isExecutable="true"
                            camunda:historyTimeToLive="180">
                <bpmn:startEvent id="start" name="开始">
                  <bpmn:outgoing>f1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:userTask id="apply" name="用户任务" camunda:assignee="manager">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:serviceTask id="svc" name="服务任务" camunda:expression="${true}">
                  <bpmn:incoming>f2</bpmn:incoming>
                  <bpmn:outgoing>f3</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:scriptTask id="script" name="脚本任务" scriptFormat="javascript">
                  <bpmn:incoming>f3</bpmn:incoming>
                  <bpmn:outgoing>f4</bpmn:outgoing>
                  <bpmn:script>1</bpmn:script>
                </bpmn:scriptTask>
                <bpmn:manualTask id="manual" name="人工任务">
                  <bpmn:incoming>f4</bpmn:incoming>
                  <bpmn:outgoing>f5</bpmn:outgoing>
                </bpmn:manualTask>
                <bpmn:businessRuleTask id="rule" name="规则任务" camunda:expression="${true}">
                  <bpmn:incoming>f5</bpmn:incoming>
                  <bpmn:outgoing>f6</bpmn:outgoing>
                </bpmn:businessRuleTask>
                <bpmn:sendTask id="send" name="发送任务" camunda:expression="${true}">
                  <bpmn:incoming>f6</bpmn:incoming>
                  <bpmn:outgoing>f7</bpmn:outgoing>
                </bpmn:sendTask>
                <bpmn:receiveTask id="receive" name="接收任务">
                  <bpmn:incoming>f7</bpmn:incoming>
                  <bpmn:outgoing>f8</bpmn:outgoing>
                </bpmn:receiveTask>
                <bpmn:callActivity id="call" name="调用活动" calledElement="traceTaskTypeTest">
                  <bpmn:incoming>f8</bpmn:incoming>
                  <bpmn:outgoing>f9</bpmn:outgoing>
                </bpmn:callActivity>
                <bpmn:subProcess id="sub" name="子流程">
                  <bpmn:incoming>f9</bpmn:incoming>
                  <bpmn:outgoing>f10</bpmn:outgoing>
                  <bpmn:startEvent id="subStart">
                    <bpmn:outgoing>subF1</bpmn:outgoing>
                  </bpmn:startEvent>
                  <bpmn:userTask id="subTask" name="子任务" camunda:assignee="manager">
                    <bpmn:incoming>subF1</bpmn:incoming>
                    <bpmn:outgoing>subF2</bpmn:outgoing>
                  </bpmn:userTask>
                  <bpmn:endEvent id="subEnd">
                    <bpmn:incoming>subF2</bpmn:incoming>
                  </bpmn:endEvent>
                  <bpmn:sequenceFlow id="subF1" sourceRef="subStart" targetRef="subTask"/>
                  <bpmn:sequenceFlow id="subF2" sourceRef="subTask" targetRef="subEnd"/>
                </bpmn:subProcess>
                <bpmn:endEvent id="end" name="结束">
                  <bpmn:incoming>f10</bpmn:incoming>
                </bpmn:endEvent>

                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="apply"/>
                <bpmn:sequenceFlow id="f2" sourceRef="apply" targetRef="svc"/>
                <bpmn:sequenceFlow id="f3" sourceRef="svc" targetRef="script"/>
                <bpmn:sequenceFlow id="f4" sourceRef="script" targetRef="manual"/>
                <bpmn:sequenceFlow id="f5" sourceRef="manual" targetRef="rule"/>
                <bpmn:sequenceFlow id="f6" sourceRef="rule" targetRef="send"/>
                <bpmn:sequenceFlow id="f7" sourceRef="send" targetRef="receive"/>
                <bpmn:sequenceFlow id="f8" sourceRef="receive" targetRef="call"/>
                <bpmn:sequenceFlow id="f9" sourceRef="call" targetRef="sub"/>
                <bpmn:sequenceFlow id="f10" sourceRef="sub" targetRef="end"/>
              </bpmn:process>

              <bpmndi:BPMNDiagram id="BPMNDiagram_trace_task_type">
                <bpmndi:BPMNPlane id="BPMNPlane_trace_task_type" bpmnElement="traceTaskTypeTest">
                  <bpmndi:BPMNShape id="Shape_start" bpmnElement="start">
                    <dc:Bounds x="40" y="102" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_apply" bpmnElement="apply">
                    <dc:Bounds x="110" y="80" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_svc" bpmnElement="svc">
                    <dc:Bounds x="250" y="80" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_script" bpmnElement="script">
                    <dc:Bounds x="390" y="80" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_manual" bpmnElement="manual">
                    <dc:Bounds x="530" y="80" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_rule" bpmnElement="rule">
                    <dc:Bounds x="670" y="80" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_send" bpmnElement="send">
                    <dc:Bounds x="810" y="80" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_receive" bpmnElement="receive">
                    <dc:Bounds x="950" y="80" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_call" bpmnElement="call">
                    <dc:Bounds x="1090" y="80" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_sub" bpmnElement="sub" isExpanded="true">
                    <dc:Bounds x="1230" y="60" width="180" height="120"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_subStart" bpmnElement="subStart">
                    <dc:Bounds x="1244" y="102" width="30" height="30"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_subTask" bpmnElement="subTask">
                    <dc:Bounds x="1300" y="77" width="90" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_subEnd" bpmnElement="subEnd">
                    <dc:Bounds x="1420" y="102" width="30" height="30"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_end" bpmnElement="end">
                    <dc:Bounds x="1450" y="102" width="36" height="36"/>
                  </bpmndi:BPMNShape>

                  <bpmndi:BPMNEdge id="Edge_f1" bpmnElement="f1">
                    <di:waypoint x="76" y="120"/><di:waypoint x="110" y="120"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f2" bpmnElement="f2">
                    <di:waypoint x="210" y="120"/><di:waypoint x="250" y="120"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f3" bpmnElement="f3">
                    <di:waypoint x="350" y="120"/><di:waypoint x="390" y="120"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f4" bpmnElement="f4">
                    <di:waypoint x="490" y="120"/><di:waypoint x="530" y="120"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f5" bpmnElement="f5">
                    <di:waypoint x="630" y="120"/><di:waypoint x="670" y="120"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f6" bpmnElement="f6">
                    <di:waypoint x="770" y="120"/><di:waypoint x="810" y="120"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f7" bpmnElement="f7">
                    <di:waypoint x="910" y="120"/><di:waypoint x="950" y="120"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f8" bpmnElement="f8">
                    <di:waypoint x="1050" y="120"/><di:waypoint x="1090" y="120"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f9" bpmnElement="f9">
                    <di:waypoint x="1190" y="120"/><di:waypoint x="1230" y="120"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f10" bpmnElement="f10">
                    <di:waypoint x="1410" y="120"/><di:waypoint x="1450" y="120"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_subF1" bpmnElement="subF1">
                    <di:waypoint x="1274" y="117"/><di:waypoint x="1300" y="117"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_subF2" bpmnElement="subF2">
                    <di:waypoint x="1390" y="117"/><di:waypoint x="1420" y="117"/>
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
    private ProcessTraceService processTraceService;

    @Test
    void taskTypesShouldBeDistinguishedByBpmnElement() {
        repositoryService.createDeployment()
                .addString("trace-task-type-test.bpmn", BPMN)
                .name("trace-task-type-test")
                .deploy();

        // 启动后停在第一个用户任务，后面的自动节点尚未执行：
        // 这正好验证“未执行的节点也能给出类型”，而 ACT_HI_ACTINST 此时没有它们的记录。
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("traceTaskTypeTest");
        Map<String, Object> trace = processTraceService.trace(instance.getId());

        assertType(trace, "apply", "userTask", "用户任务");
        assertType(trace, "svc", "serviceTask", "服务任务");
        assertType(trace, "script", "scriptTask", "脚本任务");
        assertType(trace, "manual", "manualTask", "人工任务");
        assertType(trace, "rule", "businessRuleTask", "规则任务");
        assertType(trace, "send", "sendTask", "发送任务");
        assertType(trace, "receive", "receiveTask", "接收任务");
        assertType(trace, "call", "callActivity", "调用活动");
        assertType(trace, "sub", "subProcess", "子流程");
    }

    private void assertType(Map<String, Object> trace, String nodeId, String type, String label) {
        assertEquals(type, nodeField(trace, nodeId, "type"), nodeId + " 的 BPMN 元素类型不正确");
        assertEquals(label, nodeField(trace, nodeId, "typeLabel"), nodeId + " 的类型文案不正确");
    }

    @SuppressWarnings("unchecked")
    private Object nodeField(Map<String, Object> trace, String nodeId, String field) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) trace.get("nodes");
        assertNotNull(nodes, "跟踪数据缺少节点");
        for (Map<String, Object> node : nodes) {
            if (nodeId.equals(node.get("nodeId"))) {
                return node.get(field);
            }
        }
        throw new AssertionError("未找到节点 " + nodeId);
    }
}
