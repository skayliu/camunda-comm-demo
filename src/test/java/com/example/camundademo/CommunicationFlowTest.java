package com.example.camundademo;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端冒烟测试：覆盖“发起沟通 -> 引擎拦截审批 -> 反馈 -> 收回 -> 恢复审批”完整链路。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommunicationFlowTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private org.camunda.bpm.engine.TaskService engineTaskService;

    private String dumpAllTasks() {
        return engineTaskService.createTaskQuery().list().stream()
                .map(t -> t.getTaskDefinitionKey() + "/" + t.getAssignee() + "/" + t.getProcessInstanceId())
                .collect(java.util.stream.Collectors.toList()).toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers), new ParameterizedTypeReference<Map<String, Object>>() {
                });
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(path, HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        return resp.getBody();
    }

    private List<Map<String, Object>> tasks(String assignee) {
        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange("/api/comm/demo/tasks?assignee=" + assignee,
                HttpMethod.GET, null, new ParameterizedTypeReference<List<Map<String, Object>>>() {
                });
        if (resp.getStatusCode().isError()) {
            // 接口 500 时 body 为 null，静默当空列表会掩盖真实故障
            throw new IllegalStateException("查询 " + assignee + " 的待办失败: "
                    + resp.getStatusCode() + " " + resp.getBody());
        }
        List<Map<String, Object>> body = resp.getBody();
        return body == null ? new ArrayList<>() : body;
    }

    private String findTask(String assignee, String taskDefKey) {
        return tasks(assignee).stream()
                .filter(t -> taskDefKey.equals(t.get("taskDefKey")))
                .map(t -> (String) t.get("taskId"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "未找到任务 " + taskDefKey + "，实际待办=" + tasks(assignee)));
    }

    /** 按流程实例过滤，避免多实例用例之间互相干扰 */
    private String findTaskInProcess(String assignee, String taskDefKey, String processInstanceId) {
        return tasks(assignee).stream()
                .filter(t -> taskDefKey.equals(t.get("taskDefKey")))
                .filter(t -> processInstanceId.equals(t.get("processInstanceId")))
                .map(t -> (String) t.get("taskId"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "流程 " + processInstanceId + " 中未找到任务 " + taskDefKey + "，实际待办=" + tasks(assignee)));
    }

    private void replyTo(List<Map<String, Object>> commTasks, String targetId, String content) {
        Map<String, Object> rec = commTasks.stream()
                .filter(t -> targetId.equals(t.get("targetId"))).findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到 " + targetId + " 的沟通记录: " + commTasks));
        Map<String, Object> replyReq = new HashMap<>();
        replyReq.put("commTaskRecordId", rec.get("id"));
        replyReq.put("userId", targetId);
        replyReq.put("content", content);
        assertEquals("ok", post("/api/comm/reply", replyReq).get("result"), "反馈失败: " + targetId);
    }

    private String startAndSendToManager(String applicant, String content, List<Map<String, String>> targets) {
        Map<String, Object> started = post("/api/comm/demo/start?initiator=" + applicant, null);
        assertNotNull(started, "启动流程无响应");
        String pid = (String) started.get("processInstanceId");
        assertNotNull(pid, "启动流程失败: " + started);

        String applyTaskId = findTaskInProcess(applicant, "apply", pid);
        assertEquals("ok", post("/api/comm/demo/complete/" + applyTaskId, null).get("result"));
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);

        Map<String, Object> req = new HashMap<>();
        req.put("taskId", managerTaskId);
        req.put("initiator", "manager");
        req.put("content", content);
        req.put("targets", targets);
        Map<String, Object> startResp = post("/api/comm/start", req);
        assertNotNull(startResp.get("sessionId"), "发起沟通失败: " + startResp);

        // 沟通中审批被拦截，作为后续断言的前置条件
        Map<String, Object> blocked = post("/api/comm/demo/complete/" + managerTaskId, null);
        assertEquals(Boolean.TRUE, blocked.get("error"), "沟通中审批未被拦截: " + blocked);
        return pid;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> commTasksOf(String taskId) {
        return (List<Map<String, Object>>) get("/api/comm/status/" + taskId + "?userId=manager").get("commTasks");
    }

    @Test
    @Order(1)
    void fullCommunicationFlow() {
        // 1. 启动流程，申请人完成“填写申请”
        Map<String, Object> started = post("/api/comm/demo/start?initiator=applicant", null);
        assertNotNull(started, "启动流程无响应");
        assertNotNull(started.get("processInstanceId"), "启动流程失败: " + started);
        String pid = (String) started.get("processInstanceId");

        String applyTaskId = findTask("applicant", "apply");
        assertNotNull(post("/api/comm/demo/complete/" + applyTaskId, null).get("result"));

        // 2. 经理发起沟通给 alice / bob
        String managerTaskId = findTask("manager", "manager_review");
        Map<String, Object> req = new HashMap<>();
        req.put("taskId", managerTaskId);
        req.put("initiator", "manager");
        req.put("content", "请核实合同金额是否正确");
        List<Map<String, String>> targets = new ArrayList<>();
        targets.add(target("USER", "alice", "张三"));
        targets.add(target("USER", "bob", "李四"));
        req.put("targets", targets);

        Map<String, Object> startResp = post("/api/comm/start", req);
        assertNotNull(startResp.get("sessionId"), "发起沟通失败: " + startResp);
        String sessionId = (String) startResp.get("sessionId");

        // 3. 沟通中审批必须被引擎拦截
        Map<String, Object> blocked = post("/api/comm/demo/complete/" + managerTaskId, null);
        assertEquals(Boolean.TRUE, blocked.get("error"), "沟通中审批未被拦截: " + blocked);
        assertTrue(String.valueOf(blocked.get("message")).contains("沟通中"), "拦截提示异常: " + blocked);

        // 4. 状态应为 COMMUNICATING，且有 2 条沟通任务
        Map<String, Object> status = get("/api/comm/status/" + managerTaskId + "?userId=manager");
        assertEquals("COMMUNICATING", status.get("bizStatus"));
        assertEquals(Boolean.FALSE, status.get("canApprove"));
        List<Map<String, Object>> commTasks = (List<Map<String, Object>>) status.get("commTasks");
        assertEquals(2, commTasks.size(), "沟通任务数量异常: " + commTasks);

        // 5. alice 反馈
        Map<String, Object> aliceTask = commTasks.stream()
                .filter(t -> "alice".equals(t.get("targetId"))).findFirst().orElseThrow();
        Map<String, Object> replyReq = new HashMap<>();
        replyReq.put("commTaskRecordId", aliceTask.get("id"));
        replyReq.put("userId", "alice");
        replyReq.put("content", "金额已核实无误");
        assertEquals("ok", post("/api/comm/reply", replyReq).get("result"));

        // 部分反馈后仍锁定
        assertEquals("COMMUNICATING",
                get("/api/comm/status/" + managerTaskId + "?userId=manager").get("bizStatus"));

        // 6. 全量收回后恢复审批
        Map<String, Object> withdrawReq = new HashMap<>();
        withdrawReq.put("operator", "manager");
        withdrawReq.put("commTaskIds", new ArrayList<String>());
        assertEquals("ok", post("/api/comm/withdraw/" + sessionId, withdrawReq).get("result"));

        Map<String, Object> recovered = get("/api/comm/status/" + managerTaskId + "?userId=manager");
        assertEquals("NORMAL", recovered.get("bizStatus"));
        assertEquals(Boolean.TRUE, recovered.get("canApprove"));

        // 7. 经理可正常审批并流转到财务
        assertEquals("ok", post("/api/comm/demo/complete/" + managerTaskId, null).get("result"));
        findTask("finance", "finance_review");
        assertNotNull(pid);
    }

    /**
     * 与主用例同源但走另一条出口：全部反馈（不收回）后主待办也应解锁。
     * 覆盖 replyCommTask 中“最后一次反馈即解锁”的分支。
     */
    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    void lastReplyAlsoUnlocksRootTask() {
        List<Map<String, String>> targets = new ArrayList<>();
        targets.add(target("USER", "carol", "王五"));
        targets.add(target("USER", "dave", "赵六"));

        String pid = startAndSendToManager("applicant2", "请确认发票抬头是否合规", targets);
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);

        List<Map<String, Object>> commTasks = commTasksOf(managerTaskId);
        assertEquals(2, commTasks.size(), "沟通任务数量异常: " + commTasks);

        // 多对象发起后，引擎内必须真实存在两条旁路待办（不能只落下最后一个）。
        // 必须在任何人反馈之前断言：反馈会按设计删除该接收人的旁路待办。
        List<String> commAssignees = engineTaskService.createTaskQuery()
                .processInstanceId(pid).taskDefinitionKey("COMM").list().stream()
                .map(org.camunda.bpm.engine.task.Task::getAssignee)
                .collect(java.util.stream.Collectors.toList());
        assertEquals(2, commAssignees.size(), "引擎内沟通待办数量异常: " + dumpAllTasks());
        assertTrue(commAssignees.containsAll(java.util.Arrays.asList("carol", "dave")),
                "引擎内沟通待办缺失接收人: " + dumpAllTasks());

        // 沟通旁路任务本身不允许再次发起沟通（同样要在反馈删除之前校验）
        String carolCommTaskId = tasks("carol").stream()
                .filter(t -> "COMM".equals(t.get("taskDefKey")))
                .filter(t -> pid.equals(t.get("processInstanceId")))
                .map(t -> (String) t.get("taskId"))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "未找到 carol 的沟通待办: " + tasks("carol") + "；引擎内全部任务: " + dumpAllTasks()));
        Map<String, Object> commStatus = get("/api/comm/status/" + carolCommTaskId + "?userId=carol");
        assertEquals(Boolean.FALSE, commStatus.get("canContinueComm"), "沟通任务不应允许再次继续沟通: " + commStatus);

        // 第一条反馈：仍有未处理项，必须继续锁定
        replyTo(commTasks, "carol", "抬头合规");
        Map<String, Object> partial = get("/api/comm/status/" + managerTaskId + "?userId=manager");
        assertEquals("COMMUNICATING", partial.get("bizStatus"), "部分反馈后不应解锁: " + partial);
        assertEquals(Boolean.FALSE, partial.get("canApprove"), "部分反馈后仍应锁定审批");

        // 锁定期间仍能看到会话快照，且状态按人分别落库
        List<Map<String, Object>> partialTasks = (List<Map<String, Object>>) partial.get("commTasks");
        assertEquals(2, partialTasks.size(), "锁定期间沟通记录数量异常: " + partialTasks);
        for (Map<String, Object> t : partialTasks) {
            String expected = "carol".equals(t.get("targetId")) ? "REPLIED" : "PENDING";
            assertEquals(expected, t.get("status"), "沟通记录状态异常: " + t);
            assertEquals("请确认发票抬头是否合规", t.get("content"), "沟通内容中文往返异常（可能是编码问题）: " + t);
            if ("carol".equals(t.get("targetId"))) {
                assertEquals("王五", t.get("targetName"), "沟通对象中文往返异常: " + t);
                assertEquals("抬头合规", t.get("replyContent"), "反馈内容中文往返异常: " + t);
            }
        }

        // 沟通入口只对真实流程待办开放：流程待办 true
        assertEquals(Boolean.TRUE, partial.get("canContinueComm"), "流程待办应允许继续沟通: " + partial);

        // 最后一条反馈：走“反馈”而非“收回”，同样必须解锁
        replyTo(commTasks, "dave", "已确认无误");
        Map<String, Object> unlocked = get("/api/comm/status/" + managerTaskId + "?userId=manager");
        assertEquals("NORMAL", unlocked.get("bizStatus"), "全部反馈后未解锁: " + unlocked);
        assertEquals(Boolean.TRUE, unlocked.get("canApprove"), "全部反馈后应恢复审批");
        assertEquals(Boolean.TRUE, unlocked.get("canContinueComm"), "解锁后仍应允许发起新的沟通: " + unlocked);

        // 会话结束后 activeSessionId 被清空，因此 status 不再返回 commTasks（见遗留问题说明）；
        // 但接收人的旁路待办必须已被删除，不能再出现在待办列表里
        assertTrue(tasks("carol").stream().noneMatch(t -> pid.equals(t.get("processInstanceId"))),
                "carol 的沟通待办未清理: " + tasks("carol"));
        assertTrue(tasks("dave").stream().noneMatch(t -> pid.equals(t.get("processInstanceId"))),
                "dave 的沟通待办未清理: " + tasks("dave"));

        // 解锁后经理可正常审批并流转
        assertEquals("ok", post("/api/comm/demo/complete/" + managerTaskId, null).get("result"));
        findTaskInProcess("finance", "finance_review", pid);
    }

    /**
     * 追问（继续沟通）会新增子沟通任务：父任务已反馈不等于会话结束，
     * 只要还有子任务 PENDING，主待办必须保持锁定；子任务反馈后才解锁。
     */
    @Test
    @Order(3)
    @SuppressWarnings("unchecked")
    void continueCommunicationKeepsLockUntilChildReplied() {
        List<Map<String, String>> targets = new ArrayList<>();
        targets.add(target("USER", "erin", "孙七"));

        String pid = startAndSendToManager("applicant3", "请补充合同附件", targets);
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);

        List<Map<String, Object>> firstRound = commTasksOf(managerTaskId);
        assertEquals(1, firstRound.size(), "第一轮沟通记录数量异常: " + firstRound);
        replyTo(firstRound, "erin", "附件已补齐");

        Map<String, Object> afterFirst = get("/api/comm/status/" + managerTaskId + "?userId=manager");
        assertEquals("NORMAL", afterFirst.get("bizStatus"), "首轮反馈后应解锁: " + afterFirst);

        // 对已反馈的记录发起追问，锁定应重新生效
        Map<String, Object> contReq = new HashMap<>();
        contReq.put("parentCommTaskRecordId", firstRound.get(0).get("id"));
        contReq.put("userId", "manager");
        contReq.put("content", "请再确认附件版本号");
        List<Map<String, String>> nextTargets = new ArrayList<>();
        nextTargets.add(target("USER", "frank", "周八"));
        contReq.put("targets", nextTargets);
        assertEquals("ok", post("/api/comm/continue", contReq).get("result"));

        Map<String, Object> locked = get("/api/comm/status/" + managerTaskId + "?userId=manager");
        assertEquals("COMMUNICATING", locked.get("bizStatus"), "追问后应重新锁定: " + locked);
        assertEquals(Boolean.FALSE, locked.get("canApprove"), "追问后应禁止审批");

        List<Map<String, Object>> secondRound = (List<Map<String, Object>>) locked.get("commTasks");
        assertEquals(2, secondRound.size(), "追问后沟通记录数量异常: " + secondRound);
        assertEquals(2, locked.get("currentRound"), "会话轮次未递增: " + locked);

        replyTo(secondRound, "frank", "版本号 V2 正确");
        Map<String, Object> finalStatus = get("/api/comm/status/" + managerTaskId + "?userId=manager");
        assertEquals("NORMAL", finalStatus.get("bizStatus"), "子任务反馈后未解锁: " + finalStatus);
        assertEquals(Boolean.TRUE, finalStatus.get("canApprove"), "子任务反馈后应恢复审批");
    }

    /**
     * 会话关闭后（全部反馈/收回，activeSessionId 被清空）仍要能查到这次沟通的历史，
     * 否则经理事后无法回溯沟通内容与回复。
     */
    @Test
    @Order(4)
    @SuppressWarnings("unchecked")
    void historyVisibleAfterSessionClosed() {
        List<Map<String, String>> targets = new ArrayList<>();
        targets.add(target("USER", "grace", "吴九"));

        String pid = startAndSendToManager("applicant4", "请确认付款条件", targets);
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);

        replyTo(commTasksOf(managerTaskId), "grace", "付款条件已确认");

        Map<String, Object> closed = get("/api/comm/status/" + managerTaskId + "?userId=manager");
        assertEquals("NORMAL", closed.get("bizStatus"), "全部反馈后应解锁: " + closed);
        assertNotNull(closed.get("sessionId"), "会话结束后仍应返回最近一次会话 ID: " + closed);
        assertEquals(Boolean.FALSE, closed.get("sessionActive"), "已结束的会话不应标记为进行中: " + closed);
        assertEquals(Boolean.FALSE, closed.get("canWithdraw"), "已结束的会话不可收回: " + closed);

        List<Map<String, Object>> history = (List<Map<String, Object>>) closed.get("commTasks");
        assertNotNull(history, "会话结束后 commTasks 不应为 null: " + closed);
        assertEquals(1, history.size(), "会话结束后应可回看历史沟通记录: " + closed);
        Map<String, Object> record = history.get(0);
        assertEquals("grace", record.get("targetId"));
        assertEquals("吴九", record.get("targetName"));
        assertEquals("REPLIED", record.get("status"));
        assertEquals("请确认付款条件", record.get("content"));
        assertEquals("付款条件已确认", record.get("replyContent"));
        assertEquals("grace", record.get("replyUserId"));
        assertNotNull(record.get("replyTime"), "历史记录应包含反馈时间: " + record);
    }

    /**
     * 身份校验：非待办处理人不能发起，非沟通对象本人不能反馈，非发起人不能追问。
     * 当前实现是“有身份参数但不可信”的典型场景，必须在服务端兜底。
     */
    @Test
    @Order(5)
    @SuppressWarnings("unchecked")
    void identityChecksBlockImpersonation() {
        List<Map<String, String>> targets = new ArrayList<>();
        targets.add(target("USER", "henry", "郑十"));

        String pid = startAndSendToManager("applicant5", "请核实收货地址", targets);
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);
        List<Map<String, Object>> commTasks = commTasksOf(managerTaskId);
        assertEquals(1, commTasks.size(), "沟通记录数量异常: " + commTasks);

        // 1. 冒充接收人反馈
        Map<String, Object> forgedReply = new HashMap<>();
        forgedReply.put("commTaskRecordId", commTasks.get(0).get("id"));
        forgedReply.put("userId", "mallory");
        forgedReply.put("content", "我替他确认了");
        Map<String, Object> replyResp = post("/api/comm/reply", forgedReply);
        assertEquals(Boolean.TRUE, replyResp.get("error"), "非接收人反馈应被拒绝: " + replyResp);
        assertTrue(String.valueOf(replyResp.get("message")).contains("本人"),
                "拒绝提示应说明仅本人可反馈: " + replyResp);

        // 2. 非待办处理人发起沟通
        Map<String, Object> forgedStart = new HashMap<>();
        forgedStart.put("taskId", managerTaskId);
        forgedStart.put("initiator", "mallory");
        forgedStart.put("content", "代发起");
        forgedStart.put("targets", targets);
        Map<String, Object> startResp = post("/api/comm/start", forgedStart);
        assertEquals(Boolean.TRUE, startResp.get("error"), "非处理人发起沟通应被拒绝: " + startResp);

        // 3. 非发起人追问
        Map<String, Object> forgedContinue = new HashMap<>();
        forgedContinue.put("parentCommTaskRecordId", commTasks.get(0).get("id"));
        forgedContinue.put("userId", "mallory");
        forgedContinue.put("content", "代追问");
        forgedContinue.put("targets", targets);
        Map<String, Object> continueResp = post("/api/comm/continue", forgedContinue);
        assertEquals(Boolean.TRUE, continueResp.get("error"), "非发起人追问应被拒绝: " + continueResp);

        // 被拒绝的调用不得污染状态：记录仍是 PENDING，主待办仍锁定
        Map<String, Object> locked = get("/api/comm/status/" + managerTaskId + "?userId=manager");
        assertEquals("COMMUNICATING", locked.get("bizStatus"), "越权调用后状态不应改变: " + locked);
        List<Map<String, Object>> after = (List<Map<String, Object>>) locked.get("commTasks");
        assertEquals("PENDING", after.get(0).get("status"), "越权反馈不应落库: " + after);

        // 4. 本人反馈仍然可用
        replyTo(after, "henry", "地址正确");
        assertEquals("NORMAL",
                get("/api/comm/status/" + managerTaskId + "?userId=manager").get("bizStatus"));
    }

    /**
     * 异常语义：只有“沟通中拦截”是 200 + error:true，
     * 真实的流程错误（任务不存在等）必须是 5xx，不能被伪装成业务提示。
     */
    @Test
    @Order(6)
    void realErrorsAreNotMaskedAsBusinessErrors() {
        ResponseEntity<String> resp = rest.exchange("/api/comm/demo/complete/not-exist-task-id",
                HttpMethod.POST, null, String.class);
        assertTrue(resp.getStatusCode().is5xxServerError(),
                "完成任务失败应返回 5xx，实际: " + resp.getStatusCode() + " " + resp.getBody());
        assertTrue(resp.getBody() == null || !resp.getBody().contains("\"error\":true"),
                "真实错误不应被翻译成 error:true: " + resp.getBody());
    }

    private Map<String, String> target(String type, String id, String name) {
        Map<String, String> t = new HashMap<>();
        t.put("type", type);
        t.put("id", id);
        t.put("name", name);
        return t;
    }
}
