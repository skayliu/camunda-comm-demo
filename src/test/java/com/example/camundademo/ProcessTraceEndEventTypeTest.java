package com.example.camundademo;

import com.example.camundademo.service.ProcessTraceService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 跟踪图必须能区分不同「结束事件」。
 *
 * <p>回归点：普通结束事件在 BPMN 里的元素名同样是 {@code endEvent}，只有子元素
 * {@code <terminateEventDefinition/>}、{@code <errorEventDefinition/>} 等才带真实类型。
 * 之前前端对所有结束事件无条件画内部实心圆（那是 terminate 的专属标记），
 * 结果普通结束事件也被画成实心圆，错误/信号/消息等图标又被实心底色盖住，全部长得一样。</p>
 *
 * <p>这里刻意只启动流程、不执行：未执行的节点在 ACT_HI_ACTINST 里没有任何记录，
 * 类型只能来自 BPMN 定义，正好验证「未开始节点也能画出正确图标」。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:traceendeventdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class ProcessTraceEndEventTypeTest {

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="Definitions_trace_end_event" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:message id="Msg_1" name="结束消息"/>
              <bpmn:signal id="Sig_1" name="结束信号"/>
              <bpmn:error id="Err_1" name="结束错误" errorCode="E1"/>
              <bpmn:escalation id="Esc_1" name="结束升级" escalationCode="ES1"/>

              <bpmn:process id="traceEndEventTest" name="结束事件图标测试" isExecutable="true"
                            camunda:historyTimeToLive="180">
                <bpmn:startEvent id="start" name="开始">
                  <bpmn:outgoing>f_start_approve</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:userTask id="approve" name="审批" camunda:assignee="manager">
                  <bpmn:incoming>f_start_approve</bpmn:incoming>
                  <bpmn:outgoing>f_none</bpmn:outgoing>
                  <bpmn:outgoing>f_terminate</bpmn:outgoing>
                  <bpmn:outgoing>f_error</bpmn:outgoing>
                  <bpmn:outgoing>f_signal</bpmn:outgoing>
                  <bpmn:outgoing>f_message</bpmn:outgoing>
                  <bpmn:outgoing>f_escalation</bpmn:outgoing>
                  <bpmn:outgoing>f_compensate</bpmn:outgoing>
                </bpmn:userTask>

                <bpmn:endEvent id="endNone" name="普通结束">
                  <bpmn:incoming>f_none</bpmn:incoming>
                </bpmn:endEvent>
                <bpmn:endEvent id="endTerminate" name="终止结束">
                  <bpmn:incoming>f_terminate</bpmn:incoming>
                  <bpmn:terminateEventDefinition id="Def_terminate"/>
                </bpmn:endEvent>
                <bpmn:endEvent id="endError" name="错误结束">
                  <bpmn:incoming>f_error</bpmn:incoming>
                  <bpmn:errorEventDefinition id="Def_error" errorRef="Err_1"/>
                </bpmn:endEvent>
                <bpmn:endEvent id="endSignal" name="信号结束">
                  <bpmn:incoming>f_signal</bpmn:incoming>
                  <bpmn:signalEventDefinition id="Def_signal" signalRef="Sig_1"/>
                </bpmn:endEvent>
                <bpmn:endEvent id="endMessage" name="消息结束">
                  <bpmn:incoming>f_message</bpmn:incoming>
                  <bpmn:messageEventDefinition id="Def_message" messageRef="Msg_1"/>
                </bpmn:endEvent>
                <bpmn:endEvent id="endEscalation" name="升级结束">
                  <bpmn:incoming>f_escalation</bpmn:incoming>
                  <bpmn:escalationEventDefinition id="Def_escalation" escalationRef="Esc_1"/>
                </bpmn:endEvent>
                <bpmn:endEvent id="endCompensate" name="补偿结束">
                  <bpmn:incoming>f_compensate</bpmn:incoming>
                  <bpmn:compensateEventDefinition id="Def_compensate"/>
                </bpmn:endEvent>

                <bpmn:sequenceFlow id="f_start_approve" sourceRef="start" targetRef="approve"/>
                <bpmn:sequenceFlow id="f_none" sourceRef="approve" targetRef="endNone"/>
                <bpmn:sequenceFlow id="f_terminate" sourceRef="approve" targetRef="endTerminate"/>
                <bpmn:sequenceFlow id="f_error" sourceRef="approve" targetRef="endError"/>
                <bpmn:sequenceFlow id="f_signal" sourceRef="approve" targetRef="endSignal"/>
                <bpmn:sequenceFlow id="f_message" sourceRef="approve" targetRef="endMessage"/>
                <bpmn:sequenceFlow id="f_escalation" sourceRef="approve" targetRef="endEscalation"/>
                <bpmn:sequenceFlow id="f_compensate" sourceRef="approve" targetRef="endCompensate"/>
              </bpmn:process>

              <bpmndi:BPMNDiagram id="BPMNDiagram_trace_end_event">
                <bpmndi:BPMNPlane id="BPMNPlane_trace_end_event" bpmnElement="traceEndEventTest">
                  <bpmndi:BPMNShape id="Shape_start" bpmnElement="start">
                    <dc:Bounds x="40" y="158" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_approve" bpmnElement="approve">
                    <dc:Bounds x="130" y="136" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_endNone" bpmnElement="endNone">
                    <dc:Bounds x="300" y="40" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_endTerminate" bpmnElement="endTerminate">
                    <dc:Bounds x="300" y="100" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_endError" bpmnElement="endError">
                    <dc:Bounds x="300" y="160" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_endSignal" bpmnElement="endSignal">
                    <dc:Bounds x="300" y="220" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_endMessage" bpmnElement="endMessage">
                    <dc:Bounds x="300" y="280" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_endEscalation" bpmnElement="endEscalation">
                    <dc:Bounds x="300" y="340" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_endCompensate" bpmnElement="endCompensate">
                    <dc:Bounds x="300" y="400" width="36" height="36"/>
                  </bpmndi:BPMNShape>

                  <bpmndi:BPMNEdge id="Edge_f_start_approve" bpmnElement="f_start_approve">
                    <di:waypoint x="76" y="176"/><di:waypoint x="130" y="176"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f_none" bpmnElement="f_none">
                    <di:waypoint x="230" y="176"/><di:waypoint x="300" y="58"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f_terminate" bpmnElement="f_terminate">
                    <di:waypoint x="230" y="176"/><di:waypoint x="300" y="118"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f_error" bpmnElement="f_error">
                    <di:waypoint x="230" y="176"/><di:waypoint x="300" y="178"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f_signal" bpmnElement="f_signal">
                    <di:waypoint x="230" y="176"/><di:waypoint x="300" y="238"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f_message" bpmnElement="f_message">
                    <di:waypoint x="230" y="176"/><di:waypoint x="300" y="298"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f_escalation" bpmnElement="f_escalation">
                    <di:waypoint x="230" y="176"/><di:waypoint x="300" y="358"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f_compensate" bpmnElement="f_compensate">
                    <di:waypoint x="230" y="176"/><di:waypoint x="300" y="418"/>
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
    void endEventsOfDifferentTypesShouldBeDistinguishable() {
        repositoryService.createDeployment()
                .addString("trace-end-event-test.bpmn", BPMN)
                .name("trace-end-event-test")
                .deploy();

        ProcessInstance instance = runtimeService.startProcessInstanceByKey("traceEndEventTest");
        Map<String, Object> trace = processTraceService.trace(instance.getId());

        Map<String, String> expectedDefinition = new LinkedHashMap<>();
        expectedDefinition.put("endNone", null);
        expectedDefinition.put("endTerminate", "terminate");
        expectedDefinition.put("endError", "error");
        expectedDefinition.put("endSignal", "signal");
        expectedDefinition.put("endMessage", "message");
        expectedDefinition.put("endEscalation", "escalation");
        expectedDefinition.put("endCompensate", "compensate");

        Map<String, String> expectedLabel = new LinkedHashMap<>();
        expectedLabel.put("endNone", "结束事件");
        expectedLabel.put("endTerminate", "终止结束事件");
        expectedLabel.put("endError", "错误结束事件");
        expectedLabel.put("endSignal", "信号结束事件");
        expectedLabel.put("endMessage", "消息结束事件");
        expectedLabel.put("endEscalation", "升级结束事件");
        expectedLabel.put("endCompensate", "补偿结束事件");

        expectedDefinition.forEach((nodeId, definition) -> {
            assertEquals("endEvent", nodeField(trace, nodeId, "type"),
                    nodeId + " 的元素类型应为 endEvent");
            assertEquals(definition, nodeField(trace, nodeId, "eventDefinition"),
                    nodeId + " 的事件定义解析不正确，前端会画错图标");
            assertEquals(expectedLabel.get(nodeId), nodeField(trace, nodeId, "typeLabel"),
                    nodeId + " 的类型文案应带上事件定义前缀");
        });
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
