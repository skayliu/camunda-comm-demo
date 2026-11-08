package com.example.camundademo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.security.DemoUserInitializer;
import com.example.camundademo.service.DeptCommProcessService;
import com.example.camundademo.service.ProcessTraceService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.runtime.VariableInstance;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部门沟通子流程与主流程各持同名 {@code title} 时，跟踪图标题必须按实例隔离。
 *
 * <p><b>为什么单开一条</b>：{@code ParentChildTitleIsolationTest} 守的是 callActivity 分支——
 * 父子关系写在引擎的 {@code SUPER_PROC_INST_ID_} 上。部门沟通走的是完全不同的另一条：
 * 子流程由 {@code startProcessInstanceByKey} 独立启动，引擎表里没有父子关系，
 * {@code parentInstanceRef} 只能沿 {@code BIZ_COMM_TASK_.SOURCE_TASK_ID_} 找回发起沟通的历史任务，
 * 再取其所属实例。这条回溯链路一旦把 {@code parent} 与 {@code hpi} 传反、或退化成取当前实例的标题，
 * <b>返回的标题依然非空、依然像模像样</b>，只有父子各持同名 title 时才看得出来。</p>
 *
 * <p><b>场景</b>：主流程 {@code approval} 停在经理审批（父 title = 主流程值）→ 发起部门沟通，
 * 子流程独立启动后写入自己的 title（同名不同值）→ 部门成员反馈，子流程结束。
 * 此时父流程仍在运行、子流程已归档：子标题只能来自历史变量，父标题来自运行期变量。
 * 若 {@code parentProcessTitle} 误取子实例标题，或子实例标题误取父实例标题，用例立即失败。</p>
 *
 * <p>与 {@code DeptCommFlowTest#traceShowsDeptCommAlongsideUserComm} 的区别：那条只断言
 * 父标题「非空」，本用例断言的是「等于父自己的值、且不等于子值」——只判非空是抓不到串数据的。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 独立内存库：本用例会建沟通记录与两套流程实例，不能与其它测试类共用
        "spring.datasource.url=jdbc:h2:mem:deptcommtitle;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class DeptCommTitleIsolationTest {

    private static final String DEPT_IT = "dept_it";
    private static final String DEPT_IT_NAME = "IT 部";
    private static final String DEPT_TASK_KEY = DeptCommProcessService.TASK_KEY;

    private static final String PARENT_TITLE_APPLY = "标题-主流程-发起";
    private static final String PARENT_TITLE = "标题-主流程-已提交";
    private static final String CHILD_TITLE = "标题-部门沟通-独占";

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

    /** 部门组与演示账号要就位，否则 dept_communication 的候选组没有人能认领 */
    @BeforeEach
    void ensureDemoIdentity() {
        demoUserInitializer.run(null);
        jdbc = new JdbcTemplate(dataSource);
        if (repositoryService.createProcessDefinitionQuery().processDefinitionKey("approval").count() == 0) {
            repositoryService.createDeployment()
                    .addClasspathResource("processes/approval.bpmn")
                    .name("deptcomm-title-approval").deploy();
        }
        if (repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(DeptCommProcessService.PROCESS_KEY).count() == 0) {
            repositoryService.createDeployment()
                    .addClasspathResource("processes/dept_communication.bpmn")
                    .name("deptcomm-title-dept").deploy();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void deptCommChildTitleShouldNotLeakToParent() {
        // ===== 1. 主流程推进到经理审批，父实例持有父标题 =====
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

        // ===== 2. 发起部门沟通：子流程由 startProcessInstanceByKey 独立启动 =====
        String recordId = insertPendingDeptRecord(managerTask);
        String sessionId = "SESSION-TITLE-" + recordId;
        DeptCommProcessService.Started started = deptCommProcessService.start(
                recordId, sessionId, managerTask, DEPT_IT, DEPT_IT_NAME,
                "请确认服务器扩容方案", "manager", 1);
        String childId = started.getProcessInstanceId();
        assertNotNull(childId, "部门沟通子流程未启动");
        // DeptCommProcessService.start 只负责起流程；把「子流程实例ID / 部门任务ID」回填到业务记录
        // 是 CommTaskFactory 建单路径做的事。这里走服务层构造现场，必须自己补上——
        // 跟踪图的下钻入口正是按 DEPT_PROC_INST_ID_ 并入 calledInstances 的，缺了就是空列表。
        jdbc.update("UPDATE BIZ_COMM_TASK_ SET DEPT_PROC_INST_ID_ = ?, CAMUNDA_TASK_ID_ = ? WHERE ID_ = ?",
                childId, started.getTaskId(), recordId);

        // 子流程写入自己的 title：与父同名、不同值。部门沟通子流程不继承父变量，
        // 只能由业务侧显式写入，这也正是生产里“子流程有自己的标题”的真实形态。
        runtimeService.setVariable(childId, "title", CHILD_TITLE);

        // ===== 前置条件：确认「同名不同值」与「引擎里没有父子关系」都成立 =====
        assertEquals(CHILD_TITLE, liveTitle(childId), "子实例运行期应持有自己的 title");
        assertEquals(PARENT_TITLE, liveTitle(parentId), "父实例运行期应保留父标题");
        assertNotEquals(liveTitle(parentId), liveTitle(childId), "父子标题必须不同，否则测不出串数据");

        HistoricProcessInstance childHistory = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(childId).singleResult();
        assertNotNull(childHistory, "部门沟通子流程历史不存在");
        assertEquals(recordId, childHistory.getBusinessKey(),
                "子流程业务主键应是沟通记录ID，parentInstanceRef 靠它回溯父实例");
        assertNull(childHistory.getSuperProcessInstanceId(),
                "部门沟通在引擎里没有父子关系；若这里非空，本用例就变成 callActivity 分支了");

        // ===== 3. 部门成员认领并反馈，子流程结束 =====
        Task deptTask = taskService.createTaskQuery().processInstanceId(childId)
                .taskDefinitionKey(DEPT_TASK_KEY).singleResult();
        assertNotNull(deptTask, "部门子流程里应有 dept_handle 任务");
        assertNull(deptTask.getAssignee(), "部门任务应由候选组承接，不预分派个人");
        taskService.claim(deptTask.getId(), "alice");
        taskService.complete(deptTask.getId(), Map.of("deptReply", "扩容方案已确认"));

        // ===== 前置条件：子已结束、父仍在运行 → 两个标题分别来自历史变量与运行期变量 =====
        assertNull(runtimeService.createProcessInstanceQuery().processInstanceId(childId).singleResult(),
                "部门反馈后子流程应已结束");
        assertNotNull(taskService.createTaskQuery().processInstanceId(parentId)
                        .taskDefinitionKey("manager_review").singleResult(),
                "父流程应仍停在经理审批节点");
        assertNull(liveTitle(childId), "子实例已结束，运行期不应再有它的 title 变量");
        assertEquals(CHILD_TITLE, historicTitle(childId), "历史变量中子实例的 title 应为独占值");
        assertEquals(PARENT_TITLE, historicTitle(parentId), "历史变量中父实例的 title 应仍是父自己的值");

        // ===== 4. 关键断言：两条回溯链路各取各的标题 =====
        Logger engineLogger = (Logger) LoggerFactory.getLogger("org.camunda.bpm.engine.context");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        engineLogger.addAppender(appender);
        Map<String, Object> parentTrace;
        Map<String, Object> childTrace;
        try {
            parentTrace = processTraceService.trace(parentId);
            childTrace = processTraceService.trace(childId);
        } finally {
            engineLogger.detachAppender(appender);
        }

        assertEquals("RUNNING", parentTrace.get("state"));
        assertEquals(PARENT_TITLE, parentTrace.get("processTitle"),
                "主流程跟踪图标题必须是父自己的标题，不能被部门子流程的 title 覆盖");
        assertNull(parentTrace.get("parentProcessInstanceId"),
                "主流程没有上级，不应凭空出现「返回上级」");

        assertEquals("FINISHED", childTrace.get("state"));
        assertEquals(CHILD_TITLE, childTrace.get("processTitle"),
                "部门子流程标题必须是自己的 title；显示父标题说明标题按实例过滤失效");
        assertNotEquals(PARENT_TITLE, childTrace.get("processTitle"),
                "子流程标题不得回落成父标题");

        // 这三条正是 parentInstanceRef 的业务回溯分支：引擎没有父子关系，只能靠记录反查
        assertEquals(parentId, childTrace.get("parentProcessInstanceId"),
                "部门子流程应沿沟通记录回溯到主实例，前端据此显示「返回上级」");
        assertEquals("deptComm", childTrace.get("parentSource"),
                "父实例来源应标为部门沟通，前端才能提示「来自 xx 节点的沟通」");
        assertEquals("manager_review", childTrace.get("parentActivityId"),
                "应带出发起沟通的节点");
        assertEquals(PARENT_TITLE, childTrace.get("parentProcessTitle"),
                "「返回上级」必须显示父标题；显示子标题说明 parentRef 传错了实例");
        assertNotNull(childTrace.get("parentProcessDefinitionName"),
                "父流程定义名应有值，面包屑不能只剩标题");

        // ===== 5. 主流程图上的下钻入口：指向子实例，且不能把父标题当成子标题 =====
        Map<String, Object> managerNode = node(parentTrace, "manager_review");
        List<Map<String, Object>> called = (List<Map<String, Object>>) managerNode.get("calledInstances");
        assertNotNull(called, "经理审批节点缺少 calledInstances");
        assertTrue(called.size() >= 1, "部门沟通实例应进入下钻列表：" + called);
        Map<String, Object> drill = called.stream()
                .filter(item -> childId.equals(item.get("processInstanceId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "下钻列表里没有部门子流程实例 " + childId + "，实际：" + called));
        assertEquals("deptComm", drill.get("source"), "下钻来源应标为部门沟通：" + drill);
        assertEquals("FINISHED", drill.get("state"), "子实例已结束，下钻状态应为 FINISHED：" + drill);
        assertEquals(DEPT_IT_NAME, drill.get("targetName"), "下钻项应带部门名，便于区分给谁发的：" + drill);
        assertNotEquals(PARENT_TITLE, drill.get("processTitle"),
                "下钻列表不能显示父实例标题，否则用户会误以为将要打开的是父流程");

        // ===== 6. 引擎日志：不得为取标题付出 ERROR 代价 =====
        List<ILoggingEvent> engineErrors = appender.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR))
                .toList();
        assertTrue(engineErrors.isEmpty(),
                "部门沟通读取标题不应触发引擎 ERROR，实际："
                        + engineErrors.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    /** 插入一条待反馈的部门沟通记录；监听器只在会话存在时才做收尾，这里给占位会话ID即可 */
    private String insertPendingDeptRecord(Task rootTask) {
        String recordId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO BIZ_COMM_TASK_ (ID_, SESSION_ID_, SOURCE_TASK_ID_, ROOT_TASK_ID_, "
                        + "SOURCE_TASK_DEF_KEY_, TARGET_TYPE_, TARGET_ID_, TARGET_NAME_, CONTENT_, "
                        + "STATUS_, CREATE_TIME_) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                recordId, "SESSION-TITLE-" + recordId, rootTask.getId(), rootTask.getId(),
                rootTask.getTaskDefinitionKey(), BizCommTask.TARGET_DEPT, DEPT_IT, DEPT_IT_NAME,
                "请确认服务器扩容方案", BizCommTask.STATUS_PENDING,
                new Timestamp(System.currentTimeMillis()));
        return recordId;
    }

    /** 运行期 title；实例已结束时返回 null（变量查询不会抛异常，这是修复 ENGINE-16004 的口径） */
    private Object liveTitle(String processInstanceId) {
        List<VariableInstance> titles = runtimeService.createVariableInstanceQuery()
                .processInstanceIdIn(processInstanceId).variableName("title").list();
        return titles.isEmpty() ? null : titles.get(0).getValue();
    }

    private Object historicTitle(String processInstanceId) {
        List<org.camunda.bpm.engine.history.HistoricVariableInstance> titles =
                historyService.createHistoricVariableInstanceQuery()
                        .processInstanceId(processInstanceId).variableName("title").list();
        assertEquals(1, titles.size(),
                "实例 " + processInstanceId + " 的历史 title 应恰好一条，实际：" + titles.size());
        return titles.get(0).getValue();
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
        throw new AssertionError("未找到节点 " + nodeId);
    }
}
