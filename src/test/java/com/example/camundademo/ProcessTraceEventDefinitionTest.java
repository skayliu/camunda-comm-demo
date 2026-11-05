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
 * 跟踪图必须能区分事件的“具体类型”。
 *
 * <p>回归点：BPMN 里定时开始事件的元素名仍然是 {@code startEvent}，真正的类型信息在
 * 子元素 {@code <timerEventDefinition/>} 上。只按元素名解析，前端拿不到任何事件定义，
 * 只能画一个通用空圈，看起来和普通开始事件完全一样。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:traceeventdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class ProcessTraceEventDefinitionTest {

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="Definitions_trace_event" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="traceEventTest" name="事件图标测试" isExecutable="true"
                            camunda:historyTimeToLive="180">
                <bpmn:startEvent id="timerStart" name="定时触发">
                  <bpmn:outgoing>f_start_approve</bpmn:outgoing>
                  <bpmn:timerEventDefinition id="timerDef">
                    <bpmn:timeDuration>PT1H</bpmn:timeDuration>
                  </bpmn:timerEventDefinition>
                </bpmn:startEvent>
                <bpmn:userTask id="approve" name="审批" camunda:assignee="manager">
                  <bpmn:incoming>f_start_approve</bpmn:incoming>
                  <bpmn:outgoing>f_approve_end</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:endEvent id="end" name="结束">
                  <bpmn:incoming>f_approve_end</bpmn:incoming>
                </bpmn:endEvent>

                <bpmn:sequenceFlow id="f_start_approve" sourceRef="timerStart" targetRef="approve"/>
                <bpmn:sequenceFlow id="f_approve_end" sourceRef="approve" targetRef="end"/>
              </bpmn:process>

              <bpmndi:BPMNDiagram id="BPMNDiagram_trace_event">
                <bpmndi:BPMNPlane id="BPMNPlane_trace_event" bpmnElement="traceEventTest">
                  <bpmndi:BPMNShape id="Shape_timerStart" bpmnElement="timerStart">
                    <dc:Bounds x="40" y="122" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_approve" bpmnElement="approve">
                    <dc:Bounds x="140" y="100" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_end" bpmnElement="end">
                    <dc:Bounds x="310" y="122" width="36" height="36"/>
                  </bpmndi:BPMNShape>

                  <bpmndi:BPMNEdge id="Edge_f_start_approve" bpmnElement="f_start_approve">
                    <di:waypoint x="76" y="140"/><di:waypoint x="140" y="140"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f_approve_end" bpmnElement="f_approve_end">
                    <di:waypoint x="240" y="140"/><di:waypoint x="310" y="140"/>
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
    void timerStartEventShouldExposeEventDefinition() {
        repositoryService.createDeployment()
                .addString("trace-event-test.bpmn", BPMN)
                .name("trace-event-test")
                .deploy();

        // 手动启动：定时开始事件的 timer job 不会立即触发，这里只为拿到一个可跟踪的实例
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("traceEventTest");

        Map<String, Object> trace = processTraceService.trace(instance.getId());

        assertEquals("timer", nodeField(trace, "timerStart", "eventDefinition"),
                "定时开始事件必须解析出 timer，前端才能画时钟图标");
        assertEquals("定时开始事件", nodeField(trace, "timerStart", "typeLabel"),
                "节点类型文案应带上事件定义前缀");
        assertEquals(null, nodeField(trace, "end", "eventDefinition"),
                "普通结束事件没有事件定义，不应凭空造一个");
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
