package com.example.camundademo;

import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.security.DemoUserInitializer;
import com.fasterxml.jackson.core.type.TypeReference;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端冒烟测试：覆盖"发起沟通 -> 引擎拦截审批 -> 反馈 -> 收回 -> 恢复审批"完整链路。
 *
 * <p>所有请求都以真实登录用户身份发出（HTTP Basic + Camunda Identity 校验密码），
 * 业务接口不再接受请求体里的 userId / initiator / operator。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommunicationFlowTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private org.camunda.bpm.engine.TaskService engineTaskService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // ===== HTTP 辅助 =====

    private HttpHeaders authHeaders(String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (user != null) {
            String raw = user + ":" + DemoUserInitializer.DEMO_PASSWORD;
            headers.set(HttpHeaders.AUTHORIZATION, "Basic "
                    + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        }
        return headers;
    }

    private Map<String, Object> post(String path, Object body, String user) {
        return postRaw(path, body, user).getBody();
    }

    private ResponseEntity<Map<String, Object>> postRaw(String path, Object body, String user) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, authHeaders(user)),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
    }

    private Map<String, Object> get(String path, String user) {
        return getRaw(path, user).getBody();
    }

    private ResponseEntity<Map<String, Object>> getRaw(String path, String user) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(user)),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
    }

    private String dumpAllTasks() {
        return engineTaskService.createTaskQuery().list().stream()
                .map(t -> t.getTaskDefinitionKey() + "/" + t.getAssignee() + "/" + t.getProcessInstanceId())
                .collect(java.util.stream.Collectors.toList()).toString();
    }

    private List<Map<String, Object>> tasks(String assignee) {
        // 先按 String 取响应，再解析：接口出错时（401/500）body 是 JSON 对象，
        // 直接按 List 反序列化会抛“无法从对象解析数组”，把真实状态码掩盖掉。
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
        replyReq.put("content", content);
        assertEquals("ok", post("/api/comm/reply", replyReq, targetId).get("result"), "反馈失败: " + targetId);
    }

    /** 以 applicant 身份启动流程并完成申请，再由 manager 发起沟通 */
    private String startAndSendToManager(String applicant, String content, List<Map<String, String>> targets) {
        Map<String, Object> started = post("/api/comm/demo/start", null, applicant);
        assertNotNull(started, "启动流程无响应");
        String pid = (String) started.get("processInstanceId");
        assertNotNull(pid, "启动流程失败: " + started);

        String applyTaskId = findTaskInProcess(applicant, "apply", pid);
        assertEquals("ok", post("/api/comm/demo/complete/" + applyTaskId, null, applicant).get("result"));
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);

        Map<String, Object> req = new HashMap<>();
        req.put("taskId", managerTaskId);
        req.put("content", content);
        req.put("targets", targets);
        Map<String, Object> startResp = post("/api/comm/start", req, "manager");
        assertNotNull(startResp.get("sessionId"), "发起沟通失败: " + startResp);

        // 沟通中审批被拦截，作为后续断言的前置条件
        Map<String, Object> blocked = post("/api/comm/demo/complete/" + managerTaskId, null, "manager");
        assertEquals(Boolean.TRUE, blocked.get("error"), "沟通中审批未被拦截: " + blocked);
        return pid;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> commTasksOf(String taskId) {
        return (List<Map<String, Object>>) get("/api/comm/status/" + taskId, "manager").get("commTasks");
    }

    @Test
    @Order(1)
    void fullCommunicationFlow() {
        // 1. 启动流程，申请人完成"填写申请"
        Map<String, Object> started = post("/api/comm/demo/start", null, "applicant");
        assertNotNull(started, "启动流程无响应");
        assertNotNull(started.get("processInstanceId"), "启动流程失败: " + started);
        String pid = (String) started.get("processInstanceId");

        String applyTaskId = findTaskInProcess("applicant", "apply", pid);
        assertNotNull(post("/api/comm/demo/complete/" + applyTaskId, null, "applicant").get("result"));

        // 2. 经理发起沟通给 alice / bob
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);
        Map<String, Object> req = new HashMap<>();
        req.put("taskId", managerTaskId);
        req.put("content", "请核实合同金额是否正确");
        List<Map<String, String>> targets = new ArrayList<>();
        targets.add(target("USER", "alice", "张三"));
        targets.add(target("USER", "bob", "李四"));
        req.put("targets", targets);

        Map<String, Object> startResp = post("/api/comm/start", req, "manager");
        assertNotNull(startResp.get("sessionId"), "发起沟通失败: " + startResp);
        String sessionId = (String) startResp.get("sessionId");

        // 3. 沟通中审批必须被引擎拦截
        Map<String, Object> blocked = post("/api/comm/demo/complete/" + managerTaskId, null, "manager");
        assertEquals(Boolean.TRUE, blocked.get("error"), "沟通中审批未被拦截: " + blocked);
        assertTrue(String.valueOf(blocked.get("message")).contains("沟通中"), "拦截提示异常: " + blocked);

        // 4. 状态应为 COMMUNICATING，且有 2 条沟通任务
        Map<String, Object> status = get("/api/comm/status/" + managerTaskId, "manager");
        assertEquals("COMMUNICATING", status.get("bizStatus"));
        assertEquals(Boolean.FALSE, status.get("canApprove"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> commTasks = (List<Map<String, Object>>) status.get("commTasks");
        assertEquals(2, commTasks.size(), "沟通任务数量异常: " + commTasks);

        // 5. alice 反馈
        replyTo(commTasks, "alice", "金额已核实无误");

        // 部分反馈后仍锁定
        assertEquals("COMMUNICATING",
                get("/api/comm/status/" + managerTaskId, "manager").get("bizStatus"));

        // 6. 全量收回后恢复审批
        Map<String, Object> withdrawReq = new HashMap<>();
        withdrawReq.put("commTaskIds", new ArrayList<String>());
        assertEquals("ok", post("/api/comm/withdraw/" + sessionId, withdrawReq, "manager").get("result"));

        Map<String, Object> recovered = get("/api/comm/status/" + managerTaskId, "manager");
        assertEquals("NORMAL", recovered.get("bizStatus"));
        assertEquals(Boolean.TRUE, recovered.get("canApprove"));

        // 7. 经理可正常审批并流转到财务
        assertEquals("ok", post("/api/comm/demo/complete/" + managerTaskId, null, "manager").get("result"));
        findTask("finance", "finance_review");
        assertNotNull(pid);
    }

    /**
     * 与主用例同源但走另一条出口：全部反馈（不收回）后主待办也应解锁。
     * 覆盖 replyCommTask 中"最后一次反馈即解锁"的分支。
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
        List<org.camunda.bpm.engine.task.Task> commEngineTasks = engineTaskService.createTaskQuery()
                .processInstanceId(pid).taskDefinitionKeyLike("%" + BizCommTask.TASK_KEY_SUFFIX).list();
        List<String> commAssignees = commEngineTasks.stream()
                .map(org.camunda.bpm.engine.task.Task::getAssignee)
                .collect(java.util.stream.Collectors.toList());
        assertEquals(2, commAssignees.size(), "引擎内沟通待办数量异常: " + dumpAllTasks());
        assertTrue(commAssignees.containsAll(java.util.Arrays.asList("carol", "dave")),
                "引擎内沟通待办缺失接收人: " + dumpAllTasks());
        // taskDefinitionKey 必须带上源节点前缀，才能看出是哪个节点发起的沟通；
        // name 必须沿用源待办的名称（不能是拼出来的“沟通：…”），否则待办列表无法与源节点对齐
        String sourceTaskName = engineTaskService.createTaskQuery().taskId(managerTaskId).singleResult().getName();
        assertNotNull(sourceTaskName, "源待办本身应有名称: " + dumpAllTasks());
        for (org.camunda.bpm.engine.task.Task t : commEngineTasks) {
            assertEquals("manager_review" + BizCommTask.TASK_KEY_SUFFIX, t.getTaskDefinitionKey(),
                    "沟通任务 key 应带源节点前缀: " + dumpAllTasks());
            assertEquals(sourceTaskName, t.getName(),
                    "沟通任务名称应沿用源待办名称: " + dumpAllTasks());
        }

        // 沟通旁路任务本身不允许再次发起沟通（同样要在反馈删除之前校验）
        String carolCommTaskId = tasks("carol").stream()
                .filter(t -> BizCommTask.isCommTaskKey((String) t.get("taskDefKey")))
                .filter(t -> pid.equals(t.get("processInstanceId")))
                .map(t -> (String) t.get("taskId"))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "未找到 carol 的沟通待办: " + tasks("carol") + "；引擎内全部任务: " + dumpAllTasks()));
        Map<String, Object> commStatus = get("/api/comm/status/" + carolCommTaskId, "carol");
        assertEquals(Boolean.FALSE, commStatus.get("canContinueComm"), "沟通任务不应允许再次继续沟通: " + commStatus);

        // 第一条反馈：仍有未处理项，必须继续锁定
        replyTo(commTasks, "carol", "抬头合规");
        Map<String, Object> partial = get("/api/comm/status/" + managerTaskId, "manager");
        assertEquals("COMMUNICATING", partial.get("bizStatus"), "部分反馈后不应解锁: " + partial);
        assertEquals(Boolean.FALSE, partial.get("canApprove"), "部分反馈后仍应锁定审批");

        // 锁定期间仍能看到会话快照，且状态按人分别落库
        List<Map<String, Object>> partialTasks = (List<Map<String, Object>>) partial.get("commTasks");
        assertEquals(2, partialTasks.size(), "锁定期间沟通记录数量异常: " + partialTasks);
        for (Map<String, Object> t : partialTasks) {
            String expected = "carol".equals(t.get("targetId")) ? "REPLIED" : "PENDING";
            assertEquals(expected, t.get("status"), "沟通记录状态异常: " + t);
            // 来源节点必须落到业务表，报表可直接按节点聚合
            assertEquals("manager_review", t.get("sourceTaskDefKey"),
                    "沟通记录应记录来源节点 key: " + t);
            assertEquals("请确认发票抬头是否合规", t.get("content"), "沟通内容中文往返异常（可能是编码问题）: " + t);
            if ("carol".equals(t.get("targetId"))) {
                assertEquals("王五", t.get("targetName"), "沟通对象中文往返异常: " + t);
                assertEquals("抬头合规", t.get("replyContent"), "反馈内容中文往返异常: " + t);
                // 反馈人必须是认证用户，而不是请求体里的值
                assertEquals("carol", t.get("replyUserId"), "反馈人应取自认证上下文: " + t);
            }
        }

        // 沟通入口只对真实流程待办开放：流程待办 true
        assertEquals(Boolean.TRUE, partial.get("canContinueComm"), "流程待办应允许继续沟通: " + partial);

        // 最后一条反馈：走"反馈"而非"收回"，同样必须解锁
        replyTo(commTasks, "dave", "已确认无误");
        Map<String, Object> unlocked = get("/api/comm/status/" + managerTaskId, "manager");
        assertEquals("NORMAL", unlocked.get("bizStatus"), "全部反馈后未解锁: " + unlocked);
        assertEquals(Boolean.TRUE, unlocked.get("canApprove"), "全部反馈后应恢复审批");
        assertEquals(Boolean.TRUE, unlocked.get("canContinueComm"), "解锁后仍应允许发起新的沟通: " + unlocked);

        // 会话结束后 activeSessionId 被清空，但接收人的旁路待办必须已被删除
        assertTrue(tasks("carol").stream().noneMatch(t -> pid.equals(t.get("processInstanceId"))),
                "carol 的沟通待办未清理: " + tasks("carol"));
        assertTrue(tasks("dave").stream().noneMatch(t -> pid.equals(t.get("processInstanceId"))),
                "dave 的沟通待办未清理: " + tasks("dave"));

        // 解锁后经理可正常审批并流转
        assertEquals("ok", post("/api/comm/demo/complete/" + managerTaskId, null, "manager").get("result"));
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

        Map<String, Object> afterFirst = get("/api/comm/status/" + managerTaskId, "manager");
        assertEquals("NORMAL", afterFirst.get("bizStatus"), "首轮反馈后应解锁: " + afterFirst);

        // 对已反馈的记录发起追问，锁定应重新生效
        Map<String, Object> contReq = new HashMap<>();
        contReq.put("parentCommTaskRecordId", firstRound.get(0).get("id"));
        contReq.put("content", "请再确认附件版本号");
        List<Map<String, String>> nextTargets = new ArrayList<>();
        nextTargets.add(target("USER", "frank", "周八"));
        contReq.put("targets", nextTargets);
        assertEquals("ok", post("/api/comm/continue", contReq, "manager").get("result"));

        Map<String, Object> locked = get("/api/comm/status/" + managerTaskId, "manager");
        assertEquals("COMMUNICATING", locked.get("bizStatus"), "追问后应重新锁定: " + locked);
        assertEquals(Boolean.FALSE, locked.get("canApprove"), "追问后应禁止审批");

        List<Map<String, Object>> secondRound = (List<Map<String, Object>>) locked.get("commTasks");
        assertEquals(2, secondRound.size(), "追问后沟通记录数量异常: " + secondRound);
        assertEquals(2, locked.get("currentRound"), "会话轮次未递增: " + locked);
        // 追问记录同样归属会话发起节点，而不是父沟通任务自身的 "#COMM" key
        for (Map<String, Object> t : secondRound) {
            assertEquals("manager_review", t.get("sourceTaskDefKey"),
                    "追问记录应继承会话发起节点: " + t);
        }

        // 追问任务同样沿用源待办名称，靠 description 区分“追问 + 轮次 + 内容”，
        // 这样待办列表里追问项与源节点同名，不会出现“沟通追问：…”这类与节点名不一致的标题
        List<org.camunda.bpm.engine.task.Task> followUpTasks = engineTaskService.createTaskQuery()
                .processInstanceId(pid)
                .taskDefinitionKeyLike("%" + BizCommTask.TASK_KEY_SUFFIX)
                .taskAssignee("frank")
                .list();
        assertEquals(1, followUpTasks.size(), "追问待办数量异常: " + dumpAllTasks());
        org.camunda.bpm.engine.task.Task followUp = followUpTasks.get(0);
        String followUpSourceName = engineTaskService.createTaskQuery().taskId(managerTaskId).singleResult().getName();
        assertEquals(followUpSourceName, followUp.getName(),
                "追问任务名称应沿用源待办名称: " + dumpAllTasks());
        assertTrue(followUp.getDescription() != null && followUp.getDescription().contains("追问"),
                "追问任务描述应能区分追问: " + followUp.getDescription());
        assertTrue(followUp.getDescription().contains("第 2 轮"),
                "追问任务描述应包含轮次: " + followUp.getDescription());

        replyTo(secondRound, "frank", "版本号 V2 正确");
        Map<String, Object> finalStatus = get("/api/comm/status/" + managerTaskId, "manager");
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

        Map<String, Object> closed = get("/api/comm/status/" + managerTaskId, "manager");
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
     * 身份校验：认证用户与业务数据不一致时必须拒绝——非待办处理人不能发起，
     * 非沟通对象本人不能反馈，非发起人不能追问。
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

        // 1. 冒充接收人反馈：以 mallory 认证，body 里再伪造 userId=henry 也没用
        Map<String, Object> forgedReply = new HashMap<>();
        forgedReply.put("commTaskRecordId", commTasks.get(0).get("id"));
        forgedReply.put("userId", "henry");
        forgedReply.put("content", "我替他确认了");
        Map<String, Object> replyResp = post("/api/comm/reply", forgedReply, "mallory");
        assertEquals(Boolean.TRUE, replyResp.get("error"), "非接收人反馈应被拒绝: " + replyResp);
        assertTrue(String.valueOf(replyResp.get("message")).contains("本人"),
                "拒绝提示应说明仅本人可反馈: " + replyResp);

        // 2. 非待办处理人发起沟通：body 里写 initiator=manager 也不能绕过
        Map<String, Object> forgedStart = new HashMap<>();
        forgedStart.put("taskId", managerTaskId);
        forgedStart.put("initiator", "manager");
        forgedStart.put("content", "代发起");
        forgedStart.put("targets", targets);
        Map<String, Object> startResp = post("/api/comm/start", forgedStart, "mallory");
        assertEquals(Boolean.TRUE, startResp.get("error"), "非处理人发起沟通应被拒绝: " + startResp);

        // 3. 非发起人追问
        Map<String, Object> forgedContinue = new HashMap<>();
        forgedContinue.put("parentCommTaskRecordId", commTasks.get(0).get("id"));
        forgedContinue.put("content", "代追问");
        forgedContinue.put("targets", targets);
        Map<String, Object> continueResp = post("/api/comm/continue", forgedContinue, "mallory");
        assertEquals(Boolean.TRUE, continueResp.get("error"), "非发起人追问应被拒绝: " + continueResp);

        // 被拒绝的调用不得污染状态：记录仍是 PENDING，主待办仍锁定
        Map<String, Object> locked = get("/api/comm/status/" + managerTaskId, "manager");
        assertEquals("COMMUNICATING", locked.get("bizStatus"), "越权调用后状态不应改变: " + locked);
        List<Map<String, Object>> after = (List<Map<String, Object>>) locked.get("commTasks");
        assertEquals("PENDING", after.get(0).get("status"), "越权反馈不应落库: " + after);

        // 4. 本人反馈仍然可用，且请求体里伪造的 userId 被忽略
        Map<String, Object> realReply = new HashMap<>();
        realReply.put("commTaskRecordId", after.get(0).get("id"));
        realReply.put("userId", "mallory");
        realReply.put("content", "地址正确");
        assertEquals("ok", post("/api/comm/reply", realReply, "henry").get("result"));
        assertEquals("NORMAL",
                get("/api/comm/status/" + managerTaskId, "manager").get("bizStatus"));

        Map<String, Object> closed = get("/api/comm/status/" + managerTaskId, "manager");
        List<Map<String, Object>> history = (List<Map<String, Object>>) closed.get("commTasks");
        assertEquals("henry", history.get(0).get("replyUserId"),
                "反馈人必须取自认证上下文，而不是请求体: " + history.get(0));
    }

    /**
     * 异常语义：只有"沟通中拦截"是 200 + error:true，
     * 真实的流程错误（任务不存在等）必须是 5xx，不能被伪装成业务提示。
     */
    @Test
    @Order(6)
    void realErrorsAreNotMaskedAsBusinessErrors() {
        ResponseEntity<String> resp = rest.exchange("/api/comm/demo/complete/not-exist-task-id",
                HttpMethod.POST, new HttpEntity<>(authHeaders("manager")), String.class);
        assertTrue(resp.getStatusCode().is5xxServerError(),
                "完成任务失败应返回 5xx，实际: " + resp.getStatusCode() + " " + resp.getBody());
        assertTrue(resp.getBody() == null || !resp.getBody().contains("\"error\":true"),
                "真实错误不应被翻译成 error:true: " + resp.getBody());
    }

    /**
     * 认证上下文本身：未认证一律 401；不能操作他人待办；不能查他人待办列表。
     */
    @Test
    @Order(8)
    void authenticationIsEnforcedOnEveryEndpoint() {
        // 准备一条 manager 的真实待办
        Map<String, Object> started = post("/api/comm/demo/start", null, "applicant6");
        String pid = (String) started.get("processInstanceId");
        String applyTaskId = findTaskInProcess("applicant6", "apply", pid);
        post("/api/comm/demo/complete/" + applyTaskId, null, "applicant6");
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);

        // 1. 未认证：任何业务接口都应 401
        ResponseEntity<Map<String, Object>> unauthenticated =
                rest.exchange("/api/comm/status/" + managerTaskId, HttpMethod.GET,
                        new HttpEntity<>(authHeaders(null)),
                        new ParameterizedTypeReference<Map<String, Object>>() {
                        });
        assertEquals(401, unauthenticated.getStatusCode().value(),
                "未认证请求必须返回 401: " + unauthenticated.getBody());

        ResponseEntity<Map<String, Object>> noAuthWrite =
                rest.exchange("/api/comm/start", HttpMethod.POST,
                        new HttpEntity<>(new HashMap<String, Object>(), authHeaders(null)),
                        new ParameterizedTypeReference<Map<String, Object>>() {
                        });
        assertEquals(401, noAuthWrite.getStatusCode().value(), "未认证写请求必须返回 401");

        // 2. 不能完成他人的待办
        ResponseEntity<Map<String, Object>> forbiddenComplete = postRaw(
                "/api/comm/demo/complete/" + managerTaskId, null, "alice");
        assertEquals(403, forbiddenComplete.getStatusCode().value(),
                "他人待办应返回 403: " + forbiddenComplete.getBody());

        // 3. 不能查询他人的待办列表（错误响应是 JSON 对象，用 String 接收只看状态码）
        ResponseEntity<String> forbiddenList = rest.exchange(
                "/api/comm/demo/tasks?assignee=alice", HttpMethod.GET,
                new HttpEntity<>(authHeaders("manager")), String.class);
        assertEquals(403, forbiddenList.getStatusCode().value(), "越权查询待办应返回 403");

        // 4. 清理：经理正常完成待办，避免残留影响其他用例
        ResponseEntity<Map<String, Object>> cleanup =
                postRaw("/api/comm/demo/complete/" + managerTaskId, null, "manager");
        assertEquals("ok", cleanup.getBody() == null ? null : cleanup.getBody().get("result"),
                "清理待办失败: " + cleanup.getStatusCode() + " " + cleanup.getBody());

        // 5. 错误密码不能通过。
        //    放在最后：底层 HttpClient 会按 host 复用连接与认证缓存，
        //    错误凭据会污染同一客户端后续请求（实测会让后面的正常请求也 401）。
        HttpHeaders wrongPwd = new HttpHeaders();
        wrongPwd.setContentType(MediaType.APPLICATION_JSON);
        wrongPwd.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
                .encodeToString(("manager:wrong-password").getBytes(StandardCharsets.UTF_8)));
        ResponseEntity<Map<String, Object>> badCredential = rest.exchange(
                "/api/comm/status/" + managerTaskId, HttpMethod.GET, new HttpEntity<>(wrongPwd),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertEquals(401, badCredential.getStatusCode().value(), "错误密码必须返回 401");
    }

    /**
     * 回归：前端人员选择器提交 {type,name,value}（没有 id）时必须能正常发起沟通。
     *
     * <p>账号取 name、显示名取 value。原实现只读 id，targetId 为 null 后落库到
     * BIZ_COMM_TASK_.TARGET_ID_（NOT NULL），接口直接 500。</p>
     *
     * <p>后半段断言：真正缺账号标识时必须是业务错误（200 + error），不能退化成 500。</p>
     *
     * <p>顺序放在认证用例之前：认证用例末尾会用错误密码验证 401，底层 HttpClient
     * 复用连接与认证缓存，会把后续请求一起打成 401。</p>
     */
    @Test
    @Order(7)
    @SuppressWarnings("unchecked")
    void nameValueTargetsAreAccepted() {
        Map<String, Object> started = post("/api/comm/demo/start", null, "applicant6");
        assertNotNull(started, "启动流程无响应");
        String pid = (String) started.get("processInstanceId");
        String applyTaskId = findTaskInProcess("applicant6", "apply", pid);
        assertEquals("ok", post("/api/comm/demo/complete/" + applyTaskId, null, "applicant6").get("result"));
        String managerTaskId = findTaskInProcess("manager", "manager_review", pid);

        // 用户实际提交的报文格式：type + name(账号) + value(显示名)
        List<Map<String, String>> targets = new ArrayList<>();
        targets.add(nameValueTarget("carol", "王五"));
        targets.add(nameValueTarget("dave", "赵六"));

        Map<String, Object> req = new HashMap<>();
        req.put("taskId", managerTaskId);
        req.put("content", "验证发起沟通，首发，manager");
        req.put("targets", targets);

        ResponseEntity<Map<String, Object>> resp = postRaw("/api/comm/start", req, "manager");
        assertEquals(200, resp.getStatusCode().value(), "发起沟通不应返回 5xx: " + resp.getBody());
        assertNotNull(resp.getBody() == null ? null : resp.getBody().get("sessionId"),
                "发起沟通失败: " + resp.getBody());

        List<Map<String, Object>> commTasks = commTasksOf(managerTaskId);
        assertEquals(2, commTasks.size(), "沟通任务数量异常: " + commTasks);
        assertEquals("carol", commTaskOf(commTasks, "carol").get("targetId"),
                "账号标识应取自 name: " + commTasks);
        assertEquals("王五", commTaskOf(commTasks, "carol").get("targetName"),
                "显示名应取自 value: " + commTasks);

        List<String> commAssignees = engineTaskService.createTaskQuery()
                .processInstanceId(pid).taskDefinitionKeyLike("%" + BizCommTask.TASK_KEY_SUFFIX).list().stream()
                .map(org.camunda.bpm.engine.task.Task::getAssignee)
                .collect(java.util.stream.Collectors.toList());
        assertTrue(commAssignees.containsAll(java.util.Arrays.asList("carol", "dave")),
                "引擎内沟通待办缺失接收人: " + dumpAllTasks());

        // 解析不出账号：必须是业务错误，而不是 TARGET_ID_ NOT NULL 触发的 500
        Map<String, Object> started2 = post("/api/comm/demo/start", null, "applicant6");
        String pid2 = (String) started2.get("processInstanceId");
        String applyTaskId2 = findTaskInProcess("applicant6", "apply", pid2);
        post("/api/comm/demo/complete/" + applyTaskId2, null, "applicant6");
        String managerTaskId2 = findTaskInProcess("manager", "manager_review", pid2);

        List<Map<String, String>> badTargets = new ArrayList<>();
        Map<String, String> blank = new HashMap<>();
        blank.put("type", "USER");
        badTargets.add(blank);

        Map<String, Object> badReq = new HashMap<>();
        badReq.put("taskId", managerTaskId2);
        badReq.put("content", "缺少账号标识");
        badReq.put("targets", badTargets);

        ResponseEntity<Map<String, Object>> badResp = postRaw("/api/comm/start", badReq, "manager");
        assertEquals(200, badResp.getStatusCode().value(), "参数错误不应变成 5xx: " + badResp.getBody());
        assertEquals(Boolean.TRUE, badResp.getBody().get("error"), "应返回业务错误: " + badResp.getBody());
        assertTrue(String.valueOf(badResp.getBody().get("message")).contains("账号标识"),
                "错误提示不明确: " + badResp.getBody());
    }

    private Map<String, String> nameValueTarget(String account, String displayName) {
        Map<String, String> t = new HashMap<>();
        t.put("type", "USER");
        t.put("name", account);
        t.put("value", displayName);
        return t;
    }

    private Map<String, Object> commTaskOf(List<Map<String, Object>> commTasks, String targetId) {
        return commTasks.stream().filter(t -> targetId.equals(t.get("targetId"))).findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到 " + targetId + " 的沟通记录: " + commTasks));
    }

    private Map<String, String> target(String type, String id, String name) {
        Map<String, String> t = new HashMap<>();
        t.put("type", type);
        t.put("id", id);
        t.put("name", name);
        return t;
    }
}
