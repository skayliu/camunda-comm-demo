package com.example.camundademo;

import com.example.camundademo.service.ProcessTraceService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程正常走完后，跟踪图必须把结束节点判成“已完成”。
 *
 * <p>回归点：节点与历史活动的对齐一度靠 ACT_TYPE_ 白名单，而白名单里写的是 BPMN 元素名
 * {@code endEvent}；Camunda 在 ACT_HI_ACTINST 里记录的却是具体事件类型
 * {@code noneEndEvent}。结果结束事件的历史活动被整条过滤掉，节点拿不到实例，
 * 于是流程明明已结束，图上结束节点仍是灰色“未开始”，明细行也没有结束时间。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:traceenddb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class ProcessTraceCompletedEndTest {

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="Definitions_trace_end" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="traceEndTest" name="结束后状态测试" isExecutable="true"
                            camunda:historyTimeToLive="180">
                <bpmn:startEvent id="start" name="开始">
                  <bpmn:outgoing>f_start_approve</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:userTask id="approve" name="审批" camunda:assignee="manager">
                  <bpmn:incoming>f_start_approve</bpmn:incoming>
                  <bpmn:outgoing>f_approve_end</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:endEvent id="end" name="结束">
                  <bpmn:incoming>f_approve_end</bpmn:incoming>
                </bpmn:endEvent>

                <bpmn:sequenceFlow id="f_start_approve" sourceRef="start" targetRef="approve"/>
                <bpmn:sequenceFlow id="f_approve_end" sourceRef="approve" targetRef="end"/>
              </bpmn:process>

              <bpmndi:BPMNDiagram id="BPMNDiagram_trace_end">
                <bpmndi:BPMNPlane id="BPMNPlane_trace_end" bpmnElement="traceEndTest">
                  <bpmndi:BPMNShape id="Shape_start" bpmnElement="start">
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
    private TaskService taskService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private ProcessTraceService processTraceService;

    @Test
    void completedProcessShouldMarkEndEventAsCompleted() {
        repositoryService.createDeployment()
                .addString("trace-end-test.bpmn", BPMN)
                .name("trace-end-test")
                .deploy();

        ProcessInstance instance = runtimeService.startProcessInstanceByKey("traceEndTest");
        String pid = instance.getId();

        Task approve = taskService.createTaskQuery().processInstanceId(pid)
                .taskDefinitionKey("approve").singleResult();
        assertNotNull(approve, "审批任务应已生成");
        taskService.complete(approve.getId());

        // 先确认流程真的结束了，避免把“流程没结束”误判成跟踪图的问题
        assertNull(runtimeService.createProcessInstanceQuery()
                .processInstanceId(pid).singleResult(), "流程实例应已结束");
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(pid).singleResult();
        assertNotNull(hpi.getEndTime(), "历史流程实例应有结束时间");

        // 引擎侧事实：结束事件的历史活动类型是 noneEndEvent，而不是 endEvent
        List<HistoricActivityInstance> endActivities = historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(pid)
                .activityId("end")
                .list();
        assertEquals(1, endActivities.size(), "结束事件应留下一条历史活动记录");
        assertEquals("noneEndEvent", endActivities.get(0).getActivityType(),
                "Camunda 对普通结束事件记录的类型应为 noneEndEvent");

        Map<String, Object> trace = processTraceService.trace(pid);

        assertEquals("COMPLETED", nodeStatus(trace, "start"), "开始节点应为已完成");
        assertEquals("COMPLETED", nodeStatus(trace, "approve"), "审批节点应为已完成");
        assertEquals("COMPLETED", nodeStatus(trace, "end"),
                "流程已结束，结束节点必须判为已完成，而不是未开始");
        assertNotNull(nodeField(trace, "end", "endTime"), "结束节点应有结束时间，明细行不能为空");
        assertFalse(Boolean.TRUE.equals(nodeField(trace, "end", "running")),
                "结束节点不应处于运行中");
    }

    @SuppressWarnings("unchecked")
    private String nodeStatus(Map<String, Object> trace, String nodeId) {
        Object status = nodeField(trace, nodeId, "status");
        return status == null ? null : status.toString();
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
