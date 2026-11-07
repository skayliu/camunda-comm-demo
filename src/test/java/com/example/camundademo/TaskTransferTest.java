package com.example.camundademo;

import com.example.camundademo.security.DemoUserInitializer;
import com.example.camundademo.support.TestSession;
import com.example.camundademo.service.CommunicationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricTaskInstance;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待办转办端到端测试。
 *
 * <p>覆盖：改派生效、转办链落库、原办理人待办消失、接收人待办出现并带转办标记、
 * 非办理人/转给自己/接收人不存在/沟通中/沟通待办这几条拒绝路径、转办记录可见性，
 * 以及转出方转办后仍能打开流程跟踪。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.TestPropertySource(properties = {
        // 独立 H2 库，避免与其他测试类共享内存库导致身份数据与流程实例相互污染
        "spring.datasource.url=jdbc:h2:mem:transferdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class TaskTransferTest {

    @LocalServerPort
    private int port;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private CommunicationService communicationService;

    private final ObjectMapper json = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private void ensureDemoUser(String userId) {
        org.camunda.bpm.engine.identity.User user =
                identityService.createUserQuery().userId(userId).singleResult();
        if (user == null) {
            user = identityService.newUser(userId);
            user.setFirstName(userId);
            user.setLastName("(demo)");
        }
        user.setPassword(DemoUserInitializer.DEMO_PASSWORD);
        identityService.saveUser(user);
    }

    private record ApiResponse(int status, String body) {
        boolean is2xx() {
            return status >= 200 && status < 300;
        }
    }

    private ApiResponse call(HttpMethod method, String path, Object body, String user) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (user != null) {
            // 会话式登录：先换 JSESSIONID，再放进 Cookie 头
            builder.header("Cookie", TestSession.cookie(port, user));
            // 写操作还要带 CSRF 令牌（与 XSRF-TOKEN cookie 成对）
            if (method != HttpMethod.GET) {
                builder.header("X-XSRF-TOKEN", TestSession.csrfToken(port, user));
            }
        }
        try {
            if (body == null) {
                builder.method(method.name(), HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json;charset=UTF-8");
                builder.method(method.name(), HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(body), StandardCharsets.UTF_8));
            }
            HttpResponse<String> resp = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new ApiResponse(resp.statusCode(), resp.body());
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + path + " 请求失败", e);
        }
    }

    private Map<String, Object> parseObject(String body) {
        try {
            return json.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("响应不是 JSON 对象: " + body, e);
        }
    }

    private List<Map<String, Object>> parseList(String body) {
        try {
            return json.readValue(body, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("响应不是 JSON 数组: " + body, e);
        }
    }

    /** 运行中任务按办理人查 */
    private Task taskOf(String userId, String taskDefKey) {
        return taskService.createTaskQuery()
                .taskAssignee(userId)
                .taskDefinitionKey(taskDefKey)
                .list().stream().findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> todoOf(String user, String pid) {
        ApiResponse resp = call(HttpMethod.GET, "/api/wb/todo?type=all&page=1&size=50", null, user);
        assertTrue(resp.is2xx(), "查询待办失败: " + resp.status() + " " + resp.body());
        Map<String, Object> page = parseObject(resp.body());
        List<Map<String, Object>> list = (List<Map<String, Object>>) page.get("list");
        return list.stream()
                .filter(item -> pid.equals(item.get("processInstanceId")))
                .findFirst().orElse(null);
    }

    @Test
    void transferEndToEnd() {
        for (String user : List.of("applicant", "manager", "alice", "bob", "carol")) {
            ensureDemoUser(user);
        }

        // 1. 起流程并推进到 manager_review
        identityService.setAuthenticatedUserId("applicant");
        ProcessInstance pi;
        try {
            pi = runtimeService.startProcessInstanceByKey("approval", Map.of("applicant", "applicant"));
        } finally {
            identityService.clearAuthentication();
        }
        String pid = pi.getId();

        Task applyTask = taskOf("applicant", "apply");
        assertNotNull(applyTask, "未生成申请人待办");
        taskService.complete(applyTask.getId());

        Task managerTask = taskOf("manager", "manager_review");
        assertNotNull(managerTask, "流程未流转到经理审批");
        String taskId = managerTask.getId();

        // 2. 拒绝路径：非办理人不能转办
        ApiResponse byOther = call(HttpMethod.POST, "/api/wb/task/" + taskId + "/transfer",
                Map.of("targetUserId", "bob"), "alice");
        assertTrue(byOther.is2xx(), "业务拒绝应返回 200 + error:true，实际 " + byOther.status());
        Map<String, Object> byOtherBody = parseObject(byOther.body());
        assertEquals(Boolean.TRUE, byOtherBody.get("error"));
        assertTrue(String.valueOf(byOtherBody.get("message")).contains("仅当前待办处理人"));

        // 3. 拒绝路径：转给自己 / 接收人不存在
        Map<String, Object> toSelf = parseObject(call(HttpMethod.POST,
                "/api/wb/task/" + taskId + "/transfer", Map.of("targetUserId", "manager"), "manager").body());
        assertEquals(Boolean.TRUE, toSelf.get("error"));
        assertTrue(String.valueOf(toSelf.get("message")).contains("不能转办给自己"));

        Map<String, Object> toGhost = parseObject(call(HttpMethod.POST,
                "/api/wb/task/" + taskId + "/transfer", Map.of("targetUserId", "nobody-here"), "manager").body());
        assertEquals(Boolean.TRUE, toGhost.get("error"));
        assertTrue(String.valueOf(toGhost.get("message")).contains("接收人不存在"));

        // 上面三次拒绝都不能留下痕迹
        assertEquals(0, parseList(call(HttpMethod.GET, "/api/wb/task/" + taskId + "/transfers",
                null, "manager").body()).size(), "被拒绝的转办不应落记录");

        // 4. 正常转办：manager -> bob
        Map<String, Object> ok = parseObject(call(HttpMethod.POST, "/api/wb/task/" + taskId + "/transfer",
                Map.of("targetUserId", "bob", "reason", "外出培训，转交处理"), "manager").body());
        assertEquals("ok", ok.get("result"));
        assertEquals("manager", ok.get("from"));
        assertEquals("bob", ok.get("to"));

        // 4.1 引擎侧改派生效：运行中与历史任务的办理人都变成 bob
        Task transferred = taskService.createTaskQuery().taskId(taskId).singleResult();
        assertEquals("bob", transferred.getAssignee());
        HistoricTaskInstance historic = historyService.createHistoricTaskInstanceQuery()
                .taskId(taskId).singleResult();
        assertEquals("bob", historic.getAssignee(), "历史任务办理人未同步，已办列表会归属错误");

        // 4.2 待办列表：manager 看不到，bob 看得到且带转办来源
        assertNull(todoOf("manager", pid), "转办后原办理人不该还有这条待办");
        Map<String, Object> bobTodo = todoOf("bob", pid);
        assertNotNull(bobTodo, "接收人待办中没有转办来的任务");
        assertEquals(1, ((Number) bobTodo.get("transferCount")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> lastTransfer = (Map<String, Object>) bobTodo.get("lastTransfer");
        assertEquals("manager", lastTransfer.get("from"));
        assertEquals("bob", lastTransfer.get("to"));
        assertEquals("外出培训，转交处理", lastTransfer.get("reason"));

        // 4.3 待办详情：接收人看到转办链且仍可继续转办；转出方已无权打开这条待办
        Map<String, Object> bobDetail = parseObject(call(HttpMethod.GET,
                "/api/wb/task/" + taskId, null, "bob").body());
        assertEquals(Boolean.TRUE, bobDetail.get("canTransfer"), "接收人应可继续转办");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> detailChain =
                (List<Map<String, Object>>) bobDetail.get("transfers");
        assertEquals(1, detailChain.size(), "详情应返回完整转办链");
        assertEquals("manager", detailChain.get(0).get("from"));
        assertEquals("bob", detailChain.get(0).get("to"));
        assertEquals(403, call(HttpMethod.GET, "/api/wb/task/" + taskId, null, "manager").status(),
                "转办后原办理人不应再能打开待办详情");

        // 5. 转办记录可见性：参与者可查，无关人员 403
        List<Map<String, Object>> chain =
                parseList(call(HttpMethod.GET, "/api/wb/task/" + taskId + "/transfers", null, "manager").body());
        assertEquals(1, chain.size());
        assertEquals("manager", chain.get(0).get("from"));
        assertEquals(403, call(HttpMethod.GET, "/api/wb/task/" + taskId + "/transfers", null, "carol").status());

        // 6. 转出方仍能打开流程跟踪（历史办理人已被改写，靠转办记录兜底）
        assertEquals(200, call(HttpMethod.GET, "/api/wb/trace/" + pid, null, "manager").status());

        // 7. 沟通中不可转办
        Map<String, Object> comm = communicationService.startCommunicationDetailed(taskId, "bob", "请核实差旅标准",
                List.of(Map.of("type", "USER", "id", "alice", "name", "alice")));
        assertNotNull(comm.get("sessionId"));

        Map<String, Object> locked = parseObject(call(HttpMethod.POST, "/api/wb/task/" + taskId + "/transfer",
                Map.of("targetUserId", "carol"), "bob").body());
        assertEquals(Boolean.TRUE, locked.get("error"));
        assertTrue(String.valueOf(locked.get("message")).contains("正在沟通中"),
                "沟通中应拒绝转办，实际提示: " + locked.get("message"));
        assertEquals("bob", taskService.createTaskQuery().taskId(taskId).singleResult().getAssignee(),
                "被拒绝的转办不应改派");

        // 沟通中即使仍是办理人，详情里的转办入口也必须关闭（前端按钮直接读 canTransfer）
        Map<String, Object> lockedDetail = parseObject(call(HttpMethod.GET,
                "/api/wb/task/" + taskId, null, "bob").body());
        assertEquals(Boolean.FALSE, lockedDetail.get("canTransfer"), "沟通中不应给出可转办标记");

        // 8. 沟通待办本身不可转办（改派会让沟通记录与办理人错位）
        Task commTask = taskService.createTaskQuery().taskAssignee("alice")
                .processInstanceId(pid).list().stream()
                .filter(t -> t.getTaskDefinitionKey().endsWith("#COMM"))
                .findFirst().orElse(null);
        assertNotNull(commTask, "沟通任务未生成");
        Map<String, Object> commRejected = parseObject(call(HttpMethod.POST,
                "/api/wb/task/" + commTask.getId() + "/transfer",
                Map.of("targetUserId", "carol"), "alice").body());
        assertEquals(Boolean.TRUE, commRejected.get("error"));
        assertTrue(String.valueOf(commRejected.get("message")).contains("沟通待办不支持转办"));

        // 9. 收回沟通后恢复可转办，验证锁定不是永久性的
        communicationService.withdrawCommunication((String) comm.get("sessionId"), "bob", null);
        Map<String, Object> afterWithdraw = parseObject(call(HttpMethod.POST,
                "/api/wb/task/" + taskId + "/transfer",
                Map.of("targetUserId", "carol"), "bob").body());
        assertEquals("ok", afterWithdraw.get("result"), "收回沟通后应可转办: " + afterWithdraw);
        assertEquals("carol", taskService.createTaskQuery().taskId(taskId).singleResult().getAssignee());

        // 10. 已办列表同样要带转办次数：carol 是最后经手人，办完后列表行应显示这条任务转过 2 次
        taskService.complete(taskId);
        Map<String, Object> carolDone = parseObject(call(HttpMethod.GET, "/api/wb/done", null, "carol").body());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> doneList = (List<Map<String, Object>>) carolDone.get("list");
        Map<String, Object> doneRow = doneList.stream()
                .filter(item -> taskId.equals(item.get("taskId")))
                .findFirst().orElse(null);
        assertNotNull(doneRow, "接收人办完后已办列表里应有这条任务");
        assertEquals(2, ((Number) doneRow.get("transferCount")).intValue(),
                "已办列表未返回转办次数，前端标签不会显示");
        @SuppressWarnings("unchecked")
        Map<String, Object> doneLast = (Map<String, Object>) doneRow.get("lastTransfer");
        assertEquals("bob", doneLast.get("from"));
        assertEquals("carol", doneLast.get("to"));
    }
}
