package com.example.camundademo;

import com.example.camundademo.service.ProcessTraceService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跟踪图必须能区分活动的多实例类型。
 *
 * <p>回归点：多实例活动在 BPMN 里的元素名仍是 userTask / serviceTask，历史表里
 * 多实例体记作 {@code review#multiInstanceBody}，各实例又记回原 activityId，
 * 两者都看不出「是否多实例、是并行还是顺序」。唯一可靠来源是
 * {@code <multiInstanceLoopCharacteristics isSequential="…"/>}。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:tracemultidb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class ProcessTraceMultiInstanceTest {

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="Definitions_trace_multi" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="traceMultiInstanceTest" name="多实例标记测试" isExecutable="true"
                            camunda:historyTimeToLive="180">
                <bpmn:startEvent id="start" name="开始">
                  <bpmn:outgoing>f1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:userTask id="review" name="并行审批" camunda:assignee="${assignee}">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                  <bpmn:multiInstanceLoopCharacteristics isSequential="false"
                                                        camunda:collection="${assigneeList}"
                                                        camunda:elementVariable="assignee"/>
                </bpmn:userTask>
                <bpmn:serviceTask id="archive" name="顺序归档" camunda:expression="${true}">
                  <bpmn:incoming>f2</bpmn:incoming>
                  <bpmn:outgoing>f3</bpmn:outgoing>
                  <bpmn:multiInstanceLoopCharacteristics isSequential="true"
                                                        camunda:collection="${assigneeList}"
                                                        camunda:elementVariable="assignee"/>
                </bpmn:serviceTask>
                <bpmn:endEvent id="end" name="结束">
                  <bpmn:incoming>f3</bpmn:incoming>
                </bpmn:endEvent>

                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
                <bpmn:sequenceFlow id="f2" sourceRef="review" targetRef="archive"/>
                <bpmn:sequenceFlow id="f3" sourceRef="archive" targetRef="end"/>
              </bpmn:process>

              <bpmndi:BPMNDiagram id="BPMNDiagram_trace_multi">
                <bpmndi:BPMNPlane id="BPMNPlane_trace_multi" bpmnElement="traceMultiInstanceTest">
                  <bpmndi:BPMNShape id="Shape_start" bpmnElement="start">
                    <dc:Bounds x="40" y="102" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_review" bpmnElement="review">
                    <dc:Bounds x="120" y="80" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_archive" bpmnElement="archive">
                    <dc:Bounds x="270" y="80" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_end" bpmnElement="end">
                    <dc:Bounds x="420" y="102" width="36" height="36"/>
                  </bpmndi:BPMNShape>

                  <bpmndi:BPMNEdge id="Edge_f1" bpmnElement="f1">
                    <di:waypoint x="76" y="120"/><di:waypoint x="120" y="120"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f2" bpmnElement="f2">
                    <di:waypoint x="220" y="120"/><di:waypoint x="270" y="120"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f3" bpmnElement="f3">
                    <di:waypoint x="370" y="120"/><di:waypoint x="420" y="120"/>
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
    private HistoryService historyService;

    @Autowired
    private ProcessTraceService processTraceService;

    @Test
    void multiInstanceTypeShouldComeFromBpmnDefinition() {
        repositoryService.createDeployment()
                .addString("trace-multi-instance-test.bpmn", BPMN)
                .name("trace-multi-instance-test")
                .deploy();

        // 停在并行的多实例用户任务：archive 尚未执行，正好验证未执行的节点也能取到类型
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "traceMultiInstanceTest", Map.of("assigneeList", List.of("alice", "bob")));
        Map<String, Object> trace = processTraceService.trace(instance.getId());

        assertEquals("parallel", nodeField(trace, "review", "multiInstance"),
                "并行多实例标记丢失");
        assertEquals("并行多实例用户任务", nodeField(trace, "review", "typeLabel"));
        assertEquals("sequential", nodeField(trace, "archive", "multiInstance"),
                "顺序多实例标记丢失");
        assertEquals("顺序多实例服务任务", nodeField(trace, "archive", "typeLabel"));

        // 非多实例节点必须保持 null，避免前端给所有节点都画三条线
        assertNull(nodeField(trace, "start", "multiInstance"));
        assertEquals("开始事件", nodeField(trace, "start", "typeLabel"));

        // 历史表里的多实例体不能被当成独立节点画出来（activityId 带 #multiInstanceBody）
        HistoricActivityInstance body = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(instance.getId())
                .activityId("review#multiInstanceBody")
                .singleResult();
        assertNotNull(body, "多实例体应存在于历史活动表，这正是不该用 activityId 判断类型的原因");
        assertNull(findNode(trace, "review#multiInstanceBody"), "多实例体不应出现在跟踪图节点中");

        // 两个实例在跟踪图里聚合到 review 节点上
        assertEquals(2, nodeField(trace, "review", "executionCount"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findNode(Map<String, Object> trace, String nodeId) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) trace.get("nodes");
        assertNotNull(nodes, "跟踪数据缺少节点");
        for (Map<String, Object> node : nodes) {
            if (nodeId.equals(node.get("nodeId"))) {
                return node;
            }
        }
        return null;
    }

    private Object nodeField(Map<String, Object> trace, String nodeId, String field) {
        Map<String, Object> node = findNode(trace, nodeId);
        assertNotNull(node, "未找到节点 " + nodeId);
        return node.get(field);
    }
}
