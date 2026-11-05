package com.example.camundademo;

import com.example.camundademo.service.CommunicationService;
import com.example.camundademo.service.ProcessTraceService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程跟踪图必须能看到「已收回」的沟通。
 *
 * <p>回归点：收回走的是 {@code taskService.deleteTask(...)}，Camunda 会把对应的
 * ACT_HI_TASKINST 历史任务一起删掉，早期实现只从历史任务取沟通，
 * 于是收回的沟通在跟踪界面整条消失。现在由业务表 BIZ_COMM_TASK_ 兜底补回。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:tracewithdrawdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class ProcessTraceWithdrawnCommTest {

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="Definitions_trace_withdraw" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="traceWithdrawTest" name="已收回沟通跟踪测试" isExecutable="true"
                            camunda:historyTimeToLive="180">
                <bpmn:startEvent id="start" name="开始">
                  <bpmn:outgoing>f_start_review</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:userTask id="manager_review" name="经理审批" camunda:assignee="manager">
                  <bpmn:incoming>f_start_review</bpmn:incoming>
                  <bpmn:outgoing>f_review_end</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:endEvent id="end" name="结束">
                  <bpmn:incoming>f_review_end</bpmn:incoming>
                </bpmn:endEvent>

                <bpmn:sequenceFlow id="f_start_review" sourceRef="start" targetRef="manager_review"/>
                <bpmn:sequenceFlow id="f_review_end" sourceRef="manager_review" targetRef="end"/>
              </bpmn:process>

              <bpmndi:BPMNDiagram id="BPMNDiagram_trace_withdraw">
                <bpmndi:BPMNPlane id="BPMNPlane_trace_withdraw" bpmnElement="traceWithdrawTest">
                  <bpmndi:BPMNShape id="Shape_start" bpmnElement="start">
                    <dc:Bounds x="60" y="142" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_review" bpmnElement="manager_review">
                    <dc:Bounds x="160" y="120" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_end" bpmnElement="end">
                    <dc:Bounds x="330" y="142" width="36" height="36"/>
                  </bpmndi:BPMNShape>

                  <bpmndi:BPMNEdge id="Edge_f_start_review" bpmnElement="f_start_review">
                    <di:waypoint x="96" y="160"/><di:waypoint x="160" y="160"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f_review_end" bpmnElement="f_review_end">
                    <di:waypoint x="260" y="160"/><di:waypoint x="330" y="160"/>
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
    private CommunicationService communicationService;

    @Autowired
    private ProcessTraceService processTraceService;

    @Test
    void withdrawnCommunicationShouldStillAppearInTrace() {
        repositoryService.createDeployment()
                .addString("trace-withdraw-test.bpmn", BPMN)
                .name("trace-withdraw-test")
                .deploy();

        ProcessInstance instance = runtimeService.startProcessInstanceByKey("traceWithdrawTest");
        String pid = instance.getId();
        try {
            Task review = taskService.createTaskQuery().processInstanceId(pid)
                    .taskDefinitionKey("manager_review").singleResult();
            assertNotNull(review, "经理审批待办应已生成");

            List<Map<String, String>> targets = new ArrayList<>();
            Map<String, String> alice = new HashMap<>();
            alice.put("type", "USER");
            alice.put("id", "alice");
            alice.put("name", "Alice");
            targets.add(alice);

            String sessionId = communicationService.startCommunication(
                    review.getId(), "manager", "请确认合同条款", targets);
            assertNotNull(sessionId, "沟通会话应创建成功");
            assertEquals(1, taskService.createTaskQuery().processInstanceId(pid)
                    .taskDefinitionKey("manager_review#COMM").count(), "应生成沟通任务");

            // 全部收回：引擎任务被删除，历史任务也随之一并消失
            assertEquals(1, communicationService.withdrawCommunication(sessionId, "manager", null),
                    "应收回 1 条沟通");
            assertEquals(0, taskService.createTaskQuery().processInstanceId(pid)
                    .taskDefinitionKey("manager_review#COMM").count(), "收回后沟通任务应被删除");

            Map<String, Object> trace = processTraceService.trace(pid);
            List<Map<String, Object>> commTasks = commTasksOf(trace, "manager_review");
            assertEquals(1, commTasks.size(),
                    "已收回的沟通仍应出现在跟踪图里（不能因为历史任务被删而消失）");
            Map<String, Object> comm = commTasks.get(0);
            assertEquals("WITHDRAWN", comm.get("status"), "状态应为已收回");
            assertEquals(false, comm.get("pending"), "已收回不应算待反馈");
            assertEquals("Alice", comm.get("targetName"), "应保留沟通对象");
            assertEquals("请确认合同条款", comm.get("content"), "应保留沟通内容");
            assertNotNull(comm.get("withdrawTime"), "应记录收回时间");
            assertNotNull(comm.get("camundaTaskId"), "旁路任务ID 应落在 CAMUNDA_TASK_ID_ 并原样外发");
            assertNull(comm.get("deptProcInstId"),
                    "DEPT_PROC_INST_ID_ 已归位为部门子流程实例ID，对人的沟通必须保持为空");

            Map<String, Object> node = nodeOf(trace, "manager_review");
            assertEquals(0, node.get("pendingCommCount"), "已收回后不应再有待反馈");
            assertEquals(1, node.get("withdrawnCommCount"), "节点应统计出 1 条已收回");
        } finally {
            runtimeService.deleteProcessInstance(pid, "test cleanup");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> commTasksOf(Map<String, Object> trace, String nodeId) {
        Object value = nodeOf(trace, nodeId).get("commTasks");
        assertTrue(value instanceof List, "节点缺少沟通记录");
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nodeOf(Map<String, Object> trace, String nodeId) {
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
