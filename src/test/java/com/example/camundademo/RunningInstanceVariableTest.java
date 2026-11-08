package com.example.camundademo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.camundademo.service.ProcessTraceService;
import com.example.camundademo.service.WorkbenchService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.runtime.VariableInstance;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行中实例：流程变量更新后，跟踪图标题、待办详情、待办列表、我的发起都必须取到
 * <b>运行期最新值</b>，既不能回落到流程定义名，也不能被历史值覆盖。
 *
 * <p><b>为什么要单独守这一条</b>：为修「下钻已结束子流程报 ENGINE-16004」，标题读取被改成
 * 「运行期变量查询 → 历史变量 → 流程定义名」。已结束实例有历史变量兜底，看不出问题；
 * 一旦有人把顺序改成「历史优先」、或把运行期分支删掉只留历史，<b>运行中实例的标题就会停留在
 * 历史快照上，而不是刚更新过的值</b>，这类回归在已结束实例的用例里完全抓不到。</p>
 *
 * <p><b>为什么把 history-level 降到 activity</b>：full 级别下历史变量与运行期变量同步写入，
 * 即使代码只读历史也能拿到正确标题，用例形同虚设。activity 级别不记录历史变量，
 * 运行中实例的标题就<b>只能</b>来自运行期变量——这样「运行期分支被绕过」会直接表现为断言失败，
 * 而不是被历史兜底悄悄掩盖。前置断言里显式校验历史变量为 0，防止哪天配置变化后用例失去区分度。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 独立内存库，避免与其它用例共享实例
        "spring.datasource.url=jdbc:h2:mem:runningvardb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        // 关键：不记录历史变量，运行中实例无法从历史回退，只能走运行期变量查询
        "camunda.bpm.history-level=activity"
})
class RunningInstanceVariableTest {

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="Definitions_running_var" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="runningVarProcess" name="运行中变量流程" isExecutable="true"
                            camunda:historyTimeToLive="180">
                <bpmn:startEvent id="start" name="开始">
                  <bpmn:outgoing>f1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:userTask id="approve" name="部门审批" camunda:assignee="runningUser">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:userTask id="review" name="复核" camunda:assignee="runningUser">
                  <bpmn:incoming>f2</bpmn:incoming>
                  <bpmn:outgoing>f3</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:endEvent id="end" name="结束">
                  <bpmn:incoming>f3</bpmn:incoming>
                </bpmn:endEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                <bpmn:sequenceFlow id="f2" sourceRef="approve" targetRef="review"/>
                <bpmn:sequenceFlow id="f3" sourceRef="review" targetRef="end"/>
              </bpmn:process>

              <bpmndi:BPMNDiagram id="BPMNDiagram_running_var">
                <bpmndi:BPMNPlane id="BPMNPlane_running_var" bpmnElement="runningVarProcess">
                  <bpmndi:BPMNShape id="Shape_start" bpmnElement="start">
                    <dc:Bounds x="40" y="82" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_approve" bpmnElement="approve">
                    <dc:Bounds x="120" y="60" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_review" bpmnElement="review">
                    <dc:Bounds x="270" y="60" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="Shape_end" bpmnElement="end">
                    <dc:Bounds x="420" y="82" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNEdge id="Edge_f1" bpmnElement="f1">
                    <di:waypoint x="76" y="100"/><di:waypoint x="120" y="100"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f2" bpmnElement="f2">
                    <di:waypoint x="220" y="100"/><di:waypoint x="270" y="100"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="Edge_f3" bpmnElement="f3">
                    <di:waypoint x="370" y="100"/><di:waypoint x="420" y="100"/>
                  </bpmndi:BPMNEdge>
                </bpmndi:BPMNPlane>
              </bpmndi:BPMNDiagram>
            </bpmn:definitions>
            """;

    private static final String UPDATED_TITLE = "标题-复核阶段";
    private static final String USER = "runningUser";

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

    @Autowired
    private ProcessTraceService processTraceService;

    @Test
    @SuppressWarnings("unchecked")
    void runningInstanceShouldAlwaysUseLatestRuntimeVariables() {
        repositoryService.createDeployment()
                .addString("running-var.bpmn", BPMN)
                .name("running-var").deploy();

        Map<String, Object> vars = Map.of(
                "title", "标题-审批阶段",
                "applicant", USER);
        ProcessInstance instance;
        identityService.setAuthenticatedUserId(USER);
        try {
            instance = runtimeService.startProcessInstanceByKey("runningVarProcess", vars);
        } finally {
            // Camunda 7 无 clearAuthenticatedUserId()，传 null 即清除
            identityService.setAuthenticatedUserId(null);
        }
        String pid = instance.getId();

        Task approve = taskService.createTaskQuery().processInstanceId(pid)
                .taskDefinitionKey("approve").singleResult();
        assertNotNull(approve, "流程应停在部门审批节点");
        // 审批过程中更新标题：这是「运行期值已经变化」的现场
        taskService.complete(approve.getId(), Map.of("title", UPDATED_TITLE));

        Task review = taskService.createTaskQuery().processInstanceId(pid)
                .taskDefinitionKey("review").singleResult();
        assertNotNull(review, "流程应已推进到复核节点");

        // ===== 前置条件：证明这条用例确实只能依赖运行期变量 =====
        assertNotNull(runtimeService.createProcessInstanceQuery().processInstanceId(pid).singleResult(),
                "流程应仍在运行中，否则本用例测的不是运行期分支");
        List<VariableInstance> liveTitles = runtimeService.createVariableInstanceQuery()
                .processInstanceIdIn(pid).variableName("title").list();
        assertEquals(1, liveTitles.size(), "运行期应只有一条 title（同一作用域）");
        assertEquals(UPDATED_TITLE, liveTitles.get(0).getValue(), "运行期变量应已是最新值");
        assertEquals(0, historyService.createHistoricVariableInstanceQuery()
                        .processInstanceId(pid).count(),
                "history-level=activity 下历史变量应为空；若不为空，本用例会退化成"
                        + "「历史值也能兜底」，无法发现运行期分支被绕过");

        // ===== 关键断言：四处读取都要拿到最新值，且引擎不允许出现 ERROR =====
        ch.qos.logback.classic.Logger engineLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.camunda.bpm.engine.context");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        engineLogger.addAppender(appender);
        Map<String, Object> trace;
        Map<String, Object> detail;
        Map<String, Object> todoPage;
        Map<String, Object> startedPage;
        try {
            trace = processTraceService.trace(pid);
            detail = workbenchService.taskDetail(review.getId(), USER);
            todoPage = workbenchService.listTodo(USER, null, null, 1, 20);
            startedPage = workbenchService.listStarted(USER, null, 1, 20);
        } finally {
            engineLogger.detachAppender(appender);
        }

        assertEquals("RUNNING", trace.get("state"));
        assertEquals(UPDATED_TITLE, trace.get("processTitle"),
                "运行中实例的跟踪图标题必须取运行期最新值，不能回落到流程定义名");
        assertTrue(String.valueOf(trace.get("currentNodeNames")).contains("复核"),
                "当前节点应为复核，实际：" + trace.get("currentNodeNames"));

        Map<String, Object> detailVars = (Map<String, Object>) detail.get("variables");
        assertNotNull(detailVars, "待办详情应返回变量");
        assertEquals(UPDATED_TITLE, detailVars.get("title"),
                "待办详情的 variables 必须是运行期最新值");
        assertEquals(UPDATED_TITLE, detail.get("processTitle"),
                "待办详情的标题也应是最新值");

        Map<String, Object> todoRow = rowOf((List<Map<String, Object>>) todoPage.get("list"),
                "taskId", review.getId());
        assertEquals(UPDATED_TITLE, todoRow.get("processTitle"),
                "待办列表的标题必须取运行期最新值");

        Map<String, Object> startedRow = rowOf((List<Map<String, Object>>) startedPage.get("list"),
                "processInstanceId", pid);
        assertEquals("RUNNING", startedRow.get("state"));
        assertEquals(UPDATED_TITLE, startedRow.get("processTitle"),
                "我的发起里的标题必须取运行期最新值");
        assertEquals(USER, startedRow.get("applicant"), "申请人不能为空");

        List<ILoggingEvent> engineErrors = appender.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR))
                .toList();
        assertTrue(engineErrors.isEmpty(),
                "运行中实例读取变量不应触发引擎 ERROR，实际："
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
