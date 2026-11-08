package com.example.camundademo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.security.DemoUserInitializer;
import com.example.camundademo.service.CommunicationService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部门沟通被「收回」终止后，跟踪图仍应显示父级入口，并如实标注为已终止。
 *
 * <p><b>为什么单开一条</b>：现有 {@code DeptCommFlowTest#withdrawTerminatesWholeDeptSubProcess}
 * 断言的是「子流程实例被终止、业务记录置为已收回」，看的是<b>运行期</b>与业务表；
 * {@code DeptCommTitleIsolationTest} 断言的是子流程<b>正常走完</b>后的父级回溯。
 * 两者都没有覆盖「实例被 {@code deleteProcessInstance} 终止」这一形态下的跟踪图：
 * 此时运行期什么都查不到，跟踪图只能靠历史表 + 业务表拼出来，最容易出现的退化是
 * <b>父级入口整块消失</b>（用户点进去后无法返回上级）或<b>实例被当成仍在运行</b>。</p>
 *
 * <p><b>场景</b>：主流程停在经理审批 → 发起部门沟通（真实建单，子流程独立启动）→
 * 发起人在部门反馈前收回 → 子流程被终止。随后分别打开子流程与主流程的跟踪图。</p>
 *
 * <p><b>口径说明</b>：{@code trace()} 的 {@code state} 只有 {@code RUNNING / FINISHED}
 * （见 {@code ProcessTraceService:788}），"已终止"由 {@code endTime} 非空 +
 * {@code deleteReason} 非空共同表达。用例按接口真实口径断言，不臆造第三种状态。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 独立内存库：本用例会真实建单、启动子流程并终止它，不能与其它测试类共用
        "spring.datasource.url=jdbc:h2:mem:deptcommwithdrawtrace;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class DeptCommWithdrawnTraceTest {

    private static final String DEPT_IT = "dept_it";
    private static final String DEPT_IT_NAME = "IT 部";
    private static final String DEPT_TASK_KEY = DeptCommProcessService.TASK_KEY;

    private static final String PARENT_TITLE_APPLY = "标题-主流程-发起";
    private static final String PARENT_TITLE = "标题-主流程-已提交";
    private static final String CONTENT = "请确认会议室与投屏设备";

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
    private CommunicationService communicationService;

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
                    .name("deptcomm-withdrawn-approval").deploy();
        }
        if (repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(DeptCommProcessService.PROCESS_KEY).count() == 0) {
            repositoryService.createDeployment()
                    .addClasspathResource("processes/dept_communication.bpmn")
                    .name("deptcomm-withdrawn-dept").deploy();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void withdrawnDeptCommShouldKeepParentEntryAndShowTerminated() {
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

        // ===== 2. 真实建单：发起部门沟通，子流程独立启动并回填实例ID =====
        String sessionId = communicationService.startCommunication(
                managerTask.getId(), "manager", CONTENT, List.of(deptTarget()));
        assertNotNull(sessionId, "部门沟通会话应创建成功");

        String recordId = jdbc.queryForObject(
                "SELECT ID_ FROM BIZ_COMM_TASK_ WHERE SESSION_ID_ = ? AND TARGET_TYPE_ = ?",
                String.class, sessionId, BizCommTask.TARGET_DEPT);
        assertNotNull(recordId, "业务表应落下一条部门沟通记录");

        String childId = jdbc.queryForObject(
                "SELECT DEPT_PROC_INST_ID_ FROM BIZ_COMM_TASK_ WHERE ID_ = ?", String.class, recordId);
        assertNotNull(childId, "部门沟通必须回填子流程实例ID，否则收回时无法终止整个子流程");

        // ===== 前置条件：子流程在运行、部门任务待承接、引擎里没有父子关系 =====
        assertNotNull(runtimeService.createProcessInstanceQuery().processInstanceId(childId).singleResult(),
                "部门沟通子流程应在运行中");
        Task deptTask = taskService.createTaskQuery().processInstanceId(childId)
                .taskDefinitionKey(DEPT_TASK_KEY).singleResult();
        assertNotNull(deptTask, "部门子流程里应有 dept_handle 任务");
        assertEquals(BizCommTask.STATUS_PENDING, recordStatus(recordId), "收回前记录应为待反馈");

        HistoricProcessInstance childBefore = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(childId).singleResult();
        assertNotNull(childBefore, "部门子流程历史不存在");
        assertNull(childBefore.getSuperProcessInstanceId(),
                "部门沟通在引擎里没有父子关系；若这里非空，本用例就落到 callActivity 分支了");
        assertNull(childBefore.getEndTime(), "收回前子流程不应有结束时间");

        // ===== 3. 发起人收回（真实入口），子流程被终止 =====
        // 全部收回：commTaskIds 传 null，由 withdrawCommunication 收集会话内全部 PENDING 记录
        assertEquals(1, communicationService.withdrawCommunication(sessionId, "manager", null),
                "应收回 1 条部门沟通");

        assertEquals(BizCommTask.STATUS_WITHDRAWN, recordStatus(recordId), "收回后记录应为已收回");
        assertNotNull(jdbc.queryForObject(
                        "SELECT WITHDRAW_TIME_ FROM BIZ_COMM_TASK_ WHERE ID_ = ?", java.util.Date.class, recordId),
                "收回时间应落在业务表上（引擎历史没有这个语义）");
        assertNull(runtimeService.createProcessInstanceQuery().processInstanceId(childId).singleResult(),
                "收回后子流程实例应已终止");

        // 前置条件：终止是「历史仍在、运行期消失」，这正是跟踪图必须靠历史拼装的形态
        HistoricProcessInstance childAfter = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(childId).singleResult();
        assertNotNull(childAfter, "终止后历史实例必须保留，否则跟踪图将无从渲染");
        assertNotNull(childAfter.getEndTime(), "终止后历史实例应有结束时间");
        assertNotNull(childAfter.getDeleteReason(), "终止后必须留下删除原因，前端据此区分「结束」与「终止」");
        assertTrue(childAfter.getDeleteReason().contains("收回"),
                "删除原因应说明是收回导致，实际：" + childAfter.getDeleteReason());
        assertNotNull(taskService.createTaskQuery().processInstanceId(parentId)
                        .taskDefinitionKey("manager_review").singleResult(),
                "部门沟通被收回后，父流程应仍停在经理审批（源待办未被删除）");

        // ===== 4. 跟踪图：子流程自己 =====
        Logger engineLogger = (Logger) LoggerFactory.getLogger("org.camunda.bpm.engine.context");
        Logger traceLogger = (Logger) LoggerFactory.getLogger(ProcessTraceService.class);
        ListAppender<ILoggingEvent> engineAppender = new ListAppender<>();
        ListAppender<ILoggingEvent> traceAppender = new ListAppender<>();
        engineAppender.start();
        traceAppender.start();
        engineLogger.addAppender(engineAppender);
        traceLogger.addAppender(traceAppender);

        Map<String, Object> parentTrace;
        Map<String, Object> childTrace;
        try {
            childTrace = processTraceService.trace(childId);
            parentTrace = processTraceService.trace(parentId);
        } finally {
            engineLogger.detachAppender(engineAppender);
            traceLogger.detachAppender(traceAppender);
        }

        // 4.1 状态：接口只有两值，终止由 endTime + deleteReason 表达
        assertEquals("FINISHED", childTrace.get("state"), "已终止实例不应仍被标为 RUNNING");
        assertNotNull(childTrace.get("endTime"), "已终止实例必须有结束时间");
        String deleteReason = (String) childTrace.get("deleteReason");
        assertNotNull(deleteReason, "已终止实例必须带删除原因，否则前端无法显示「已终止」");
        assertTrue(deleteReason.contains("收回"), "删除原因应体现收回语义，实际：" + deleteReason);

        // 4.2 父级入口：收回不影响回溯，仍应能返回上级
        assertEquals(parentId, childTrace.get("parentProcessInstanceId"),
                "收回后仍应沿沟通记录回溯到主实例；为空说明「返回上级」整块消失了");
        assertEquals("deptComm", childTrace.get("parentSource"),
                "父级来源应标为部门沟通，前端才能提示「来自 xx 节点的沟通」");
        assertEquals("manager_review", childTrace.get("parentActivityId"),
                "应带出发起沟通的节点");
        assertEquals(PARENT_TITLE, childTrace.get("parentProcessTitle"),
                "「返回上级」必须显示父自己的标题");
        assertNotNull(childTrace.get("parentProcessDefinitionName"),
                "父流程定义名应有值，面包屑不能只剩标题");

        // 4.3 节点：已终止实例不得留着「执行中」的节点
        List<String> activeNodeIds = (List<String>) childTrace.get("activeNodeIds");
        assertNotNull(activeNodeIds, "跟踪数据缺少 activeNodeIds");
        assertTrue(activeNodeIds.isEmpty(),
                "实例已终止，不应还有执行中的节点，实际：" + activeNodeIds);
        assertFalse(((List<String>) childTrace.get("completedNodeIds")).isEmpty(),
                "已走过的节点应归入已完成");

        Map<String, Object> handleNode = node(childTrace, DEPT_TASK_KEY);
        assertEquals(STATUS_COMPLETED, handleNode.get("status"),
                "被终止时正在执行的部门任务应收口为已完成，不能一直挂着「执行中」");
        assertEquals(Boolean.FALSE, handleNode.get("running"), "已终止实例的节点不应标为 running");
        assertEquals(Boolean.FALSE, handleNode.get("pendingClaim"),
                "已终止任务不能被标成「待认领」，否则会指向一个没人能接的任务");

        Map<String, Object> notStarted = node(childTrace, "dept_end");
        assertEquals(STATUS_PENDING, notStarted.get("status"),
                "从未到达的结束节点应保持未开始，不能跟着一起标成完成");

        Map<String, Object> stats = (Map<String, Object>) childTrace.get("stats");
        assertEquals(0, ((Number) stats.get("active")).intValue(), "统计口径：执行中节点数应为 0");
        assertEquals(activeNodeIds.size(), ((Number) stats.get("active")).intValue(),
                "统计与节点列表必须自洽");
        long completedNodes = ((List<Map<String, Object>>) childTrace.get("nodes")).stream()
                .filter(n -> STATUS_COMPLETED.equals(n.get("status"))).count();
        assertEquals((int) completedNodes, ((Number) stats.get("completed")).intValue(),
                "统计口径：已完成数应等于节点中已完成的个数");

        // ===== 5. 跟踪图：父流程上的下钻入口与沟通记录 =====
        assertEquals("RUNNING", parentTrace.get("state"), "收回部门沟通不影响父流程运行");
        assertEquals(PARENT_TITLE, parentTrace.get("processTitle"), "父标题不应被子流程影响");
        assertNull(parentTrace.get("parentProcessInstanceId"), "主流程没有上级");

        Map<String, Object> managerNode = node(parentTrace, "manager_review");
        assertEquals(STATUS_ACTIVE, managerNode.get("status"), "收回顾问后经理审批仍应可办理");

        List<Map<String, Object>> called = (List<Map<String, Object>>) managerNode.get("calledInstances");
        assertNotNull(called, "经理审批节点缺少 calledInstances");
        Map<String, Object> drill = called.stream()
                .filter(item -> childId.equals(item.get("processInstanceId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "收回后下钻列表里仍应保留已终止的子实例 " + childId + "，实际：" + called));
        assertEquals("deptComm", drill.get("source"), "下钻来源应标为部门沟通：" + drill);
        assertEquals("FINISHED", drill.get("state"), "已终止子实例不应在下钻列表里显示为运行中：" + drill);
        assertNotNull(drill.get("deleteReason"), "下钻项应带删除原因，前端才能显示「已终止」：" + drill);
        assertEquals(DEPT_IT_NAME, drill.get("targetName"), "下钻项应带部门名：" + drill);

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
        assertEquals(0, ((Number) managerNode.get("pendingCommCount")).intValue(),
                "已收回后不应再有待反馈");
        assertEquals(1, ((Number) managerNode.get("withdrawnCommCount")).intValue(),
                "节点应统计出 1 条已收回");

        // ===== 6. 日志：读取已终止实例不应付出异常代价 =====
        List<ILoggingEvent> engineErrors = engineAppender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.ERROR))
                .toList();
        assertTrue(engineErrors.isEmpty(),
                "读取已终止实例不应触发引擎 ERROR，实际："
                        + engineErrors.stream().map(ILoggingEvent::getFormattedMessage).toList());
        List<ILoggingEvent> traceWarns = traceAppender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .toList();
        assertTrue(traceWarns.isEmpty(),
                "源任务仍存在，父级回溯不应退化，实际日志："
                        + traceWarns.stream().map(ILoggingEvent::getFormattedMessage).toList());

        // 清理：父流程仍在运行
        runtimeService.deleteProcessInstance(parentId, "test cleanup");
    }

    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_PENDING = "PENDING";

    private Map<String, String> deptTarget() {
        Map<String, String> target = new LinkedHashMap<>();
        target.put("type", BizCommTask.TARGET_DEPT);
        target.put("id", DEPT_IT);
        target.put("name", DEPT_IT_NAME);
        return target;
    }

    private String recordStatus(String recordId) {
        return jdbc.queryForObject("SELECT STATUS_ FROM BIZ_COMM_TASK_ WHERE ID_ = ?", String.class, recordId);
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
