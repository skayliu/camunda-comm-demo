package com.example.camundademo;

import com.example.camundademo.service.ProcessTraceService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程跟踪图的连线点亮判定。
 *
 * <p>回归点：早期实现用“目标节点存在活动实例”判断连线是否走过。当多条入边汇到同一个
 * 节点（汇合任务 / 网关）时，只要有一条分支到达，该节点就有了活动实例，于是所有还没到达
 * 的入边也会被点亮。这里用“一条分支办完、另一条仍在办理”的场景把该错误钉住。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:traceedgedb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class ProcessTraceEdgeTest {

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="Definitions_trace_edge" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="traceEdgeTest" name="并行分支连线测试" isExecutable="true"
                            camunda:historyTimeToLive="180">
                <bpmn:startEvent id="start" name="开始">
                  <bpmn:outgoing>f_start_split</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:parallelGateway id="split" name="并行网关">
                  <bpmn:incoming>f_start_split</bpmn:incoming>
                  <bpmn:outgoing>f_split_t1</bpmn:outgoing>
                  <bpmn:outgoing>f_split_t2</bpmn:outgoing>
                </bpmn:parallelGateway>
                <bpmn:userTask id="t1" name="分支一" camunda:assignee="u1">
                  <bpmn:incoming>f_split_t1</bpmn:incoming>
                  <bpmn:outgoing>f_t1_t3</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:userTask id="t2" name="分支二" camunda:assignee="u2">
                  <bpmn:incoming>f_split_t2</bpmn:incoming>
                  <bpmn:outgoing>f_t2_t3</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:userTask id="t3" name="汇合任务" camunda:assignee="u3">
                  <bpmn:incoming>f_t1_t3</bpmn:incoming>
                  <bpmn:incoming>f_t2_t3</bpmn:incoming>
                  <bpmn:outgoing>f_t3_end</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:endEvent id="end" name="结束">
                  <bpmn:incoming>f_t3_end</bpmn:incoming>
                </bpmn:endEvent>

                <bpmn:sequenceFlow id="f_start_split" sourceRef="start" targetRef="split"/>
                <bpmn:sequenceFlow id="f_split_t1" sourceRef="split" targetRef="t1"/>
                <bpmn:sequenceFlow id="f_split_t2" sourceRef="split" targetRef="t2"/>
                <bpmn:sequenceFlow id="f_t1_t3" sourceRef="t1" targetRef="t3"/>
                <bpmn:sequenceFlow id="f_t2_t3" sourceRef="t2" targetRef="t3"/>
                <bpmn:sequenceFlow id="f_t3_end" sourceRef="t3" targetRef="end"/>
              </bpmn:process>

              <bpmndi:BPMNDiagram id="BPMNDiagram_trace_edge">
                <bpmndi:BPMNPlane id="BPMNPlane_trace_edge" bpmnElement="traceEdgeTest">
                  <bpmndi:BPMNShape id="Shape_start" bpmnElement="start">
                    <dc:Bounds x="40" y="142" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_split" bpmnElement="split">
                    <dc:Bounds x="116" y="140" width="40" height="40"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_t1" bpmnElement="t1">
                    <dc:Bounds x="200" y="80" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_t2" bpmnElement="t2">
                    <dc:Bounds x="200" y="200" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_t3" bpmnElement="t3">
                    <dc:Bounds x="360" y="140" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_end" bpmnElement="end">
                    <dc:Bounds x="510" y="162" width="36" height="36"/>
                  </bpmndi:BPMNShape>

                  <bpmndi:BPMNEdge id="Edge_f_start_split" bpmnElement="f_start_split">
                    <di:waypoint x="76" y="160"/><di:waypoint x="116" y="160"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f_split_t1" bpmnElement="f_split_t1">
                    <di:waypoint x="136" y="140"/><di:waypoint x="136" y="120"/>
                    <di:waypoint x="200" y="120"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f_split_t2" bpmnElement="f_split_t2">
                    <di:waypoint x="136" y="180"/><di:waypoint x="136" y="240"/>
                    <di:waypoint x="200" y="240"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f_t1_t3" bpmnElement="f_t1_t3">
                    <di:waypoint x="300" y="120"/><di:waypoint x="330" y="120"/>
                    <di:waypoint x="330" y="180"/><di:waypoint x="360" y="180"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f_t2_t3" bpmnElement="f_t2_t3">
                    <di:waypoint x="300" y="240"/><di:waypoint x="330" y="240"/>
                    <di:waypoint x="330" y="180"/><di:waypoint x="360" y="180"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f_t3_end" bpmnElement="f_t3_end">
                    <di:waypoint x="460" y="180"/><di:waypoint x="510" y="180"/>
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
    private ProcessTraceService processTraceService;

    @Test
    void unreachedBranchEdgeShouldStayIdle() {
        repositoryService.createDeployment()
                .addString("trace-edge-test.bpmn", BPMN)
                .name("trace-edge-test")
                .deploy();

        ProcessInstance instance = runtimeService.startProcessInstanceByKey("traceEdgeTest");
        String pid = instance.getId();
        try {
            // 只办完分支一：分支二仍停在 t2，汇合任务 t3 已被分支一触发
            Task t1 = taskService.createTaskQuery().processInstanceId(pid)
                    .taskDefinitionKey("t1").singleResult();
            assertNotNull(t1, "分支一应已生成待办");
            taskService.complete(t1.getId());

            assertEquals(1, taskService.createTaskQuery().processInstanceId(pid)
                    .taskDefinitionKey("t2").count(), "分支二应仍在办理中");
            assertEquals(1, taskService.createTaskQuery().processInstanceId(pid)
                    .taskDefinitionKey("t3").count(), "汇合任务应已被分支一触发");

            Map<String, Object> trace = processTraceService.trace(pid);

            assertTrue(edgeTaken(trace, "f_t1_t3"), "分支一已办完，其进入汇合任务的连线应点亮");
            assertEquals(false, edgeTaken(trace, "f_t2_t3"),
                    "分支二尚未办完，其进入汇合任务的连线不应点亮");
            assertEquals(false, edgeTaken(trace, "f_t3_end"), "汇合任务未完成，后续连线不应点亮");

            assertEquals("ACTIVE", nodeStatus(trace, "t2"));
            assertEquals("ACTIVE", nodeStatus(trace, "t3"));

            // 已走完 vs 流转中：目标节点已结束的连线是“已完成”（绿），
            // 目标节点仍在办理的连线是“流转中”（蓝），两者都算 taken
            assertTrue(edgeTaken(trace, "f_split_t1"), "分支一已办完，拆分到分支一的连线应点亮");
            assertEquals(false, edgeActive(trace, "f_split_t1"),
                    "分支一已完成，其入边应为已完成而不是流转中");

            assertTrue(edgeTaken(trace, "f_split_t2"), "分支二已到达，拆分到分支二的连线应点亮");
            assertTrue(edgeActive(trace, "f_split_t2"),
                    "分支二仍在办理，其入边应为流转中");

            assertTrue(edgeTaken(trace, "f_t1_t3"), "分支一已办完，到汇合任务的连线应点亮");
            assertTrue(edgeActive(trace, "f_t1_t3"),
                    "汇合任务仍在办理，分支一的入边应为流转中而不是已完成");

            assertEquals(false, edgeActive(trace, "f_t2_t3"),
                    "分支二尚未到达，其入边不能是流转中");
            assertEquals(false, edgeActive(trace, "f_t3_end"),
                    "汇合任务未办完，其出边既未走过也不应流转中");
            assertEquals(false, edgeTaken(trace, "f_t3_end"), "汇合任务未办完，其出边不应点亮");
        } finally {
            runtimeService.deleteProcessInstance(pid, "test cleanup");
        }
    }

    @SuppressWarnings("unchecked")
    private boolean edgeTaken(Map<String, Object> trace, String edgeId) {
        List<Map<String, Object>> edges = (List<Map<String, Object>>) trace.get("edges");
        assertNotNull(edges, "跟踪数据缺少连线");
        for (Map<String, Object> edge : edges) {
            if (edgeId.equals(edge.get("id"))) {
                return Boolean.TRUE.equals(edge.get("taken"));
            }
        }
        throw new AssertionError("未找到连线 " + edgeId);
    }

    @SuppressWarnings("unchecked")
    private boolean edgeActive(Map<String, Object> trace, String edgeId) {
        List<Map<String, Object>> edges = (List<Map<String, Object>>) trace.get("edges");
        assertNotNull(edges, "跟踪数据缺少连线");
        for (Map<String, Object> edge : edges) {
            if (edgeId.equals(edge.get("id"))) {
                return Boolean.TRUE.equals(edge.get("active"));
            }
        }
        throw new AssertionError("未找到连线 " + edgeId);
    }

    @SuppressWarnings("unchecked")
    private String nodeStatus(Map<String, Object> trace, String nodeId) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) trace.get("nodes");
        assertNotNull(nodes, "跟踪数据缺少节点");
        for (Map<String, Object> node : nodes) {
            if (nodeId.equals(node.get("nodeId"))) {
                return (String) node.get("status");
            }
        }
        throw new AssertionError("未找到节点 " + nodeId);
    }
}
