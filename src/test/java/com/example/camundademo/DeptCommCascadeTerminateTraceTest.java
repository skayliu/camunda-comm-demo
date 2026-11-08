package com.example.camundademo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.listener.DeptCommCascadeListener;
import com.example.camundademo.security.DemoUserInitializer;
import com.example.camundademo.service.DeptCommProcessService;
import com.example.camundademo.service.ProcessTraceService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主流程<b>正常办结</b>级联终止部门沟通后，跟踪图仍应能下钻，父级入口不得消失。
 *
 * <p><b>为什么单开一条</b>：{@code DeptCommFlowTest#mainProcessEndCascadesDeptSubProcess}
 * 已经覆盖了级联终止本身，但只看<b>运行期与业务表</b>（实例没了、记录变已收回）；
 * {@code DeptCommWithdrawnTraceTest} 覆盖的是<b>用户主动收回</b>后的跟踪图。
 * 两者都没有回答本用例的问题：主流程自己已经结束，此时打开已终止的子流程跟踪图，
 * 父实例已不在运行期，标题要落到历史变量，下钻入口只能靠业务记录拼装——
 * 最容易退化成「返回上级」整块消失、或下钻列表里看不到这个已终止的子实例。</p>
 *
 * <p><b>为什么手工建单</b>：正常链路上，沟通中的主待办会被
 * {@code CommunicationCompleteGuardListener} 锁住（{@code BizTaskExt=COMMUNICATING}），
 * 不可能一边等部门反馈一边把主流程办结。能同时成立的真实形态是「主单已办结、
 * 遗留下仍在等待的部门子流程」。因此这里直接插入一条待反馈记录并启动子流程，
 * 让主流程保持未锁定，从而真实触发"主流程 end 事件 → 级联监听器"这条路径。</p>
 *
 * <p><b>口径说明</b>：{@code trace()} 的 {@code state} 只有 {@code RUNNING / FINISHED}，
 * 「已终止」由 {@code endTime} 非空 + {@code deleteReason} 非空表达；
 * 父流程是正常办结，{@code deleteReason} 必须为空，两者不能混淆。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 独立内存库：本用例会真实办结主流程、终止子流程，不能与其它测试类共用数据
        "spring.datasource.url=jdbc:h2:mem:deptcommcascadetrace;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class DeptCommCascadeTerminateTraceTest {

    private static final String DEPT_IT = "dept_it";
    private static final String DEPT_IT_NAME = "IT 部";
    private static final String DEPT_TASK_KEY = DeptCommProcessService.TASK_KEY;

    private static final String PARENT_TITLE_APPLY = "标题-主流程-发起";
    private static final String PARENT_TITLE = "标题-主流程-已提交";
    private static final String CONTENT = "主单办结前遗留的部门沟通";

    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_PENDING = "PENDING";

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

    @Autowired
    private DeptCommProcessService deptCommProcessService;

    @Autowired
    private DemoUserInitializer demoUserInitializer;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    /** 部门组与演示账号要就位，否则 dept_handle 的候选组没有人能承接 */
    @BeforeEach
    void ensureDemoIdentity() {
        demoUserInitializer.run(null);
        jdbc = new JdbcTemplate(dataSource);
        if (repositoryService.createProcessDefinitionQuery().processDefinitionKey("approval").count() == 0) {
            repositoryService.createDeployment()
                    .addClasspathResource("processes/approval.bpmn")
                    .name("deptcomm-cascade-approval").deploy();
        }
        if (repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(DeptCommProcessService.PROCESS_KEY).count() == 0) {
            repositoryService.createDeployment()
                    .addClasspathResource("processes/dept_communication.bpmn")
                    .name("deptcomm-cascade-dept").deploy();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void mainProcessFinishShouldCascadeTerminateAndKeepDrillEntry() {
        // ===== 1. 主流程推进到经理审批，父实例持有自己的标题 =====
        ProcessInstance parent = runtimeService.startProcessInstanceByKey("approval",
                Map.of("title", PARENT_TITLE_APPLY, "applicant", "applicant"));
        String parentId = parent.getId();

        Task apply = taskService.createTaskQuery().processInstanceId(parentId)
                .taskDefinitionKey("apply").singleResult();
        assertNotNull(apply, "主流程应停在发起节点");
        taskService.complete(apply.getId(), Map.of("title", PARENT_TITLE));

        Task managerTask = taskService.createTaskQuery().processInstanceId(parentId)
                .taskDefinitionKey("manager_review").singleResult();
        assertNotNull(managerTask, "主流程应停在经理审批节点");

        // ===== 2. 手工建单 + 手工启动部门子流程（主流程保持未锁定） =====
        String recordId = insertPendingDeptRecord(managerTask);
        DeptCommProcessService.Started started = deptCommProcessService.start(
                recordId, "SESSION-CASCADE-" + recordId, managerTask, DEPT_IT, DEPT_IT_NAME,
                CONTENT, "manager", 1);
        String childId = started.getProcessInstanceId();
        assertNotNull(childId, "部门沟通子流程未启动");
        assertNotNull(started.getTaskId(), "部门沟通子流程启动后应立刻有 dept_handle 任务");
        jdbc.update("UPDATE BIZ_COMM_TASK_ SET CAMUNDA_TASK_ID_ = ?, DEPT_PROC_INST_ID_ = ? WHERE ID_ = ?",
                started.getTaskId(), childId, recordId);

        // ===== 前置条件：子流程在运行、记录待反馈、引擎里没有父子关系 =====
        assertNotNull(runtimeService.createProcessInstanceQuery().processInstanceId(childId).singleResult(),
                "部门沟通子流程应在运行中");
        assertNotNull(taskService.createTaskQuery().processInstanceId(childId)
                .taskDefinitionKey(DEPT_TASK_KEY).singleResult(), "部门子流程里应有 dept_handle 任务");
        assertEquals(BizCommTask.STATUS_PENDING, recordStatus(recordId), "办结前记录应为待反馈");
        assertEquals(childId, jdbc.queryForObject(
                "SELECT DEPT_PROC_INST_ID_ FROM BIZ_COMM_TASK_ WHERE ID_ = ?", String.class, recordId),
                "业务记录必须指向子流程实例，级联终止与下钻都靠它");

        HistoricProcessInstance childBefore = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(childId).singleResult();
        assertNotNull(childBefore, "部门子流程历史不存在");
        assertNull(childBefore.getSuperProcessInstanceId(),
                "部门沟通在引擎里没有父子关系；若这里非空，本用例就落到 callActivity 分支了");
        assertNull(childBefore.getEndTime(), "办结主流程前，子流程不应有结束时间");

        // ===== 3. 主流程办结：经理审批 → 财务审批 → end 事件触发级联监听器 =====
        Logger engineLogger = (Logger) LoggerFactory.getLogger("org.camunda.bpm.engine.context");
        Logger traceLogger = (Logger) LoggerFactory.getLogger(ProcessTraceService.class);
        Logger cascadeLogger = (Logger) LoggerFactory.getLogger(DeptCommCascadeListener.class);
        ListAppender<ILoggingEvent> engineAppender = new ListAppender<>();
        ListAppender<ILoggingEvent> traceAppender = new ListAppender<>();
        ListAppender<ILoggingEvent> cascadeAppender = new ListAppender<>();
        engineAppender.start();
        traceAppender.start();
        cascadeAppender.start();
        engineLogger.addAppender(engineAppender);
        traceLogger.addAppender(traceAppender);
        cascadeLogger.addAppender(cascadeAppender);
        // test/resources/logback-test.xml 把日志压到 WARN，监听器的 INFO 记录默认不会产生事件。
        // 这里临时放开该 logger，用完在 finally 里恢复，否则「级联确实走了 end 监听器」这条断言
        // 会永远拿到空列表而失败（也会让人误以为监听器没执行）。
        Level cascadeOriginalLevel = cascadeLogger.getLevel();
        cascadeLogger.setLevel(Level.INFO);

        Map<String, Object> parentTrace;
        Map<String, Object> childTrace;
        try {
            taskService.complete(managerTask.getId());

            Task financeTask = taskService.createTaskQuery().processInstanceId(parentId)
                    .taskDefinitionKey("finance_review").singleResult();
            assertNotNull(financeTask, "经理审批通过后应流转到财务审批");
            // 办结财务审批即走到 end，主流程实例结束，级联监听器在 end 事件里终止子流程
            taskService.complete(financeTask.getId());

            // ----- 3.1 终止事实：主流程正常办结，子流程被级联终止 -----
            assertNull(runtimeService.createProcessInstanceQuery().processInstanceId(parentId).singleResult(),
                    "主流程应已办结");

            HistoricProcessInstance parentAfter = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(parentId).singleResult();
            assertNotNull(parentAfter, "主流程历史实例不存在");
            assertNotNull(parentAfter.getEndTime(), "主流程应有结束时间");
            assertNull(parentAfter.getDeleteReason(), "主流程是正常办结，不能被记成删除/终止");

            assertNull(runtimeService.createProcessInstanceQuery().processInstanceId(childId).singleResult(),
                    "主流程办结后部门子流程应被级联终止，否则会留下孤儿流程");
            assertEquals(0, taskService.createTaskQuery().processInstanceId(childId).count(),
                    "主流程办结后部门任务不应残留");

            HistoricProcessInstance childAfter = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(childId).singleResult();
            assertNotNull(childAfter, "级联终止后子流程历史必须保留，否则跟踪图无从渲染");
            assertNotNull(childAfter.getEndTime(), "级联终止后子流程应有结束时间");
            assertNotNull(childAfter.getDeleteReason(), "级联终止必须留下删除原因，前端据此显示「已终止」");
            assertTrue(childAfter.getDeleteReason().contains("级联终止"),
                    "删除原因应说明是主流程办结级联，实际：" + childAfter.getDeleteReason());

            assertEquals(BizCommTask.STATUS_WITHDRAWN, recordStatus(recordId),
                    "级联终止后业务记录应按已收回收尾，不能继续显示待反馈");
            assertNotNull(withdrawTimeOf(recordId),
                    "级联终止须收尾收回时间，否则前端显示不出收尾时刻");

            // ----- 3.2 跟踪图：子流程自己 + 已结束的父流程 -----
            childTrace = processTraceService.trace(childId);
            parentTrace = processTraceService.trace(parentId);
        } finally {
            engineLogger.detachAppender(engineAppender);
            traceLogger.detachAppender(traceAppender);
            cascadeLogger.detachAppender(cascadeAppender);
            cascadeLogger.setLevel(cascadeOriginalLevel);
        }

        // ===== 4. 子流程跟踪图 =====
        assertEquals("FINISHED", childTrace.get("state"), "已终止实例不应仍被标为 RUNNING");
        assertNotNull(childTrace.get("endTime"), "已终止实例必须有结束时间");
        String deleteReason = (String) childTrace.get("deleteReason");
        assertNotNull(deleteReason, "已终止实例必须带删除原因");
        assertTrue(deleteReason.contains("级联终止"),
                "删除原因应体现级联语义，实际：" + deleteReason);

        // 4.1 父级入口：父实例已结束，回溯仍需通（历史任务与业务记录都还在）
        assertEquals(parentId, childTrace.get("parentProcessInstanceId"),
                "父流程办结后仍应沿沟通记录回溯到主实例；为空说明「返回上级」整块消失");
        assertEquals("deptComm", childTrace.get("parentSource"),
                "父级来源应标为部门沟通，前端才能提示「来自 xx 节点的沟通」");
        assertEquals("manager_review", childTrace.get("parentActivityId"), "应带出发起沟通的节点");
        assertEquals(PARENT_TITLE, childTrace.get("parentProcessTitle"),
                "父实例已结束，标题必须回落到历史变量；取不到说明 processTitle 还在查运行期");
        assertNotNull(childTrace.get("parentProcessDefinitionName"),
                "父流程定义名应有值，面包屑不能只剩标题");

        // 4.2 节点：已终止实例不得留着「执行中」的节点
        List<String> activeNodeIds = (List<String>) childTrace.get("activeNodeIds");
        assertNotNull(activeNodeIds, "跟踪数据缺少 activeNodeIds");
        assertTrue(activeNodeIds.isEmpty(), "实例已终止，不应还有执行中的节点，实际：" + activeNodeIds);
        assertFalse(((List<String>) childTrace.get("completedNodeIds")).isEmpty(), "已走过的节点应归入已完成");

        Map<String, Object> handleNode = node(childTrace, DEPT_TASK_KEY);
        assertEquals(STATUS_COMPLETED, handleNode.get("status"),
                "被终止时正在执行的部门任务应收口为已完成，不能一直挂着「执行中」");
        assertEquals(Boolean.FALSE, handleNode.get("running"), "已终止实例的节点不应标为 running");
        assertEquals(Boolean.FALSE, handleNode.get("pendingClaim"),
                "已终止任务不能被标成「待认领」，否则会指向一个没人能接的任务");
        assertEquals(STATUS_PENDING, node(childTrace, "dept_end").get("status"),
                "从未到达的结束节点应保持未开始，不能跟着一起标成完成");

        // ===== 5. 父流程跟踪图：正常办结 + 下钻入口仍在 =====
        assertEquals("FINISHED", parentTrace.get("state"), "主流程应显示为已结束");
        assertNotNull(parentTrace.get("endTime"), "主流程应有结束时间");
        assertNull(parentTrace.get("deleteReason"), "主流程正常办结不应有删除原因，否则前端会显示成「已终止」");
        assertEquals(PARENT_TITLE, parentTrace.get("processTitle"),
                "父标题必须回落到历史变量；运行期已查不到它");
        assertNull(parentTrace.get("parentProcessInstanceId"), "主流程没有上级");
        assertTrue(((List<String>) parentTrace.get("activeNodeIds")).isEmpty(),
                "主流程已办结，不应还有执行中的节点，实际：" + parentTrace.get("activeNodeIds"));
        assertEquals(STATUS_COMPLETED, node(parentTrace, "end").get("status"), "结束节点应标为已完成");

        Map<String, Object> managerNode = node(parentTrace, "manager_review");
        assertEquals(STATUS_COMPLETED, managerNode.get("status"), "经理审批应已完成");
        assertEquals(Boolean.FALSE, managerNode.get("running"), "已办结节点不应标为 running");

        List<Map<String, Object>> called = (List<Map<String, Object>>) managerNode.get("calledInstances");
        assertNotNull(called, "经理审批节点缺少 calledInstances");
        Map<String, Object> drill = called.stream()
                .filter(item -> childId.equals(item.get("processInstanceId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "主流程办结后下钻列表里仍应保留已终止的子实例 " + childId + "，实际：" + called));
        assertEquals("deptComm", drill.get("source"), "下钻来源应标为部门沟通：" + drill);
        assertEquals("FINISHED", drill.get("state"), "已终止子实例不应在下钻列表里显示为运行中：" + drill);
        assertNotNull(drill.get("deleteReason"), "下钻项应带删除原因，前端才能显示「已终止」：" + drill);
        assertEquals(DEPT_IT_NAME, drill.get("targetName"), "下钻项应带部门名：" + drill);
        // 下钻项的 processTitle 目前是流程定义名，这里只守住「不得串成父标题」这条底线
        assertNotEquals(PARENT_TITLE, drill.get("processTitle"), "下钻项标题不得串到父流程上：" + drill);

        List<Map<String, Object>> commTasks = (List<Map<String, Object>>) managerNode.get("commTasks");
        assertNotNull(commTasks, "经理审批节点缺少沟通记录");
        Map<String, Object> deptComm = commTasks.stream()
                .filter(c -> BizCommTask.TARGET_DEPT.equals(c.get("targetType")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("跟踪图里找不到部门沟通记录，实际：" + commTasks));
        assertEquals(BizCommTask.STATUS_WITHDRAWN, deptComm.get("status"), "沟通记录应显示已收回");
        assertEquals(Boolean.FALSE, deptComm.get("pending"), "已收回不应再算待反馈");
        assertNotNull(deptComm.get("withdrawTime"), "收回时间应外发到跟踪图");
        assertEquals(DEPT_IT_NAME, deptComm.get("targetName"), "应保留部门名");
        assertEquals(0, ((Number) managerNode.get("pendingCommCount")).intValue(), "已收回后不应再有待反馈");
        assertEquals(1, ((Number) managerNode.get("withdrawnCommCount")).intValue(), "节点应统计出 1 条已收回");

        Map<String, Object> stats = (Map<String, Object>) parentTrace.get("stats");
        assertEquals(0, ((Number) stats.get("active")).intValue(), "统计口径：执行中节点数应为 0");
        long completedNodes = ((List<Map<String, Object>>) parentTrace.get("nodes")).stream()
                .filter(n -> STATUS_COMPLETED.equals(n.get("status"))).count();
        assertEquals((int) completedNodes, ((Number) stats.get("completed")).intValue(),
                "统计口径：已完成数应等于节点中已完成的个数");

        // ===== 6. 日志：级联确实由 end 监听器完成，且取数不付出异常代价 =====
        List<String> cascadeMessages = cascadeAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertTrue(cascadeMessages.stream().anyMatch(m -> m.contains(childId)),
                "子流程应由 DeptCommCascadeListener 的 end 监听器终止；日志里没有该子实例，"
                        + "说明终止来自别的路径。实际：" + cascadeMessages);
        assertTrue(cascadeAppender.list.stream().noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.ERROR)),
                "级联终止不应失败，实际：" + cascadeMessages);

        List<ILoggingEvent> engineErrors = engineAppender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.ERROR))
                .toList();
        assertTrue(engineErrors.isEmpty(), "读取已终止实例不应触发引擎 ERROR，实际："
                + engineErrors.stream().map(ILoggingEvent::getFormattedMessage).toList());

        List<ILoggingEvent> traceWarns = traceAppender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .toList();
        assertTrue(traceWarns.isEmpty(), "父实例已结束但历史完整，回溯不应退化，实际日志："
                + traceWarns.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    /** 直接落一条待反馈的部门沟通记录：不经过 CommTaskFactory，因此不会锁住源待办 */
    private String insertPendingDeptRecord(Task rootTask) {
        String recordId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO BIZ_COMM_TASK_ (ID_, SESSION_ID_, SOURCE_TASK_ID_, ROOT_TASK_ID_, "
                        + "SOURCE_TASK_DEF_KEY_, TARGET_TYPE_, TARGET_ID_, TARGET_NAME_, CONTENT_, "
                        + "STATUS_, CREATE_TIME_) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                recordId, "SESSION-CASCADE-" + recordId, rootTask.getId(), rootTask.getId(),
                rootTask.getTaskDefinitionKey(), BizCommTask.TARGET_DEPT, DEPT_IT, DEPT_IT_NAME,
                CONTENT, BizCommTask.STATUS_PENDING, new Timestamp(System.currentTimeMillis()));
        return recordId;
    }

    private String recordStatus(String recordId) {
        return jdbc.queryForObject("SELECT STATUS_ FROM BIZ_COMM_TASK_ WHERE ID_ = ?", String.class, recordId);
    }

    private Timestamp withdrawTimeOf(String recordId) {
        return jdbc.queryForObject("SELECT WITHDRAW_TIME_ FROM BIZ_COMM_TASK_ WHERE ID_ = ?",
                Timestamp.class, recordId);
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
        throw new AssertionError("未找到节点 " + nodeId + "，实际：" + new ArrayList<>(nodes));
    }
}
