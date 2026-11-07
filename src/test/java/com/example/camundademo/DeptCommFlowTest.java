package com.example.camundademo;

import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.security.DemoUserInitializer;
import com.example.camundademo.service.DeptCommProcessService;
import com.example.camundademo.support.TestSession;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部门沟通子流程的端到端用例，覆盖三条主路径：
 *
 * <ol>
 *   <li><b>部门反馈后解锁</b>：发起给 {@code dept_it} → 部门内任意成员认领并提交意见 →
 *       记录回写为 REPLIED → 子流程结束 → 源待办恢复可审批；</li>
 *   <li><b>收回即终止</b>：发起给 {@code dept_admin} → 发起人收回 → 整条子流程实例被终止
 *       （不是只删任务留下孤儿实例）→ 记录 WITHDRAWN → 源待办解锁；</li>
 *   <li><b>主流程结束级联</b>：主流程办结时，挂在它名下仍存活的部门子流程被级联终止，
 *       业务记录按已收回收尾，不出现“主单已结束、部门还在等反馈”。</li>
 *   <li><b>部门成员再发起</b>：部门沟通的当前对象是整个部门，部门内任意成员可把这条沟通
 *       再发起给另一个部门（{@code /api/comm/forward} 的部门分支），两个部门各自反馈后
 *       源待办才解锁。</li>
 *   <li><b>任务ID 缺失时的反查兜底</b>：{@code CAMUNDA_TASK_ID_} 为空时，按子流程实例ID
 *       仍要能反查到沟通记录（部门成员的再发起、待办标签、沟通详情、反馈解锁都依赖它）。</li>
 *   <li><b>建单后任务ID 回填</b>：新建的部门沟通记录上 {@code CAMUNDA_TASK_ID_} 应等于真实的
 *       {@code dept_handle} 任务ID，让新数据不必依赖上面的兜底。</li>
 * </ol>
 *
 * <p>请求全部走真实登录会话（与 {@link CommunicationFlowTest} 同一套 CSRF + JSESSIONID 机制），
 * 只使用独立 H2 库，避免与其它测试类互相污染。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // 独立 H2 库：部门沟通会产生流程实例与业务记录，不能与其它测试类共用
        "spring.datasource.url=jdbc:h2:mem:deptcommflow;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class DeptCommFlowTest {

    private static final String DEPT_IT = "dept_it";
    private static final String DEPT_IT_NAME = "IT 部";
    private static final String DEPT_ADMIN = "dept_admin";
    private static final String DEPT_ADMIN_NAME = "行政部";

    private static final String DEPT_TASK_KEY = DeptCommProcessService.TASK_KEY;

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    @Autowired
    private TaskService engineTaskService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private DeptCommProcessService deptCommProcessService;

    @Autowired
    private DemoUserInitializer demoUserInitializer;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.example.camundademo.service.ProcessTraceService processTraceService;

    private JdbcTemplate jdbc;

    /**
     * 每条用例前确保演示账号与部门组就位：部门组被其它用例删掉时也要能自愈，
     * 否则这里会以“部门不存在”这种与用例无关的失败收场。
     */
    @BeforeEach
    void ensureDemoIdentity() {
        demoUserInitializer.run(null);
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
    }

    // ==================== 1. 部门反馈后解锁 ====================

    @Test
    @SuppressWarnings("unchecked")
    void departmentReplyUnlocksSourceTask() {
        String pid = startProcessAndApply("applicant");
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);

        startDeptComm(managerTaskId, DEPT_IT, DEPT_IT_NAME, "请 IT 部确认服务器扩容方案");

        // 源待办被锁：部门没给意见之前不能审批
        Map<String, Object> locked = statusOf(managerTaskId, "manager");
        assertEquals("COMMUNICATING", locked.get("bizStatus"), "部门沟通发出后源待办应锁定: " + locked);
        assertEquals(Boolean.FALSE, locked.get("canApprove"), "沟通中不应允许审批: " + locked);

        List<Map<String, Object>> records = commTasksIn(locked);
        assertEquals(1, records.size(), "首轮应只有一条部门沟通记录: " + records);
        Map<String, Object> record = recordOf(records, DEPT_IT);
        String recordId = (String) record.get("id");
        assertEquals(BizCommTask.TARGET_DEPT, record.get("targetType"), "沟通对象类型应为 DEPT: " + record);
        assertEquals(DEPT_IT_NAME, record.get("targetName"), "部门名称应可回读: " + record);
        assertEquals(BizCommTask.STATUS_PENDING, record.get("status"), "新建部门沟通应为待反馈: " + record);

        // 部门子流程确实起来了：dept_handle 由候选组承接，没有分派给任何个人
        ProcessInstance deptProcess = deptProcessOf(recordId);
        assertNotNull(deptProcess, "部门沟通未启动独立子流程实例: " + record);
        Task deptTask = deptTaskOf(recordId);
        assertNotNull(deptTask, "部门子流程里没有 dept_handle 任务");
        assertNull(deptTask.getAssignee(), "部门任务不应分派给个人，否则只有一个人能处理");
        assertEquals(1, candidateCount(deptTask.getId(), "alice"), "alice 属于 dept_it，应是候选处理人");
        assertEquals(1, candidateCount(deptTask.getId(), "bob"), "bob 属于 dept_it，应是候选处理人");
        assertEquals(0, candidateCount(deptTask.getId(), "carol"), "carol 属于 dept_admin，不应是候选处理人");

        // 发起人要有值：流程实例列表的「发起人」读 START_USER_ID_，只写流程变量 initiator 不会显示
        assertEquals("manager", startUserOf(deptProcess.getId()),
                "部门子流程的发起人应记为发起这次沟通的待办办理人");

        // 部门候选任务要出现在部门成员的待办中心里，否则对部门来说这条沟通不存在
        List<Map<String, Object>> aliceTodo = todoList("alice");
        assertTrue(aliceTodo.stream().anyMatch(t -> deptTask.getId().equals(t.get("taskId"))),
                "部门成员的待办里看不到部门沟通任务，实际待办=" + aliceTodo);

        // 待办中心的「发起人」列与流程实例列表不是同一个来源：前者读流程变量 applicant。
        // 只修 START_USER_ID_ 时这一列仍然为空，这里钉住两条链路都要有值。
        Map<String, Object> deptTodo = aliceTodo.stream()
                .filter(t -> deptTask.getId().equals(t.get("taskId")))
                .findFirst().orElseThrow(() -> new IllegalStateException("待办里找不到部门任务"));
        assertEquals("manager", deptTodo.get("applicant"),
                "待办中心的发起人应取流程变量 applicant，实际为: " + deptTodo);

        // 点「去反馈」必须能打开详情：部门任务是候选组任务，未认领时 assignee 为空，
        // 详情接口若只认 assignee，部门成员拿到 403、抽屉退化成审批界面。
        // 可见范围必须与待办列表同一口径：本人候选也能查看。
        ResponseEntity<String> bobDetail = rest.exchange("/api/wb/task/" + deptTask.getId(),
                HttpMethod.GET, new HttpEntity<>(authHeaders("bob")), String.class);
        assertEquals(200, bobDetail.getStatusCode().value(),
                "部门成员应能打开部门沟通待办详情: " + bobDetail.getBody());
        assertTrue(bobDetail.getBody() != null && bobDetail.getBody().contains("\"isCommTask\":true"),
                "部门待办详情必须标记为沟通任务，前端才会渲染反馈/发起沟通界面: " + bobDetail.getBody());
        ResponseEntity<String> carolDetail = rest.exchange("/api/wb/task/" + deptTask.getId(),
                HttpMethod.GET, new HttpEntity<>(authHeaders("carol")), String.class);
        assertEquals(403, carolDetail.getStatusCode().value(),
                "非部门成员不应能查看该部门待办详情: " + carolDetail.getBody());

        // 沟通中审批被引擎层兜底拦截，任何渠道都绕不过
        Map<String, Object> blocked = post("/api/comm/demo/complete/" + managerTaskId, null, "manager");
        assertEquals(Boolean.TRUE, blocked.get("error"), "沟通中审批未被拦截: " + blocked);

        // 非本部门成员不能代替部门提交意见
        Map<String, Object> forged = new LinkedHashMap<>();
        forged.put("commTaskRecordId", recordId);
        forged.put("content", "行政部代为答复");
        Map<String, Object> forgedResp = post("/api/comm/reply", forged, "carol");
        assertEquals(Boolean.TRUE, forgedResp.get("error"), "非部门成员不应能提交部门意见: " + forgedResp);
        assertTrue(String.valueOf(forgedResp.get("message")).contains("成员"),
                "拒绝原因应指明只有部门成员可提交: " + forgedResp);

        // 部门成员提交意见：认领 dept_handle → 完成子流程 → 监听器回写并解锁
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("commTaskRecordId", recordId);
        reply.put("content", "扩容方案已确认，本周五前完成");
        assertEquals("ok", post("/api/comm/reply", reply, "alice").get("result"), "部门成员反馈失败");

        Map<String, Object> after = statusOf(managerTaskId, "manager");
        Map<String, Object> replied = recordOf(commTasksIn(after), DEPT_IT);
        assertEquals(BizCommTask.STATUS_REPLIED, replied.get("status"), "部门反馈后记录应为已反馈: " + replied);
        assertEquals("alice", replied.get("replyUserId"), "应记录实际提交意见的部门成员: " + replied);
        assertEquals("扩容方案已确认，本周五前完成", replied.get("replyContent"), "部门意见应回写: " + replied);
        assertNotNull(replied.get("replyTime"), "反馈时间应回写: " + replied);
        assertEquals("NORMAL", after.get("bizStatus"), "部门反馈后源待办应解锁: " + after);
        assertEquals(Boolean.TRUE, after.get("canApprove"), "解锁后应恢复可审批: " + after);

        // 子流程已走完：实例与部门任务都不应继续存在
        assertEquals(0, runtimeService.createProcessInstanceQuery()
                        .processInstanceId(deptProcess.getId()).count(),
                "部门反馈完成后子流程实例应结束");
        assertEquals(0, engineTaskService.createTaskQuery().processInstanceId(deptProcess.getId()).count(),
                "部门反馈完成后 dept_handle 任务应消失");

        // 已经闭环的部门沟通不允许重复提交，避免覆盖历史结论
        Map<String, Object> again = post("/api/comm/reply", reply, "alice");
        assertEquals(Boolean.TRUE, again.get("error"), "同一部门沟通不应允许重复提交: " + again);

        // 解锁后审批可以正常继续，直至流程结束
        completeTask(managerTaskId, "manager");
        completeTask(findTaskInProcess("finance", "finance_review", pid), "finance");
        assertEquals(Boolean.TRUE, get("/api/comm/demo/finished/" + pid, "manager").get("finished"),
                "主流程应能正常办结");
    }

    // ==================== 2. 收回即终止整个子流程 ====================

    @Test
    void withdrawTerminatesWholeDeptSubProcess() {
        String pid = startProcessAndApply("applicant2");
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);

        Map<String, Object> started = startDeptComm(managerTaskId, DEPT_ADMIN, DEPT_ADMIN_NAME,
                "请行政部确认会议室安排");
        String sessionId = String.valueOf(started.get("sessionId"));

        Map<String, Object> record = recordOf(commTasksIn(statusOf(managerTaskId, "manager")), DEPT_ADMIN);
        String recordId = (String) record.get("id");
        ProcessInstance deptProcess = deptProcessOf(recordId);
        assertNotNull(deptProcess, "部门沟通未启动独立子流程实例: " + record);
        assertNotNull(deptTaskOf(recordId), "部门子流程里没有 dept_handle 任务");

        // 发起人收回这条部门沟通
        Map<String, Object> withdrawReq = new LinkedHashMap<>();
        withdrawReq.put("commTaskIds", List.of(recordId));
        Map<String, Object> withdrawn = post("/api/comm/withdraw/" + sessionId, withdrawReq, "manager");
        assertEquals("ok", withdrawn.get("result"), "收回部门沟通失败: " + withdrawn);
        assertEquals(1, ((Number) withdrawn.get("withdrawn")).intValue(), "应收回 1 条: " + withdrawn);

        Map<String, Object> after = statusOf(managerTaskId, "manager");
        Map<String, Object> withdrawnRecord = recordOf(commTasksIn(after), DEPT_ADMIN);
        assertEquals(BizCommTask.STATUS_WITHDRAWN, withdrawnRecord.get("status"),
                "收回后记录应为已收回: " + withdrawnRecord);
        assertNotNull(withdrawnRecord.get("withdrawTime"), "收回时间应记录: " + withdrawnRecord);

        // 关键：结束的是整个子流程实例，而不是只删掉 dept_handle 任务
        assertEquals(0, runtimeService.createProcessInstanceQuery()
                        .processInstanceId(deptProcess.getId()).count(),
                "收回后部门子流程实例应被终止，否则会留下永远结束不了的孤儿实例");
        assertEquals(0, engineTaskService.createTaskQuery().processInstanceId(deptProcess.getId()).count(),
                "收回后部门任务应消失");
        assertEquals("NORMAL", after.get("bizStatus"), "收回后源待办应解锁: " + after);

        // 收回后部门成员不能再补意见，也不会再出现在其待办里
        Map<String, Object> lateReply = new LinkedHashMap<>();
        lateReply.put("commTaskRecordId", recordId);
        lateReply.put("content", "补一句意见");
        Map<String, Object> lateResp = post("/api/comm/reply", lateReply, "carol");
        assertEquals(Boolean.TRUE, lateResp.get("error"), "已收回的部门沟通不应还能提交意见: " + lateResp);
        assertFalse(todoList("carol").stream().anyMatch(t -> deptProcess.getId().equals(t.get("processInstanceId"))),
                "收回后部门成员待办里不应还有这条沟通");

        completeTask(managerTaskId, "manager");
        completeTask(findTaskInProcess("finance", "finance_review", pid), "finance");
    }

    // ==================== 3. 主流程结束级联终止 ====================

    /**
     * 主流程办结时，挂在它名下的部门子流程必须被级联终止。
     *
     * <p>为什么这里要手工启动子流程：正常链路上“沟通中”的主待办会被引擎锁住，
     * 不可能一边等待部门反馈、一边把主流程办结；能同时成立的真实场景是主单被撤销/办结后
     * 遗留了仍在等待的部门子流程。为了让“主流程 end 事件 → 级联监听器”这条路径被真实触发，
     * 这里让主流程保持未锁定，另行启动一条带 {@code parentProcessInstanceId} 的部门子流程。</p>
     */
    @Test
    void mainProcessEndCascadesDeptSubProcess() {
        String pid = startProcessAndApply("applicant3");
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);
        Task rootTask = engineTaskService.createTaskQuery().taskId(managerTaskId).singleResult();
        assertNotNull(rootTask, "未找到经理审批任务");

        String recordId = insertPendingDeptRecord(rootTask, "主流程结束前发出的部门沟通");
        DeptCommProcessService.Started started = deptCommProcessService.start(
                recordId, "SESSION-CASCADE-" + recordId, rootTask, DEPT_IT, DEPT_IT_NAME,
                "主流程结束前发出的部门沟通", "manager", 1);
        jdbc.update("UPDATE BIZ_COMM_TASK_ SET CAMUNDA_TASK_ID_ = ?, DEPT_PROC_INST_ID_ = ? WHERE ID_ = ?",
                started.getTaskId(), started.getProcessInstanceId(), recordId);

        assertNotNull(deptTaskOf(recordId), "级联用例前置：部门任务应存在");
        assertEquals(BizCommTask.STATUS_PENDING, deptStatus(recordId), "级联用例前置：记录应为待反馈");

        // 主流程正常办结：经理审批 → 财务审批 → end 事件触发级联监听器
        completeTask(managerTaskId, "manager");
        completeTask(findTaskInProcess("finance", "finance_review", pid), "finance");
        assertEquals(Boolean.TRUE, get("/api/comm/demo/finished/" + pid, "manager").get("finished"),
                "主流程应正常办结");

        assertEquals(0, runtimeService.createProcessInstanceQuery()
                        .processInstanceId(started.getProcessInstanceId()).count(),
                "主流程结束后部门子流程应被级联终止");
        assertEquals(0, engineTaskService.createTaskQuery()
                        .processInstanceId(started.getProcessInstanceId()).count(),
                "主流程结束后部门任务不应残留");
        assertEquals(BizCommTask.STATUS_WITHDRAWN, deptStatus(recordId),
                "级联终止后业务记录应按已收回收尾，不能继续显示待反馈");
        assertNotNull(jdbc.queryForObject(
                        "SELECT WITHDRAW_TIME_ FROM BIZ_COMM_TASK_ WHERE ID_ = ?",
                        Timestamp.class, recordId),
                "级联终止时应收尾收回时间");
    }

    // ==================== 4. 部门成员再发起给另一个部门 ====================

    /**
     * 部门沟通里由部门成员“再发起沟通”给另一个部门：{@code /api/comm/forward} 的部门分支。
     *
     * <p>这条链路的几处易错点都在这里钉住：</p>
     * <ol>
     *   <li>权限口径是“部门成员”而不是“部门 ID 等于登录账号”——部门沟通的当前对象是整个部门，
     *       {@code dept_it} 的任意成员都能再发起，非本部门的人不能；</li>
     *   <li>新记录必须挂在当前这条部门沟通下（{@code PARENT_COMM_TASK_ID_}），
     *       否则沟通树会断成两棵，看不出“哪个部门又找了谁”；</li>
     *   <li>新记录同样走独立子流程、由候选组承接，两个部门各自认领，互不干扰；</li>
     *   <li>再发起不改变原记录状态，原部门仍要自己反馈；两个部门都反馈完，源待办才解锁。</li>
     * </ol>
     */
    @Test
    void deptMemberForwardsToAnotherDepartment() {
        String pid = startProcessAndApply("applicant4");
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);

        startDeptComm(managerTaskId, DEPT_IT, DEPT_IT_NAME, "请 IT 部确认机房搬迁窗口");
        Map<String, Object> itRecord = recordOf(commTasksIn(statusOf(managerTaskId, "manager")), DEPT_IT);
        String itRecordId = (String) itRecord.get("id");

        Task itTask = deptTaskOf(itRecordId);
        assertNotNull(itTask, "IT 部部门任务应存在");

        // 当前对象是整个部门：部门内任意成员都能在这条沟通上再发起
        Map<String, Object> itStatus = statusOf(itTask.getId(), "alice");
        assertEquals(Boolean.TRUE, itStatus.get("canForwardComm"),
                "IT 部成员应可在部门沟通上再发起: " + itStatus);

        // 不属于该部门的人不能替部门再发起
        Map<String, Object> forged = forward(itRecordId, "manager", "经理替 IT 部再发起",
                List.of(deptTarget(DEPT_ADMIN, DEPT_ADMIN_NAME)));
        assertEquals(Boolean.TRUE, forged.get("error"), "非部门成员不应能再发起部门沟通: " + forged);

        // 也不能把沟通退回给会话发起人，那等于把问题推回原点、形成互相等待
        Map<String, Object> toInitiator = forward(itRecordId, "alice", "退回经理", List.of(userTarget("manager", "经理")));
        assertEquals(Boolean.TRUE, toInitiator.get("error"), "不应允许把沟通再发给发起人: " + toInitiator);
        assertTrue(String.valueOf(toInitiator.get("message")).contains("发起人"),
                "拒绝原因应指明不能发给发起人: " + toInitiator);

        // 部门成员把这条沟通再发起给行政部
        Map<String, Object> forwarded = forward(itRecordId, "alice", "搬迁窗口还需行政部确认会议室",
                List.of(deptTarget(DEPT_ADMIN, DEPT_ADMIN_NAME)));
        assertEquals(Boolean.FALSE, Boolean.TRUE.equals(forwarded.get("error")), "部门成员再发起失败: " + forwarded);
        assertEquals(List.of(DEPT_ADMIN_NAME), forwarded.get("addedTargets"), "应新增行政部一条沟通: " + forwarded);
        assertEquals(2, ((Number) forwarded.get("currentRound")).intValue(), "再发起应推进到第 2 轮: " + forwarded);

        Map<String, Object> afterForward = statusOf(managerTaskId, "manager");
        List<Map<String, Object>> records = commTasksIn(afterForward);
        assertEquals(2, records.size(), "应有 IT 部与行政部两条沟通记录: " + records);

        Map<String, Object> adminRecord = recordOf(records, DEPT_ADMIN);
        String adminRecordId = (String) adminRecord.get("id");
        assertEquals(BizCommTask.TARGET_DEPT, adminRecord.get("targetType"), "新记录对象类型应为 DEPT: " + adminRecord);
        assertEquals(DEPT_ADMIN_NAME, adminRecord.get("targetName"), "应回写部门名称: " + adminRecord);
        assertEquals(itRecordId, adminRecord.get("parentCommTaskId"),
                "新记录必须挂在 IT 部这条沟通下，否则沟通树会断开: " + adminRecord);
        assertEquals(Boolean.TRUE, adminRecord.get("isFollowUp"), "再发起产生的记录应标记为后续沟通: " + adminRecord);
        assertEquals(DEPT_IT_NAME, adminRecord.get("parentTargetName"), "应能回溯父级部门: " + adminRecord);
        assertEquals(BizCommTask.STATUS_PENDING, adminRecord.get("status"), "新建部门沟通应为待反馈: " + adminRecord);

        // 再发起不改原记录状态：IT 部仍要自己给意见
        assertEquals(BizCommTask.STATUS_PENDING, recordOf(records, DEPT_IT).get("status"),
                "再发起不应把原部门沟通置为已处理: " + records);

        // 行政部这条同样是独立子流程 + 候选组承接，候选人是行政部成员而不是 IT 部成员
        ProcessInstance adminProcess = deptProcessOf(adminRecordId);
        assertNotNull(adminProcess, "再发起的部门沟通未启动独立子流程实例: " + adminRecord);
        Task adminTask = deptTaskOf(adminRecordId);
        assertNotNull(adminTask, "行政部子流程里没有 dept_handle 任务");
        assertNull(adminTask.getAssignee(), "行政部任务不应分派给个人");
        assertEquals(1, candidateCount(adminTask.getId(), "carol"), "carol 属于 dept_admin，应是候选处理人");
        assertEquals(1, candidateCount(adminTask.getId(), "dave"), "dave 属于 dept_admin，应是候选处理人");
        assertEquals(0, candidateCount(adminTask.getId(), "alice"), "alice 属于 dept_it，不应是行政部任务候选人");
        assertEquals("alice", startUserOf(adminProcess.getId()),
                "再发起产生的子流程，发起人应是实际操作人 alice，而不是会话最初发起人 manager");
        assertTrue(todoList("carol").stream().anyMatch(t -> adminTask.getId().equals(t.get("taskId"))),
                "行政部成员的待办里看不到再发起的部门沟通，实际待办=" + todoList("carol"));

        // 待办中心同样要跟随「实际操作人」：这次是 alice 转给行政部的
        Map<String, Object> adminTodo = todoList("carol").stream()
                .filter(t -> adminTask.getId().equals(t.get("taskId")))
                .findFirst().orElseThrow(() -> new IllegalStateException("待办里找不到行政部任务"));
        assertEquals("alice", adminTodo.get("applicant"),
                "再发起的部门沟通，待办中心发起人应是实际操作人 alice: " + adminTodo);

        // 再发起期间源待办仍锁定
        assertEquals("COMMUNICATING", afterForward.get("bizStatus"), "再发起期间源待办应保持锁定: " + afterForward);
        assertEquals(Boolean.FALSE, afterForward.get("canApprove"), "再发起期间不应允许审批: " + afterForward);

        // 只需一个部门反馈还不够：两边都在等反馈，源待办不能解锁
        replyComm(adminRecordId, "carol", "行政部确认周六可进场");
        Map<String, Object> half = statusOf(managerTaskId, "manager");
        assertEquals(BizCommTask.STATUS_REPLIED, recordOf(commTasksIn(half), DEPT_ADMIN).get("status"),
                "行政部反馈后记录应为已反馈: " + half);
        assertEquals("COMMUNICATING", half.get("bizStatus"),
                "IT 部还没反馈就想解锁，会让审批人带着半截信息审批: " + half);
        assertEquals(Boolean.FALSE, half.get("canApprove"), "仍有待反馈部门时不应允许审批: " + half);

        // 两个部门都反馈完，源待办才解锁
        replyComm(itRecordId, "alice", "IT 部确认网络切换方案");
        Map<String, Object> done = statusOf(managerTaskId, "manager");
        assertEquals(BizCommTask.STATUS_REPLIED, recordOf(commTasksIn(done), DEPT_IT).get("status"),
                "IT 部反馈后记录应为已反馈: " + done);
        assertEquals("NORMAL", done.get("bizStatus"), "两个部门都反馈后源待办应解锁: " + done);
        assertEquals(Boolean.TRUE, done.get("canApprove"), "解锁后应恢复可审批: " + done);

        // 两条子流程都已走完，不留存活实例
        assertEquals(0, runtimeService.createProcessInstanceQuery()
                        .processInstanceId(adminProcess.getId()).count(),
                "行政部反馈完成后子流程实例应结束");
        assertNull(deptProcessOf(itRecordId), "IT 部反馈完成后子流程实例应结束");

        completeTask(managerTaskId, "manager");
        completeTask(findTaskInProcess("finance", "finance_review", pid), "finance");
        assertEquals(Boolean.TRUE, get("/api/comm/demo/finished/" + pid, "manager").get("finished"),
                "主流程应能正常办结");
    }

    // ==================== 5. 任务ID 缺失时的反查兜底 ====================

    /**
     * {@code CAMUNDA_TASK_ID_} 缺失时，必须能按「任务所在流程实例ID」反查到沟通记录。
     *
     * <p>部门沟通是在<b>同一个 Camunda 命令内</b>插入业务表的，建单时 {@code dept_handle} 还没
     * flush，拿不到任务ID。现在建单命令提交后会补一次回填，但<b>历史数据不会自动补齐</b>，
     * 而且回填属于“尽力而为”（失败只记日志），所以查询端的兜底必须一直在：
     * {@code CommQueryService#resolveCommRecord} 按子流程实例ID 再查一次。这层兜底一旦被删或改坏，
     * 表现全是静默错误、不会抛异常：部门成员永远点不到「再发起」、待办里看不出这是部门沟通、
     * 沟通详情 {@code commTasks} 为空、{@code bizStatus} 误报 {@code NORMAL}。</p>
     *
     * <p>这里<b>主动把任务ID 抹成 NULL</b> 再验，而不是依赖建单链路是否能拿到任务ID：
     * 回填已经补上，新记录通常都带任务ID，只有抹掉它才是在测兜底本身；
     * 将来回填逻辑再调整，这条用例的语义也不会退化。</p>
     */
    @Test
    void commRecordStillResolvableWhenCamundaTaskIdMissing() {
        String pid = startProcessAndApply("applicant5");
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);

        startDeptComm(managerTaskId, DEPT_IT, DEPT_IT_NAME, "请 IT 部确认数据备份策略");
        Map<String, Object> record = recordOf(commTasksIn(statusOf(managerTaskId, "manager")), DEPT_IT);
        String recordId = (String) record.get("id");

        Task deptTask = deptTaskOf(recordId);
        assertNotNull(deptTask, "部门子流程里没有 dept_handle 任务");
        assertNotNull(deptTask.getProcessInstanceId(), "部门任务应挂在独立子流程实例上");

        // 基线：回填生效后这里本应带有任务ID，但断言只看“能不能查到记录”。
        // 即使将来回填失效、任务ID 又落空，基线也必须通过——这正是兜底的意义。
        // 断言消息里带上完整 VO，兜底被删时能直接看到症状。
        Map<String, Object> baseline = statusOf(deptTask.getId(), "alice");
        assertEquals(Boolean.TRUE, baseline.get("canForwardComm"),
                "基线：部门成员应可在部门沟通上再发起: " + baseline);

        // 确保记录上只留子流程实例ID。回填已经生效，新记录本来就有任务ID，
        // 只有主动抹掉它，用例才是在测兜底，而不是在测「正常反查」。
        jdbc.update("UPDATE BIZ_COMM_TASK_ SET CAMUNDA_TASK_ID_ = NULL WHERE ID_ = ?", recordId);
        assertNull(jdbc.queryForObject("SELECT CAMUNDA_TASK_ID_ FROM BIZ_COMM_TASK_ WHERE ID_ = ?",
                        String.class, recordId),
                "用例前提：这一步之后记录上不应再有任务ID");

        Map<String, Object> status = statusOf(deptTask.getId(), "alice");
        assertEquals(Boolean.TRUE, status.get("isCommTask"), "部门任务仍应被识别为沟通任务: " + status);
        assertEquals(Boolean.TRUE, status.get("canForwardComm"),
                "任务ID 缺失时应按子流程实例ID 反查命中，否则部门成员永远点不到再发起: " + status);
        assertEquals(Boolean.TRUE, status.get("canReply"), "任务ID 缺失时部门成员仍应能提交反馈: " + status);
        assertEquals(Boolean.FALSE, status.get("canApprove"), "沟通任务本身不应出现审批权: " + status);
        assertEquals("COMMUNICATING", status.get("bizStatus"),
                "业务状态应取源待办，而不是因查不到扩展记录回退成 NORMAL: " + status);

        List<Map<String, Object>> records = commTasksIn(status);
        assertEquals(1, records.size(), "任务ID 缺失时沟通详情不应为空: " + status);
        assertEquals(recordId, records.get(0).get("id"), "应反查到同一条沟通记录: " + records);
        assertEquals(BizCommTask.TARGET_DEPT, records.get(0).get("targetType"), "对象类型应为部门: " + records);
        assertEquals(DEPT_IT_NAME, records.get(0).get("targetName"), "部门名称应可回读: " + records);
        assertEquals(BizCommTask.STATUS_PENDING, records.get(0).get("status"), "记录应为待反馈: " + records);

        // 待办中心同样依赖这次反查取 targetType：取不到时前端显示不出「部门沟通」标签
        Map<String, Object> todo = todoList("alice").stream()
                .filter(t -> deptTask.getId().equals(t.get("taskId")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "部门成员待办里找不到部门任务: " + todoList("alice")));
        assertEquals(recordId, todo.get("commRecordId"), "待办应带出沟通记录ID: " + todo);
        assertEquals(BizCommTask.TARGET_DEPT, todo.get("targetType"),
                "任务ID 缺失时待办仍应标出这是部门沟通: " + todo);
        assertEquals(DEPT_IT_NAME, todo.get("targetName"), "待办应带出部门名称: " + todo);

        // 兜底不只是展示：反馈链路要能走完，源待办正常解锁
        replyComm(recordId, "bob", "备份策略已确认，保留 30 天");
        Map<String, Object> after = statusOf(managerTaskId, "manager");
        Map<String, Object> replied = recordOf(commTasksIn(after), DEPT_IT);
        assertEquals(BizCommTask.STATUS_REPLIED, replied.get("status"), "部门反馈应回写: " + replied);
        assertEquals("bob", replied.get("replyUserId"), "应记录实际提交意见的部门成员: " + replied);
        assertNotNull(replied.get("replyTime"), "反馈时间应回写: " + replied);
        assertEquals("NORMAL", after.get("bizStatus"), "任务ID 缺失不应影响解锁: " + after);
        assertEquals(Boolean.TRUE, after.get("canApprove"), "解锁后应恢复可审批: " + after);
        assertNull(deptProcessOf(recordId), "部门反馈完成后子流程实例应结束");

        completeTask(managerTaskId, "manager");
        completeTask(findTaskInProcess("finance", "finance_review", pid), "finance");
        assertEquals(Boolean.TRUE, get("/api/comm/demo/finished/" + pid, "manager").get("finished"),
                "主流程应能正常办结");
    }

    // ==================== 6. 建单后任务ID 回填 ====================

    /**
     * 部门沟通建单后，{@code CAMUNDA_TASK_ID_} 必须被回填成真实的 {@code dept_handle} 任务ID。
     *
     * <p>建单发生在同一个 Camunda 命令里，子流程任务此时还没 flush，取不到任务ID，
     * 只能等命令提交后由 {@code CommTaskFactory#backfillDeptTaskIds} 补写。</p>
     *
     * <p>为什么要单独钉这一条：回填一旦被删，功能表面上仍然是好的（查询端有实例ID 兜底），
     * 但记录上的任务ID 会重新变成空值——按任务ID 统计部门沟通量、或外部系统按
     * {@code CAMUNDA_TASK_ID_} 关联任务的地方会静默漏数据，不会报错。</p>
     */
    @Test
    void deptTaskIdBackfilledAfterDeptCommCreated() {
        String pid = startProcessAndApply("applicant6");
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);

        // 首轮发起：建单命令提交后应完成回填
        startDeptComm(managerTaskId, DEPT_IT, DEPT_IT_NAME, "请 IT 部确认权限回收清单");
        String itRecordId = (String) recordOf(commTasksIn(statusOf(managerTaskId, "manager")), DEPT_IT).get("id");
        Task itTask = deptTaskOf(itRecordId);
        assertNotNull(itTask, "IT 部部门任务应存在");
        assertEquals(itTask.getId(), camundaTaskIdOf(itRecordId),
                "首轮发起后应把 dept_handle 任务ID 回填到 CAMUNDA_TASK_ID_");
        assertEquals(itTask.getProcessInstanceId(), deptProcInstIdOf(itRecordId),
                "回填任务ID 不应挤占 DEPT_PROC_INST_ID_：该列仍是子流程实例ID");

        // 部门成员再发起：走另一条建单路径（createFollowUpRecords），同样要回填
        Map<String, Object> forwarded = forward(itRecordId, "alice", "权限清单还需行政部确认",
                List.of(deptTarget(DEPT_ADMIN, DEPT_ADMIN_NAME)));
        assertEquals(Boolean.FALSE, Boolean.TRUE.equals(forwarded.get("error")), "再发起失败: " + forwarded);
        String adminRecordId = (String) recordOf(
                commTasksIn(statusOf(managerTaskId, "manager")), DEPT_ADMIN).get("id");
        Task adminTask = deptTaskOf(adminRecordId);
        assertNotNull(adminTask, "行政部部门任务应存在");
        assertEquals(adminTask.getId(), camundaTaskIdOf(adminRecordId),
                "再发起产生的部门沟通同样应回填任务ID");
        assertEquals(adminTask.getProcessInstanceId(), deptProcInstIdOf(adminRecordId),
                "再发起的记录同样应保留子流程实例ID");

        // 两条记录的任务ID 必须各自指向自己的部门任务，不能串
        assertFalse(camundaTaskIdOf(itRecordId).equals(camundaTaskIdOf(adminRecordId)),
                "两个部门的子流程任务ID 不应相同");

        // 回填后按任务ID 直接反查即可命中，不再依赖实例ID 兜底
        assertEquals(adminRecordId, jdbc.queryForObject(
                        "SELECT ID_ FROM BIZ_COMM_TASK_ WHERE CAMUNDA_TASK_ID_ = ?",
                        String.class, adminTask.getId()),
                "按 CAMUNDA_TASK_ID_ 应直接命中这条沟通记录");

        // 回填不改变功能：两条沟通都可正常反馈，源待办解锁。
        // 顺序必须先下游（行政部）后上游（IT 部）：IT 部这条还有下游待反馈时会被拦下。
        replyComm(adminRecordId, "carol", "行政部确认清单无遗漏");
        replyComm(itRecordId, "alice", "权限清单已核对");
        Map<String, Object> done = statusOf(managerTaskId, "manager");
        assertEquals("NORMAL", done.get("bizStatus"), "两个部门反馈后源待办应解锁: " + done);

        completeTask(managerTaskId, "manager");
        completeTask(findTaskInProcess("finance", "finance_review", pid), "finance");
    }

    // ==================== 7. 跟踪图同时展示人员与部门沟通 ====================

    /**
     * 同一次「发起沟通」同时选人员和部门时，跟踪图和明细里必须两条都在。
     *
     * <p>部门任务在独立的 dept_communication 子流程里，按主实例查历史任务扫不到，
     * 早先只按引擎历史任务组装沟通列表，导致部门那条整条消失——图上只剩“沟通 1 条”，
     * 明细里也只有人员。这里钉住：节点上的 commTasks 必须同时包含 USER 和 DEPT，
     * 且部门记录带上子流程实例ID、来源节点、待反馈状态，统计口径同步为 2 条。</p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void traceShowsDeptCommAlongsideUserComm() {
        String pid = startProcessAndApply("applicant");
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("taskId", managerTaskId);
        req.put("content", "请 bob 与 IT 部同时确认扩容窗口");
        req.put("targets", List.of(userTarget("bob", "Bob"), deptTarget(DEPT_IT, DEPT_IT_NAME)));
        Map<String, Object> resp = post("/api/comm/start", req, "manager");
        assertEquals(Boolean.FALSE, Boolean.TRUE.equals(resp.get("error")), "同时发起人员/部门沟通失败: " + resp);

        Map<String, Object> trace = processTraceService.trace(pid);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) trace.get("nodes");
        Map<String, Object> node = nodes.stream()
                .filter(n -> "manager_review".equals(n.get("nodeId")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("跟踪图缺少 manager_review 节点: " + nodes));
        List<Map<String, Object>> commTasks = (List<Map<String, Object>>) node.get("commTasks");
        assertNotNull(commTasks, "节点缺少 commTasks 字段");
        assertEquals(2, commTasks.size(), "跟踪图应同时展示人员与部门两条沟通: " + commTasks);

        Map<String, Object> userComm = commTasks.stream()
                .filter(c -> BizCommTask.TARGET_USER.equals(c.get("targetType")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("跟踪图缺少人员沟通: " + commTasks));
        assertEquals("bob", userComm.get("targetId"));

        Map<String, Object> deptComm = commTasks.stream()
                .filter(c -> BizCommTask.TARGET_DEPT.equals(c.get("targetType")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("跟踪图缺少部门沟通: " + commTasks));
        assertEquals(DEPT_IT, deptComm.get("targetId"));
        assertEquals(DEPT_IT_NAME, deptComm.get("targetName"));
        assertEquals(BizCommTask.STATUS_PENDING, deptComm.get("status"));
        assertEquals(Boolean.TRUE, deptComm.get("pending"));
        // 来源节点：明细表按节点归集，必须落到发起它的 manager_review 上
        assertEquals("manager_review", deptComm.get("taskDefKey").toString().replace("#COMM", ""),
                "部门沟通应归到 manager_review 节点: " + deptComm);
        // rootTaskId 是主实例里的源待办，前端据此把两条沟通归入同一会话
        assertEquals(managerTaskId, deptComm.get("rootTaskId"));
        assertNotNull(deptComm.get("deptProcInstId"), "部门沟通应带子流程实例ID: " + deptComm);
        assertNotNull(deptComm.get("taskId"), "部门沟通应有稳定唯一键: " + deptComm);

        Map<String, Object> stats = (Map<String, Object>) trace.get("stats");
        assertEquals(2, stats.get("commTotal"), "跟踪图统计应包含部门沟通: " + stats);
        assertEquals(2, stats.get("commPending"), "两条沟通都待反馈: " + stats);

        // 明细表“只看有沟通的节点”依赖 commTasks，非空即会被保留；
        // 同时开启筛选时该节点必须仍在结果里（由同一份数据驱动，不单独断言前端口径）
        assertEquals(Boolean.TRUE, node.get("commLocked"), "有待反馈沟通时节点应处于锁定态: " + node);
    }

    // ==================== 诊断：明细处理人 ====================

    /**
     * 跟踪图「节点执行明细」的处理人：候选组任务认领后办结也要显示办理人。
     *
     * <p>Camunda 对「先 claim 再 complete」的候选组任务只更新 ACT_HI_TASKINST.ASSIGNEE_，
     * ACT_HI_ACTINST.ASSIGNEE_ 仍为 null。部门沟通的 dept_handle 正是这种任务，
     * 早先明细只读活动实例，于是有人办理也显示「—」。这里先钉住这个库层事实，
     * 再断言明细能取到 alice，避免以后又改回只读活动实例。</p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void traceShowsAssigneeForClaimedCandidateTask() {
        String pid = startProcessAndApply("applicant");
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);
        startDeptComm(managerTaskId, DEPT_IT, DEPT_IT_NAME, "确认部门办理人");

        Map<String, Object> st = statusOf(managerTaskId, "manager");
        String recordId = (String) recordOf(commTasksIn(st), DEPT_IT).get("id");
        ProcessInstance deptPi = deptProcessOf(recordId);
        assertNotNull(deptPi, "部门子流程未启动: " + recordId);

        replyComm(recordId, "alice", "确认");

        // 复现条件：活动实例无办理人、历史任务有办理人
        assertNull(jdbc.queryForObject(
                        "SELECT ASSIGNEE_ FROM ACT_HI_ACTINST WHERE PROC_INST_ID_ = ? AND ACT_ID_ = ?",
                        String.class, deptPi.getId(), DEPT_TASK_KEY),
                "Camunda 对 claim 后的候选组任务不回写 ACT_HI_ACTINST，此断言用于固定测试前提");
        assertEquals("alice", jdbc.queryForObject(
                        "SELECT ASSIGNEE_ FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = ? AND TASK_DEF_KEY_ = ?",
                        String.class, deptPi.getId(), DEPT_TASK_KEY),
                "认领后历史任务表应记录实际办理人");

        Map<String, Object> trace = processTraceService.trace(deptPi.getId());
        Map<String, Object> node = ((List<Map<String, Object>>) trace.get("nodes")).stream()
                .filter(n -> DEPT_TASK_KEY.equals(n.get("nodeId")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("跟踪图缺少 dept_handle 节点: " + trace.get("nodes")));
        List<String> assignees = (List<String>) node.get("assignees");
        assertNotNull(assignees, "节点缺少 assignees 字段");
        assertEquals(List.of("alice"), assignees, "部门任务已由 alice 办理，明细处理人不应为空: " + node);
        Map<String, Object> execution = ((List<Map<String, Object>>) node.get("executions")).get(0);
        assertEquals("alice", execution.get("assignee"), "执行实例的办理人应取历史任务表: " + execution);
    }

    /**
     * 未认领的部门任务：明细要显示「待认领」并带出候选部门，而不是一个无法解释的「—」。
     *
     * <p>dept_handle 是候选组任务，无人认领时 ASSIGNEE_ 为空；这一行以前与
     * “开始/结束事件本来就没有办理人”长得完全一样。断言两件事：
     * 未认领时 pendingClaim = true 且候选部门可读；认领后标记立即消失。</p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void traceMarksUnclaimedDeptTaskWithCandidateDept() {
        String pid = startProcessAndApply("applicant");
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);
        startDeptComm(managerTaskId, DEPT_IT, DEPT_IT_NAME, "确认候选部门");

        Map<String, Object> st = statusOf(managerTaskId, "manager");
        String recordId = (String) recordOf(commTasksIn(st), DEPT_IT).get("id");
        ProcessInstance deptPi = deptProcessOf(recordId);
        assertNotNull(deptPi, "部门子流程未启动: " + recordId);

        Map<String, Object> node = nodeOf(processTraceService.trace(deptPi.getId()));
        assertEquals(Boolean.TRUE, node.get("pendingClaim"), "无人认领时明细应标为待认领: " + node);
        assertEquals(List.of(DEPT_IT_NAME), node.get("candidateGroupNames"),
                "待认领应带出候选部门名称: " + node);
        assertTrue(((List<String>) node.get("assignees")).isEmpty(),
                "未认领时不应凭空出现办理人: " + node);

        // 认领后同一行必须改回真实办理人，待认领标记要消失
        engineTaskService.claim(deptTaskOf(recordId).getId(), "alice");
        Map<String, Object> claimed = nodeOf(processTraceService.trace(deptPi.getId()));
        assertEquals(Boolean.FALSE, claimed.get("pendingClaim"), "认领后不应再显示待认领: " + claimed);
        assertEquals(List.of("alice"), claimed.get("assignees"), "认领后应显示实际办理人: " + claimed);
    }

    /**
     * 已办结任务的跟踪图，不应再去查任务身份链接。
     *
     * <p>回归点：{@code getIdentityLinksForTask} 对不存在的任务不是返回空列表，而是抛
     * {@code NullValueException("Cannot find task with id ...")}。跟踪图遍历的是
     * {@code ACT_HI_ACTINST} 全量历史活动，早期实现对每条活动都直接查一次，
     * 于是每次打开跟踪图都会在日志里刷出一整屏异常堆栈。</p>
     */
    @Test
    void traceOfFinishedDeptTaskSkipsCandidateLookup() {
        String pid = startProcessAndApply("applicant3");
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);
        startDeptComm(managerTaskId, DEPT_IT, DEPT_IT_NAME, "办结后仍可查看跟踪");
        String recordId = (String) recordOf(
                commTasksIn(statusOf(managerTaskId, "manager")), DEPT_IT).get("id");
        ProcessInstance deptPi = deptProcessOf(recordId);
        assertNotNull(deptPi, "部门子流程未启动: " + recordId);
        String deptPid = deptPi.getId();

        replyComm(recordId, "alice", "已确认");

        // 固定前提：活动实例还在历史里，但运行期任务已经没了。
        // 这正是 NullValueException 的触发条件，若代码不先判断就会在这里踩到。
        assertTrue(historyService.createHistoricActivityInstanceQuery()
                        .processInstanceId(deptPid).activityId(DEPT_TASK_KEY).count() > 0,
                "部门节点应保留在历史活动里，否则这个用例覆盖不到已办结路径");
        assertEquals(0, engineTaskService.createTaskQuery()
                        .processInstanceId(deptPid).count(),
                "部门反馈完成后运行期任务应已消失");

        Map<String, Object> node = nodeOf(processTraceService.trace(deptPid));
        assertEquals(Boolean.FALSE, node.get("pendingClaim"),
                "已结束的任务不能再标“待认领”: " + node);
        assertEquals(List.of("alice"), node.get("assignees"),
                "已办结节点仍应显示实际办理人: " + node);

        completeTask(managerTaskId, "manager");
        completeTask(findTaskInProcess("finance", "finance_review", pid), "finance");
    }

    /**
     * 「候选组未生成」与「确实没有候选」在页面上都显示「待认领」，日志必须能分开。
     *
     * <p>两个子场景都固定住“任务在运行、无人认领”，只改变候选来源：</p>
     * <ol>
     *   <li>删掉运行期身份链接、保留 {@code deptName} 变量 —— 模拟 {@code ${deptId}}
     *       没解析出值时 Camunda 不创建链接：日志应指出候选组未生成，并说明页面上的
     *       部门名只是变量兜底，任务实际无法认领；</li>
     *   <li>再把 {@code deptName}/{@code deptId} 的历史变量一并删掉 —— 这才是真的没有任何候选：
     *       日志应给出“无任何候选”，而不是同样一句“待认领”。</li>
     * </ol>
     */
    @Test
    @SuppressWarnings("unchecked")
    void traceLogDistinguishesMissingCandidateLinkFromNoCandidate() {
        String pid = startProcessAndApply("applicant");
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);
        startDeptComm(managerTaskId, DEPT_IT, DEPT_IT_NAME, "候选诊断");
        String recordId = (String) recordOf(
                commTasksIn(statusOf(managerTaskId, "manager")), DEPT_IT).get("id");
        ProcessInstance deptPi = deptProcessOf(recordId);
        assertNotNull(deptPi, "部门子流程未启动: " + recordId);
        String deptPid = deptPi.getId();
        String deptTaskId = deptTaskOf(recordId).getId();

        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(
                com.example.camundademo.service.ProcessTraceService.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        List<String> warnings = new ArrayList<>();
        try {
            // 场景一：身份链接丢失，变量仍在 —— 页面能显示部门名，但候选实际已断
            jdbc.update("DELETE FROM ACT_RU_IDENTITYLINK WHERE TASK_ID_ = ? AND TYPE_ = 'candidate'",
                    deptTaskId);
            Map<String, Object> node = nodeOf(processTraceService.trace(deptPid));
            warnings.addAll(warnMessages(capture));
            assertEquals(Boolean.TRUE, node.get("pendingClaim"), "无人认领时仍应标为待认领: " + node);
            assertEquals(List.of(DEPT_IT_NAME), node.get("candidateGroupNames"),
                    "变量兜底仍应让页面显示部门名: " + node);

            assertTrue(warnings.stream().anyMatch(m -> m.contains(deptTaskId)),
                    "诊断日志应带 taskId，便于按实例定位: " + warnings);
            assertTrue(warnings.stream().anyMatch(m -> m.contains("候选组未生成")),
                    "身份链接缺失应判为“候选组未生成”，不能与“没有候选”混为一谈: " + warnings);
            assertTrue(warnings.stream().anyMatch(m -> m.contains("deptName=" + DEPT_IT_NAME)),
                    "日志应说明兜底值取自哪个变量: " + warnings);

            // 场景二：变量也查不到 —— 这才是真的没有任何候选
            warnings.clear();
            capture.list.clear();
            jdbc.update("DELETE FROM ACT_HI_VARINST WHERE PROC_INST_ID_ = ? AND NAME_ IN (?, ?)",
                    deptPid, DeptCommProcessService.VAR_DEPT_NAME, DeptCommProcessService.VAR_DEPT_ID);
            Map<String, Object> bare = nodeOf(processTraceService.trace(deptPid));
            warnings.addAll(warnMessages(capture));
            assertEquals(Boolean.TRUE, bare.get("pendingClaim"), "没有任何候选时仍是待认领: " + bare);
            assertTrue(((List<String>) bare.get("candidateGroupNames")).isEmpty(),
                    "变量删除后不应再凭空显示候选部门: " + bare);
            assertTrue(warnings.stream().anyMatch(m -> m.contains("无任何候选")),
                    "链接与变量两级都空时应判为“无任何候选”: " + warnings);
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }
    }

    /** 取捕获到的 WARN 及以上日志（含参数格式化后的最终文本） */
    private static List<String> warnMessages(ListAppender<ILoggingEvent> capture) {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent event : capture.list) {
            if (event.getLevel().isGreaterOrEqual(Level.WARN) && event.getFormattedMessage() != null) {
                messages.add(event.getFormattedMessage());
            }
        }
        return messages;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nodeOf(Map<String, Object> trace) {        return ((List<Map<String, Object>>) trace.get("nodes")).stream()
                .filter(n -> DEPT_TASK_KEY.equals(n.get("nodeId")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("跟踪图缺少 dept_handle 节点: " + trace.get("nodes")));
    }

    // ==================== 场景构造 ====================

    /** 插入一条待反馈的部门沟通记录；会话只在监听器里做收尾，这里给一个占位 ID 即可 */
    private String insertPendingDeptRecord(Task rootTask, String content) {
        String recordId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO BIZ_COMM_TASK_ (ID_, SESSION_ID_, SOURCE_TASK_ID_, ROOT_TASK_ID_, "
                        + "SOURCE_TASK_DEF_KEY_, TARGET_TYPE_, TARGET_ID_, TARGET_NAME_, CONTENT_, "
                        + "STATUS_, CREATE_TIME_) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                recordId, "SESSION-CASCADE-" + recordId, rootTask.getId(), rootTask.getId(),
                rootTask.getTaskDefinitionKey(), BizCommTask.TARGET_DEPT, DEPT_IT, DEPT_IT_NAME,
                content, BizCommTask.STATUS_PENDING, new Timestamp(System.currentTimeMillis()));
        return recordId;
    }

    // ==================== 引擎 / 业务查询 ====================

    private ProcessInstance deptProcessOf(String recordId) {
        return runtimeService.createProcessInstanceQuery()
                .processDefinitionKey(DeptCommProcessService.PROCESS_KEY)
                .processInstanceBusinessKey(recordId)
                .singleResult();
    }

    private Task deptTaskOf(String recordId) {
        ProcessInstance pi = deptProcessOf(recordId);
        if (pi == null) {
            return null;
        }
        return engineTaskService.createTaskQuery()
                .processInstanceId(pi.getId())
                .taskDefinitionKey(DEPT_TASK_KEY)
                .singleResult();
    }

    private long candidateCount(String taskId, String userId) {
        return engineTaskService.createTaskQuery().taskId(taskId).taskCandidateUser(userId).count();
    }

    private String deptStatus(String recordId) {
        return jdbc.queryForObject("SELECT STATUS_ FROM BIZ_COMM_TASK_ WHERE ID_ = ?", String.class, recordId);
    }

    /** 记录上的沟通任务ID（CAMUNDA_TASK_ID_） */
    private String camundaTaskIdOf(String recordId) {
        return jdbc.queryForObject("SELECT CAMUNDA_TASK_ID_ FROM BIZ_COMM_TASK_ WHERE ID_ = ?",
                String.class, recordId);
    }

    /** 记录上的部门子流程实例ID（DEPT_PROC_INST_ID_） */
    private String deptProcInstIdOf(String recordId) {
        return jdbc.queryForObject("SELECT DEPT_PROC_INST_ID_ FROM BIZ_COMM_TASK_ WHERE ID_ = ?",
                String.class, recordId);
    }

    // ==================== HTTP 辅助 ====================

    private HttpHeaders authHeaders(String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (user != null) {
            headers.set(HttpHeaders.COOKIE, TestSession.cookie(port, user));
            // 写操作需要 CSRF 令牌；GET 多带一个头不影响
            headers.set("X-XSRF-TOKEN", TestSession.csrfToken(port, user));
        }
        return headers;
    }

    private Map<String, Object> post(String path, Object body, String user) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, authHeaders(user)),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }).getBody();
    }

    private Map<String, Object> get(String path, String user) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(user)),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }).getBody();
    }

    private List<Map<String, Object>> tasks(String assignee) {
        // 先按 String 取响应：接口出错时 body 是 JSON 对象，直接按 List 反序列化会掩盖真实状态码
        ResponseEntity<String> resp = rest.exchange("/api/comm/demo/tasks?assignee=" + assignee,
                HttpMethod.GET, new HttpEntity<>(authHeaders(assignee)), String.class);
        if (resp.getStatusCode().isError()) {
            throw new IllegalStateException("查询 " + assignee + " 的待办失败: "
                    + resp.getStatusCode() + " " + resp.getBody());
        }
        String body = resp.getBody();
        if (body == null || body.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(body, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("解析待办列表失败: " + body, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> todoList(String user) {
        Map<String, Object> page = get("/api/wb/todo?page=1&size=50", user);
        Object list = page == null ? null : page.get("list");
        return list == null ? new ArrayList<>() : (List<Map<String, Object>>) list;
    }

    /**
     * 子流程的「发起人」：取引擎历史表的 START_USER_ID_，也就是流程实例列表里显示的那一列。
     * 只写进流程变量 initiator 是不够的——列表读的是这个字段，两者不是一回事。
     */
    private String startUserOf(String processInstanceId) {
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        assertNotNull(hpi, "流程实例历史不存在: " + processInstanceId);
        return hpi.getStartUserId();
    }

    private String findTaskInProcess(String assignee, String taskDefKey, String processInstanceId) {        return tasks(assignee).stream()
                .filter(t -> taskDefKey.equals(t.get("taskDefKey")))
                .filter(t -> processInstanceId.equals(t.get("processInstanceId")))
                .map(t -> (String) t.get("taskId"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "流程 " + processInstanceId + " 中未找到 " + assignee + " 的任务 " + taskDefKey
                                + "，实际待办=" + tasks(assignee)));
    }

    private String startProcessAndApply(String applicant) {
        Map<String, Object> started = post("/api/comm/demo/start", null, applicant);
        assertNotNull(started, "启动流程无响应");
        String pid = (String) started.get("processInstanceId");
        assertNotNull(pid, "启动流程失败: " + started);
        String applyTaskId = findTaskInProcess(applicant, "apply", pid);
        assertEquals("ok", post("/api/comm/demo/complete/" + applyTaskId, null, applicant).get("result"),
                "完成申请失败");
        return pid;
    }

    private Map<String, Object> startDeptComm(String managerTaskId, String deptId, String deptName,
                                             String content) {
        Map<String, String> target = new LinkedHashMap<>();
        target.put("type", BizCommTask.TARGET_DEPT);
        target.put("id", deptId);
        target.put("name", deptName);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("taskId", managerTaskId);
        req.put("content", content);
        req.put("targets", List.of(target));

        Map<String, Object> resp = post("/api/comm/start", req, "manager");
        assertNotNull(resp, "发起部门沟通无响应");
        assertEquals(Boolean.FALSE, Boolean.TRUE.equals(resp.get("error")), "发起部门沟通失败: " + resp);
        assertNotNull(resp.get("sessionId"), "发起部门沟通未返回会话: " + resp);
        return resp;
    }

    /** 沟通对象：部门。字段名与前端选择器提交的一致（type/id/name） */
    private Map<String, String> deptTarget(String deptId, String deptName) {
        Map<String, String> target = new LinkedHashMap<>();
        target.put("type", BizCommTask.TARGET_DEPT);
        target.put("id", deptId);
        target.put("name", deptName);
        return target;
    }

    /** 沟通对象：人员 */
    private Map<String, String> userTarget(String userId, String userName) {
        Map<String, String> target = new LinkedHashMap<>();
        target.put("type", "USER");
        target.put("id", userId);
        target.put("name", userName);
        return target;
    }

    /** 由 operator 把当前这条沟通再发起给 targets；返回原始响应，失败场景由调用方断言 error */
    private Map<String, Object> forward(String commRecordId, String operator, String content,
                                        List<Map<String, String>> targets) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("commTaskRecordId", commRecordId);
        req.put("content", content);
        req.put("targets", targets);
        Map<String, Object> resp = post("/api/comm/forward", req, operator);
        assertNotNull(resp, operator + " 再发起沟通无响应");
        return resp;
    }

    /** 提交部门意见（部门成员认领后反馈） */
    private void replyComm(String commRecordId, String user, String content) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("commTaskRecordId", commRecordId);
        req.put("content", content);
        Map<String, Object> resp = post("/api/comm/reply", req, user);
        assertEquals("ok", resp == null ? null : resp.get("result"), user + " 提交部门意见失败: " + resp);
    }

    private void completeTask(String taskId, String user) {
        Map<String, Object> resp = post("/api/comm/demo/complete/" + taskId, null, user);
        assertEquals("ok", resp == null ? null : resp.get("result"), user + " 完成任务失败: " + resp);
    }

    private Map<String, Object> statusOf(String taskId, String user) {
        Map<String, Object> status = get("/api/comm/status/" + taskId, user);
        assertNotNull(status, "查询沟通状态无响应: " + taskId);
        return status;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> commTasksIn(Map<String, Object> status) {
        Object value = status.get("commTasks");
        return value == null ? new ArrayList<>() : (List<Map<String, Object>>) value;
    }

    private Map<String, Object> recordOf(List<Map<String, Object>> records, String targetId) {
        Map<String, Object> record = records.stream()
                .filter(t -> targetId.equals(t.get("targetId")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "未找到沟通对象 " + targetId + " 的记录: " + records));
        return new HashMap<>(record);
    }
}
