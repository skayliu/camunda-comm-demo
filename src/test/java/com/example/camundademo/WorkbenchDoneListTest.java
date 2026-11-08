package com.example.camundademo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.camundademo.service.WorkbenchService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已结束实例的列表查询：「我的已办」「我的发起」必须能从历史变量取到标题与申请人，
 * 且全程不触发引擎 ERROR。
 *
 * <p><b>为什么单独守这一条</b>：{@code WorkbenchService#safeVariables} 原先直接调
 * {@code runtimeService.getVariables(processInstanceId)}。实例一旦结束，运行期表里没有这个
 * execution，引擎抛 {@code NullValueException}，收尾时打印 {@code ENGINE-16004}，并把
 * Spring 事务标记为 rollback-only——即使外层 catch 住，提交阶段仍会变成 500。</p>
 *
 * <p><b>为什么断言引擎日志而不是只看返回值</b>：旧实现把异常吞掉后，历史变量回退仍然生效，
 * 标题和申请人看起来完全正常。只断言返回值的用例在旧代码上会「通过」，起不到防回归作用。
 * 真正的信号是引擎那条 ERROR，因此这里直接捕获 {@code org.camunda.bpm.engine.context} 的日志。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 独立内存库：避免与其它测试上下文共享数据，否则已办列表里会混入别的用例的记录
        "spring.datasource.url=jdbc:h2:mem:workbenchdonedb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class WorkbenchDoneListTest {

    private static final String CHILD_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="Definitions_done_child" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="doneListChild" name="被调用子流程" isExecutable="true"
                            camunda:historyTimeToLive="180">
                <bpmn:startEvent id="childStart" name="开始">
                  <bpmn:outgoing>cf1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:userTask id="childApprove" name="子流程审批" camunda:assignee="childUser">
                  <bpmn:incoming>cf1</bpmn:incoming>
                  <bpmn:outgoing>cf2</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:endEvent id="childEnd" name="结束">
                  <bpmn:incoming>cf2</bpmn:incoming>
                </bpmn:endEvent>
                <bpmn:sequenceFlow id="cf1" sourceRef="childStart" targetRef="childApprove"/>
                <bpmn:sequenceFlow id="cf2" sourceRef="childApprove" targetRef="childEnd"/>
              </bpmn:process>

              <bpmndi:BPMNDiagram id="BPMNDiagram_done_child">
                <bpmndi:BPMNPlane id="BPMNPlane_done_child" bpmnElement="doneListChild">
                  <bpmndi:BPMNShape id="Shape_childStart" bpmnElement="childStart">
                    <dc:Bounds x="40" y="82" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_childApprove" bpmnElement="childApprove">
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
                              id="Definitions_done_parent" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="doneListParent" name="主流程" isExecutable="true"
                            camunda:historyTimeToLive="180">
                <bpmn:startEvent id="start" name="开始">
                  <bpmn:outgoing>f1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:userTask id="apply" name="发起申请" camunda:assignee="parentUser">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:callActivity id="call" name="调用子流程" calledElement="doneListChild">
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

              <bpmndi:BPMNDiagram id="BPMNDiagram_done_parent">
                <bpmndi:BPMNPlane id="BPMNPlane_done_parent" bpmnElement="doneListParent">
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
    private IdentityService identityService;

    @Autowired
    private WorkbenchService workbenchService;

    @Test
    @SuppressWarnings("unchecked")
    void finishedInstanceListsShouldReadHistoricVariablesWithoutEngineError() {
        repositoryService.createDeployment()
                .addString("done-list-child.bpmn", CHILD_BPMN)
                .name("done-list-child").deploy();
        repositoryService.createDeployment()
                .addString("done-list-parent.bpmn", PARENT_BPMN)
                .name("done-list-parent").deploy();

        // 让历史实例的 START_USER_ID_ 有值，listStarted 才能按发起人查到。
        Map<String, Object> vars = Map.of(
                "title", "调用子流程审批",
                "applicant", "parentUser");
        ProcessInstance parent;
        identityService.setAuthenticatedUserId("parentUser");
        try {
            parent = runtimeService.startProcessInstanceByKey("doneListParent", vars);
        } finally {
            // Camunda 7 的 IdentityService 没有 clearAuthenticatedUserId()，传 null 即为清除
            identityService.setAuthenticatedUserId(null);
        }
        String parentId = parent.getId();

        Task apply = taskService.createTaskQuery().processInstanceId(parentId).singleResult();
        assertNotNull(apply, "父流程应停在发起申请节点");
        assertEquals("parentUser", apply.getAssignee());
        taskService.complete(apply.getId());

        ProcessInstance child = runtimeService.createProcessInstanceQuery()
                .superProcessInstanceId(parentId).singleResult();
        assertNotNull(child, "调用活动应已调起子流程实例");
        Task childTask = taskService.createTaskQuery().processInstanceId(child.getId()).singleResult();
        assertNotNull(childTask, "子流程应停在审批节点");
        taskService.complete(childTask.getId());

        // 前置条件：父实例已彻底结束；运行期变量查询为空，标题只能来自历史变量。
        // 若这里不成立，下面的用例就测不到「已结束」这条路径，必须在断言层面失败而不是静默滑过。
        assertNull(runtimeService.createProcessInstanceQuery().processInstanceId(parentId).singleResult(),
                "父流程应已结束，运行期不应再有该实例");
        assertEquals(0, runtimeService.createVariableInstanceQuery()
                        .processInstanceIdIn(parentId).count(),
                "已结束实例不应再返回运行期变量，否则本用例没有覆盖历史回退分支");
        assertNotNull(historyService.createHistoricVariableInstanceQuery()
                        .processInstanceId(parentId).variableName("title").singleResult(),
                "历史变量表应保留 title，列表才有数据可取");
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(parentId).singleResult();
        assertNotNull(hpi, "历史实例应存在");
        assertEquals("parentUser", hpi.getStartUserId(), "发起人需落在历史实例上，listStarted 才查得到");

        // 关键：整段列表查询期间不允许引擎出现 ERROR。旧实现会在这里打印 ENGINE-16004。
        ch.qos.logback.classic.Logger engineLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.camunda.bpm.engine.context");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        engineLogger.addAppender(appender);
        Map<String, Object> donePage;
        Map<String, Object> startedPage;
        try {
            donePage = workbenchService.listDone("parentUser", null, 1, 20);
            startedPage = workbenchService.listStarted("parentUser", null, 1, 20);
        } finally {
            engineLogger.detachAppender(appender);
        }

        Map<String, Object> doneRow = rowOf((List<Map<String, Object>>) donePage.get("list"),
                "taskDefKey", "apply");
        assertEquals("调用子流程审批", doneRow.get("processTitle"),
                "已办列表的标题必须取自历史变量，而不是回退成流程定义名或空");
        assertEquals("parentUser", doneRow.get("applicant"), "已办列表的申请人不能为空");
        assertEquals("parentUser", doneRow.get("assignee"));

        Map<String, Object> startedRow = rowOf((List<Map<String, Object>>) startedPage.get("list"),
                "processInstanceId", parentId);
        assertEquals("FINISHED", startedRow.get("state"));
        assertEquals("调用子流程审批", startedRow.get("processTitle"),
                "我的发起的标题必须取自历史变量");
        assertEquals("parentUser", startedRow.get("applicant"));

        List<ILoggingEvent> engineErrors = appender.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR))
                .toList();
        assertTrue(engineErrors.isEmpty(),
                "已结束实例的列表查询不应触发引擎 ERROR（ENGINE-16004），实际："
                        + engineErrors.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    private Map<String, Object> rowOf(List<Map<String, Object>> rows, String key, Object value) {
        assertNotNull(rows, "列表结果缺少 list");
        for (Map<String, Object> row : rows) {
            if (value.equals(row.get(key))) {
                return row;
            }
        }
        throw new AssertionError("列表中未找到 " + key + "=" + value + "，实际条数：" + rows.size());
    }
}
