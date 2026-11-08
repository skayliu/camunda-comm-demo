package com.example.camundademo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.service.BusinessException;
import com.example.camundademo.security.DemoUserInitializer;
import com.example.camundademo.service.DeptCommProcessService;
import com.example.camundademo.service.ProcessTraceService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部门沟通子流程的父实例已被清理时，跟踪图必须降级为「没有返回上级」，而不是报错。
 *
 * <p><b>为什么这条单独测</b>：{@code DeptCommTitleIsolationTest} 守的是「源任务还在，
 * 沿 {@code BIZ_COMM_TASK_.SOURCE_TASK_ID_} 能找回父实例」的正常分支。
 * 但生产里还有一条退化路径：</p>
 *
 * <ul>
 *   <li>发起人在主流程上撤件 / 流程实例被后台清理，源任务连同历史记录一起消失；</li>
 *   <li>沟通业务记录 {@code BIZ_COMM_TASK_} 仍然保留，{@code SOURCE_TASK_ID_} 还指向那个
 *       已经不存在的任务——这正是最容易写出 {@code sourceTask.getProcessInstanceId()} 空指针的现场；</li>
 *   <li>此时打开部门子流程跟踪图，前端不应出现「返回上级」，接口更不应 500。</li>
 * </ul>
 *
 * <p>用例不只断言「不抛异常」：它还确认降级只影响父引用这一处，
 * 子流程自己的标题、状态、节点、统计必须完全正常。否则很容易用「整体返回空」来掩盖问题，
 * 页面上表现为跟踪图能打开但一片空白，比 500 更难排查。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 独立内存库：本用例会物理清理一个流程实例的历史，不能与其它测试类共用
        "spring.datasource.url=jdbc:h2:mem:deptcommparentmissing;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class DeptCommParentMissingTest {

    private static final String DEPT_IT = "dept_it";
    private static final String DEPT_IT_NAME = "IT 部";
    private static final String DEPT_TASK_KEY = DeptCommProcessService.TASK_KEY;

    private static final String PARENT_TITLE = "标题-主流程-已提交";
    private static final String CHILD_TITLE = "标题-部门沟通-独立";

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

    @BeforeEach
    void ensureDemoIdentity() {
        demoUserInitializer.run(null);
        jdbc = new JdbcTemplate(dataSource);
        if (repositoryService.createProcessDefinitionQuery().processDefinitionKey("approval").count() == 0) {
            repositoryService.createDeployment()
                    .addClasspathResource("processes/approval.bpmn")
                    .name("deptcomm-parent-missing-approval").deploy();
        }
        if (repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(DeptCommProcessService.PROCESS_KEY).count() == 0) {
            repositoryService.createDeployment()
                    .addClasspathResource("processes/dept_communication.bpmn")
                    .name("deptcomm-parent-missing-dept").deploy();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void cleanedSourceTaskShouldDegradeToNoParentEntry() {
        // ===== 1. 主流程停在经理审批，发起部门沟通 =====
        ProcessInstance parent = runtimeService.startProcessInstanceByKey("approval",
                Map.of("title", "标题-主流程-发起", "applicant", "applicant"));
        String parentId = parent.getId();

        Task apply = taskService.createTaskQuery().processInstanceId(parentId)
                .taskDefinitionKey("apply").singleResult();
        assertNotNull(apply, "主流程应停在发起节点");
        taskService.complete(apply.getId(), Map.of("title", PARENT_TITLE));

        Task managerTask = taskService.createTaskQuery().processInstanceId(parentId)
                .taskDefinitionKey("manager_review").singleResult();
        assertNotNull(managerTask, "主流程应停在经理审批节点");
        String sourceTaskId = managerTask.getId();

        String recordId = insertPendingDeptRecord(managerTask);
        DeptCommProcessService.Started started = deptCommProcessService.start(
                recordId, "SESSION-PARENT-MISSING-" + recordId, managerTask,
                DEPT_IT, DEPT_IT_NAME, "请确认服务器扩容方案", "manager", 1);
        String childId = started.getProcessInstanceId();
        assertNotNull(childId, "部门沟通子流程未启动");
        jdbc.update("UPDATE BIZ_COMM_TASK_ SET DEPT_PROC_INST_ID_ = ?, CAMUNDA_TASK_ID_ = ? WHERE ID_ = ?",
                childId, started.getTaskId(), recordId);
        runtimeService.setVariable(childId, "title", CHILD_TITLE);

        // ===== 2. 部门反馈，子流程结束（历史完整保留） =====
        Task deptTask = taskService.createTaskQuery().processInstanceId(childId)
                .taskDefinitionKey(DEPT_TASK_KEY).singleResult();
        assertNotNull(deptTask, "部门子流程里应有 dept_handle 任务");
        taskService.claim(deptTask.getId(), "alice");
        taskService.complete(deptTask.getId(), Map.of("deptReply", "扩容方案已确认"));

        assertNull(runtimeService.createProcessInstanceQuery().processInstanceId(childId).singleResult(),
                "部门反馈后子流程应已结束");

        // ===== 3. 前置条件：清理之前，回溯是通的 =====
        // 这一条必须存在，否则后面的「查不到」可能只是因为一开始就没接上，用例会失去区分度。
        Map<String, Object> before = processTraceService.trace(childId);
        assertEquals(parentId, before.get("parentProcessInstanceId"),
                "清理前部门子流程应能沿沟通记录回溯到父实例：" + before.get("parentProcessInstanceId"));
        assertEquals("deptComm", before.get("parentSource"), "清理前来源应为部门沟通");
        assertEquals("manager_review", before.get("parentActivityId"), "清理前应带出发起沟通的节点");

        // ===== 4. 构造退化现场：主流程撤件，源任务连同历史一起被清理 =====
        // 先删运行期再删历史，顺序不能反：历史删除接口对仍在运行的实例会直接拒绝。
        runtimeService.deleteProcessInstance(parentId, "发起人撤件");
        historyService.deleteHistoricProcessInstance(parentId);

        // 前置条件：这三条就是「源任务确实不存在、记录仍在、线索仍指向它」的证据
        assertEquals(0, historyService.createHistoricProcessInstanceQuery()
                        .processInstanceId(parentId).count(),
                "父实例历史应已被清理，否则测的仍是正常回溯分支");
        assertEquals(0, historyService.createHistoricTaskInstanceQuery()
                        .taskId(sourceTaskId).count(),
                "源任务历史应已不存在，parentInstanceRef 查不到父实例");
        Map<String, Object> record = jdbc.queryForMap(
                "SELECT STATUS_, SOURCE_TASK_ID_, DEPT_PROC_INST_ID_ FROM BIZ_COMM_TASK_ WHERE ID_ = ?",
                recordId);
        assertEquals(sourceTaskId, record.get("SOURCE_TASK_ID_"),
                "沟通记录仍保留着已消失的源任务ID，这正是会写出空指针的现场");
        assertEquals(childId, record.get("DEPT_PROC_INST_ID_"),
                "子流程实例ID不应受主流程清理影响");

        // ===== 5. 关键断言：跟踪图正常返回，只是没有「返回上级」 =====
        Logger engineLogger = (Logger) LoggerFactory.getLogger("org.camunda.bpm.engine.context");
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(ProcessTraceService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        ListAppender<ILoggingEvent> serviceAppender = new ListAppender<>();
        appender.start();
        serviceAppender.start();
        engineLogger.addAppender(appender);
        serviceLogger.addAppender(serviceAppender);
        Map<String, Object> after;
        try {
            // 不抛异常 = 接口不会 500；若 sourceTask 的空值防护被去掉，这里会 NPE
            after = processTraceService.trace(childId);
        } finally {
            engineLogger.detachAppender(appender);
            serviceLogger.detachAppender(serviceAppender);
        }

        assertNotNull(after, "父实例被清理后，子流程跟踪图仍应正常返回");
        assertEquals(childId, after.get("processInstanceId"), "返回的必须是子流程自己");
        assertEquals("FINISHED", after.get("state"), "子流程状态不应受父实例清理影响");
        assertEquals(CHILD_TITLE, after.get("processTitle"),
                "子流程标题必须取自自己的历史变量，不能因为父实例没了就变空");

        // 五个 parent* 字段同时为空，前端据此不渲染「返回上级」
        assertNull(after.get("parentProcessInstanceId"),
                "父实例已不存在，不得返回一个点进去就 404 的实例ID");
        assertNull(after.get("parentProcessTitle"), "父实例不存在时不应残留父标题");
        assertNull(after.get("parentProcessDefinitionName"), "父实例不存在时不应残留父定义名");
        assertNull(after.get("parentActivityId"), "父实例不存在时不应残留发起节点");
        assertNull(after.get("parentSource"), "父实例不存在时不应标注来源");

        // 降级只发生在父引用这一处：节点、状态、统计必须完好，不能整体返回空图
        assertTrue(after.get("diagramAvailable") instanceof Boolean
                        && (Boolean) after.get("diagramAvailable"),
                "流程图定义仍应可用，父实例清理不应影响子流程的图");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) after.get("nodes");
        assertNotNull(nodes, "跟踪数据缺少节点");
        Map<String, Object> deptNode = node(after, DEPT_TASK_KEY);
        assertEquals("COMPLETED", deptNode.get("status"),
                "部门处理节点应保持已完成状态：" + deptNode.get("status"));
        // 子流程是 start → dept_handle → end 三个节点，走完后应当全部完成
        assertEquals("COMPLETED", node(after, "dept_end").get("status"),
                "部门沟通结束事件应已完成");
        Map<String, Object> stats = (Map<String, Object>) after.get("stats");
        assertNotNull(stats, "缺少节点统计");
        long completedNodes = nodes.stream()
                .filter(n -> "COMPLETED".equals(String.valueOf(n.get("status"))))
                .count();
        assertEquals(nodes.size(), completedNodes,
                "子流程已结束，图中所有节点都应为已完成，实际：" + completedNodes + "/" + nodes.size());
        assertEquals((int) completedNodes, ((Number) stats.get("completed")).intValue(),
                "统计的已完成数必须与节点状态一致，避免统计与图各说各的");

        // ===== 6. 引擎日志：降级不能以引擎 ERROR 为代价 =====
        List<ILoggingEvent> engineErrors = appender.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR))
                .toList();
        assertTrue(engineErrors.isEmpty(),
                "父实例缺失不应触发引擎 ERROR，实际："
                        + engineErrors.stream().map(ILoggingEvent::getFormattedMessage).toList());

        // 只断言「不抛异常」还不够：sourceTask 的判空被删掉时，NPE 会被外层 catch 兜住，
        // 返回值照样是空 parentRef，用例会误通过；但每次打开跟踪图都会留下一条 WARN 堆栈。
        // 已知的正常退化不应以异常收场，因此这里把 WARN 也一并守住。
        List<ILoggingEvent> serviceNoise = serviceAppender.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .toList();
        assertTrue(serviceNoise.isEmpty(),
                "源任务缺失是已知退化，不应留下 WARN/ERROR 异常日志，实际："
                        + serviceNoise.stream().map(ILoggingEvent::getFormattedMessage).toList());

        // ===== 7. 边界：实例本身不存在时仍应明确报业务异常，而不是 NPE =====
        // 父实例被清理是「父引用缺失」，子实例被清理是「要看的实例不存在」，两者不能混为一谈。
        assertThrows(BusinessException.class, () -> processTraceService.trace(parentId),
                "被清理的实例本身应返回业务异常「流程实例不存在」，而不是空跟踪图或空指针");
    }

    /** 插入一条待反馈的部门沟通记录；监听器只在会话存在时才做收尾，这里给占位会话ID即可 */
    private String insertPendingDeptRecord(Task rootTask) {
        String recordId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO BIZ_COMM_TASK_ (ID_, SESSION_ID_, SOURCE_TASK_ID_, ROOT_TASK_ID_, "
                        + "SOURCE_TASK_DEF_KEY_, TARGET_TYPE_, TARGET_ID_, TARGET_NAME_, CONTENT_, "
                        + "STATUS_, CREATE_TIME_) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                recordId, "SESSION-PARENT-MISSING-" + recordId, rootTask.getId(), rootTask.getId(),
                rootTask.getTaskDefinitionKey(), BizCommTask.TARGET_DEPT, DEPT_IT, DEPT_IT_NAME,
                "请确认服务器扩容方案", BizCommTask.STATUS_PENDING,
                new Timestamp(System.currentTimeMillis()));
        return recordId;
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
