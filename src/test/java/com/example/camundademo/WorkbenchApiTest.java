package com.example.camundademo;

import com.example.camundademo.security.DemoUserInitializer;
import com.example.camundademo.support.TestSession;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待办中心接口端到端测试：待办 / 详情 / 沟通待办 / 已办 / 我的发起 / 未认证 401。
 *
 * <p><b>关于 HTTP 客户端的选择</b>：本类刻意不使用注入的 {@code TestRestTemplate}。
 * 该类会在整个测试 JVM 内共享同一个底层客户端实例，而 Basic 认证凭据由客户端按 host
 * 缓存；同 JVM 内的其他测试类（如 {@code CommunicationFlowTest}）一旦用错误密码请求过
 * 同一 host，缓存就会被污染，后续即使携带正确凭据也会收到 401。
 * 这会让本用例的成败取决于测试执行顺序，而不是被测代码。
 * 因此这里改用 JDK 自带的 {@link HttpClient}：它不维护认证缓存，每个请求携带的
 * {@code Authorization} 头相互独立，认证行为只由服务端决定。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.TestPropertySource(properties = {
        // 独立 H2 库：与 CommunicationFlowTest 共用同一个内存库时，身份数据会在
        // 多个 Spring 上下文之间相互污染（实测 manager 的密码会变成非 demo），
        // 导致本用例的成败取决于测试执行顺序。接口测试应自带干净的数据环境。
        "spring.datasource.url=jdbc:h2:mem:workbenchdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class WorkbenchApiTest {

    @LocalServerPort
    private int port;

    @org.springframework.beans.factory.annotation.Autowired
    private org.camunda.bpm.engine.IdentityService identityService;

    /**
     * 确保演示账号存在且密码是 demo。
     *
     * <p>测试进程内可能启动多个 Spring 上下文，它们共享同一个 H2 内存库
     * （{@code jdbc:h2:mem:camundadb;DB_CLOSE_DELAY=-1}），而 {@code DemoUserInitializer}
     * 对已存在的用户直接跳过，不校正密码。因此不能假设“别人的初始化一定让 manager 的密码是 demo”，
     * 否则本用例的成败会取决于测试执行顺序。这里显式把用到的账号拉回已知状态。</p>
     */
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

    private final ObjectMapper json = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 最小响应包装：保留原始状态码与响应体，失败时能报出真实原因。 */
    private record ApiResponse(int status, String body) {
        boolean is2xx() {
            return status >= 200 && status < 300;
        }

        boolean isError() {
            return status >= 400;
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
        if (body == null) {
            builder.method(method.name(), HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json;charset=UTF-8");
            try {
                builder.method(method.name(), HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(body), StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException("请求体序列化失败: " + body, e);
            }
        }
        try {
            HttpResponse<String> resp = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new ApiResponse(resp.statusCode(), resp.body());
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + path + " 请求失败", e);
        }
    }

    private Map<String, Object> get(String path, String user) {
        ApiResponse resp = call(HttpMethod.GET, path, null, user);
        assertTrue(resp.is2xx(), "GET " + path + " 失败: " + resp.status() + " " + resp.body());
        return parse(resp.body());
    }

    private Map<String, Object> post(String path, Object body, String user) {
        ApiResponse resp = call(HttpMethod.POST, path, body, user);
        assertTrue(resp.is2xx(), "POST " + path + " 失败: " + resp.status() + " " + resp.body());
        return parse(resp.body());
    }

    private Map<String, Object> parse(String body) {
        try {
            return json.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("响应不是 JSON 对象: " + body, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOf(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("list");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> statsOf(Map<String, Object> page) {
        return (Map<String, Object>) page.get("stats");
    }

    private Map<String, Object> findInPage(Map<String, Object> page, String pid) {
        for (Map<String, Object> item : listOf(page)) {
            if (pid.equals(item.get("processInstanceId"))) {
                return item;
            }
        }
        return null;
    }

    private Map<String, Object> nodeById(List<Map<String, Object>> nodes, String nodeId) {
        for (Map<String, Object> node : nodes) {
            if (nodeId.equals(node.get("nodeId"))) {
                return node;
            }
        }
        return null;
    }

    private boolean edgeTaken(List<Map<String, Object>> edges, String edgeId) {
        for (Map<String, Object> edge : edges) {
            if (edgeId.equals(edge.get("id"))) {
                return Boolean.TRUE.equals(edge.get("taken"));
            }
        }
        return false;
    }

    @Test
    void workbenchEndToEnd() {
        // 0. 测试自建账号状态，避免依赖其他测试类遗留的身份数据
        ensureDemoUser("applicant");
        ensureDemoUser("manager");
        ensureDemoUser("alice");

        // 1. 申请人发起流程
        Map<String, Object> started = post("/api/comm/demo/start", null, "applicant");
        String pid = (String) started.get("processInstanceId");
        assertNotNull(pid);

        // 2. 申请人待办里能看到 apply，且统计字段存在
        Map<String, Object> applicantTodo = get("/api/wb/todo?type=all&page=1&size=50", "applicant");
        assertNotNull(statsOf(applicantTodo));
        Map<String, Object> applyTask = findInPage(applicantTodo, pid);
        assertNotNull(applyTask, "申请人待办中未找到新流程: " + listOf(applicantTodo));
        assertEquals("apply", applyTask.get("taskDefKey"));
        assertEquals(false, applyTask.get("isCommTask"));

        // 3. 待办详情接口
        String applyTaskId = (String) applyTask.get("taskId");
        Map<String, Object> detail = get("/api/wb/task/" + applyTaskId, "applicant");
        assertEquals("apply", detail.get("taskDefKey"));
        assertEquals("applicant", detail.get("applicant"));
        assertNotNull(detail.get("commStatus"));

        // 4. 他人不能查看该待办：认证身份合法但无权限，应为 403
        ApiResponse forbidden = call(HttpMethod.GET, "/api/wb/task/" + applyTaskId, null, "manager");
        assertEquals(403, forbidden.status(),
                "他人查看待办应返回 403，实际: " + forbidden.status() + " " + forbidden.body());

        // 5. 提交申请（带审批意见）→ 流转到经理
        post("/api/comm/demo/complete/" + applyTaskId,
                new HashMap<>(Map.of("comment", "资料已备齐")), "applicant");

        // 6. 经理待办里能看到审批任务
        Map<String, Object> managerTodo = get("/api/wb/todo?type=approval&page=1&size=50", "manager");
        Map<String, Object> mgrTask = findInPage(managerTodo, pid);
        assertNotNull(mgrTask, "经理待办中未找到新流程");
        String mgrTaskId = (String) mgrTask.get("taskId");

        // 7. 经理向 alice 发起沟通
        Map<String, Object> target = new HashMap<>();
        target.put("type", "USER");
        target.put("id", "alice");
        target.put("name", "alice");
        Map<String, Object> commReq = new HashMap<>();
        commReq.put("taskId", mgrTaskId);
        commReq.put("content", "请核实合同金额");
        commReq.put("targets", List.of(target));
        post("/api/comm/start", commReq, "manager");

        // 8. 经理待办状态变沟通中，且不可审批
        Map<String, Object> mgrDetail = get("/api/wb/task/" + mgrTaskId, "manager");
        Map<?, ?> commStatus = (Map<?, ?>) mgrDetail.get("commStatus");
        assertEquals("COMMUNICATING", commStatus.get("bizStatus"));
        assertEquals(false, commStatus.get("canApprove"));

        // 9. alice 的沟通待办：type=comm 能查到，且带来源节点
        Map<String, Object> aliceTodo = get("/api/wb/todo?type=comm&page=1&size=50", "alice");
        Map<String, Object> commTask = findInPage(aliceTodo, pid);
        assertNotNull(commTask, "alice 沟通待办缺失: " + listOf(aliceTodo));
        assertEquals(true, commTask.get("isCommTask"));
        assertEquals(false, commTask.get("isFollowUp"), "首轮沟通不应被标记为追问");
        assertEquals("manager_review", commTask.get("sourceTaskDefKey"));
        assertEquals("PENDING", commTask.get("myCommStatus"));

        // 9.1 流程跟踪：节点状态、几何、沟通任务归属都要能直接驱动前端高亮与悬停信息
        Map<String, Object> trace = get("/api/wb/trace/" + pid, "manager");
        assertEquals("approval", trace.get("processDefinitionKey"));
        assertEquals("RUNNING", trace.get("state"));
        assertEquals(true, trace.get("diagramAvailable"), "BPMN DI 应解析成功");
        assertTrue(((Number) trace.get("diagramWidth")).doubleValue() > 0);

        List<Map<String, Object>> traceNodes = (List<Map<String, Object>>) trace.get("nodes");
        assertEquals(5, traceNodes.size(), "approval 流程应有 5 个可渲染节点: " + traceNodes);

        Map<String, Object> applyNode = nodeById(traceNodes, "apply");
        assertNotNull(applyNode, "跟踪图中缺少 apply 节点");
        assertEquals("发起", applyNode.get("name"));
        assertEquals("COMPLETED", applyNode.get("status"));
        assertEquals("userTask", applyNode.get("type"));
        assertEquals("用户任务", applyNode.get("typeLabel"));
        assertNotNull(applyNode.get("startTime"), "已完成节点应带到达时间");
        assertNotNull(applyNode.get("endTime"), "已完成节点应带完成时间");
        assertTrue(((List<?>) applyNode.get("comments")).size() > 0, "apply 节点应带审批意见");

        Map<String, Object> mgrNode = nodeById(traceNodes, "manager_review");
        assertNotNull(mgrNode, "跟踪图中缺少 manager_review 节点");
        assertEquals("ACTIVE", mgrNode.get("status"), "经理节点应处于执行中");
        assertEquals(List.of("manager"), mgrNode.get("assignees"), "悬停信息应显示处理人");
        assertTrue(((Number) mgrNode.get("x")).doubleValue() > 0, "节点应带 BPMN 坐标");
        assertTrue(((Number) mgrNode.get("width")).doubleValue() > 0);
        assertEquals(1, ((Number) mgrNode.get("pendingCommCount")).intValue(),
                "经理节点应有 1 条待反馈沟通，实际 commTasks=" + mgrNode.get("commTasks"));
        assertEquals(true, mgrNode.get("commLocked"), "存在待反馈沟通时节点应标记沟通中");
        List<Map<String, Object>> nodeCommTasks = (List<Map<String, Object>>) mgrNode.get("commTasks");
        assertEquals(1, nodeCommTasks.size(), "沟通任务应挂在来源节点 manager_review 上");
        assertEquals(false, nodeCommTasks.get(0).get("isFollowUp"));
        assertEquals("alice", nodeCommTasks.get(0).get("assignee"));
        // 沟通明细：前端需要对象名称、沟通内容、发起时间与会话归属才能分组展示
        assertEquals("manager", nodeCommTasks.get(0).get("initiator"), "沟通发起人应为源待办处理人");
        assertEquals("alice", nodeCommTasks.get(0).get("targetName"));
        assertEquals("USER", nodeCommTasks.get(0).get("targetType"));
        assertEquals("请核实合同金额", nodeCommTasks.get(0).get("content"));
        assertNotNull(nodeCommTasks.get(0).get("createTime"), "沟通明细应带发起时间");
        assertNotNull(nodeCommTasks.get(0).get("rootTaskId"), "沟通明细应带会话 rootTaskId");

        Map<String, Object> financeNode = nodeById(traceNodes, "finance_review");
        assertNotNull(financeNode);
        assertEquals("PENDING", financeNode.get("status"), "财务节点尚未到达");

        List<Map<String, Object>> traceEdges = (List<Map<String, Object>>) trace.get("edges");
        assertEquals(true, edgeTaken(traceEdges, "flow2"), "apply → manager_review 应已走过");
        assertEquals(false, edgeTaken(traceEdges, "flow3"), "manager_review → finance_review 尚未走过");
        for (Map<String, Object> e : traceEdges) {
            assertTrue(((List<?>) e.get("waypoints")).size() >= 2, "连线应带路径点: " + e);
        }

        Map<?, ?> traceStats = (Map<?, ?>) trace.get("stats");
        assertEquals(2, ((Number) traceStats.get("completed")).intValue(), "start 与 apply 已完成");
        assertEquals(1, ((Number) traceStats.get("active")).intValue());
        assertEquals(2, ((Number) traceStats.get("pending")).intValue());
        assertEquals(1, ((Number) traceStats.get("commPending")).intValue());

        // 参与过该实例的沟通反馈人也能查看；无关人员一律 403
        ApiResponse aliceTrace = call(HttpMethod.GET, "/api/wb/trace/" + pid, null, "alice");
        assertEquals(200, aliceTrace.status(), "沟通反馈人应能查看跟踪: " + aliceTrace.body());
        ApiResponse outsiderTrace = call(HttpMethod.GET, "/api/wb/trace/" + pid, null, "finance");
        assertEquals(403, outsiderTrace.status(),
                "未参与该流程的用户应返回 403，实际: " + outsiderTrace.status());

        // 10. alice 反馈 → 沟通待办从待办列表消失、进入已办
        String commRecordId = (String) commTask.get("commRecordId");
        Map<String, Object> reply = new HashMap<>();
        reply.put("commTaskRecordId", commRecordId);
        reply.put("content", "金额无误");
        post("/api/comm/reply", reply, "alice");

        Map<String, Object> aliceTodoAfter = get("/api/wb/todo?type=all&page=1&size=50", "alice");
        assertEquals(null, findInPage(aliceTodoAfter, pid), "反馈后沟通待办应消失");

        // 10.1 沟通反馈后，跟踪图上的待反馈数应实时归零，沟通记录仍保留在源节点
        Map<String, Object> traceAfterReply = get("/api/wb/trace/" + pid, "manager");
        Map<String, Object> mgrAfterReply = nodeById(
                (List<Map<String, Object>>) traceAfterReply.get("nodes"), "manager_review");
        assertNotNull(mgrAfterReply);
        assertEquals(0, ((Number) mgrAfterReply.get("pendingCommCount")).intValue(),
                "反馈后不应再有待反馈沟通");
        assertEquals(false, mgrAfterReply.get("commLocked"));
        List<Map<String, Object>> commTasksAfterReply =
                (List<Map<String, Object>>) mgrAfterReply.get("commTasks");
        assertEquals(1, commTasksAfterReply.size(), "沟通记录不应从跟踪图消失");
        assertEquals("REPLIED", commTasksAfterReply.get(0).get("status"));
        // 反馈后明细要带回复内容、反馈人与反馈时间，供展开区展示
        assertEquals("金额无误", commTasksAfterReply.get(0).get("replyContent"));
        assertEquals("alice", commTasksAfterReply.get(0).get("replyUserId"));
        assertNotNull(commTasksAfterReply.get(0).get("replyTime"), "已反馈沟通应带回复时间");

        Map<String, Object> aliceDone = get("/api/wb/done?page=1&size=50", "alice");
        Map<String, Object> doneItem = findInPage(aliceDone, pid);
        assertNotNull(doneItem, "alice 已办中未找到沟通任务");
        assertEquals(true, doneItem.get("isCommTask"));
        assertEquals(false, doneItem.get("isFollowUp"), "已办中的首轮沟通不应被标记为追问");
        // 反馈是"办结"而非"删除"：历史任务不应带 deleteReason（否则已办显示为已收回/删除）
        assertEquals(null, doneItem.get("deleteReason"), "沟通反馈应办结任务，不应产生删除原因");
        // 反馈内容必须落在审批意见（ACT_HI_COMMENT）上，且按当前 taskId 可回读，
        // 这样已办列表、已办详情、流程图三处取自同一数据源，显示才一致
        assertEquals("金额无误", doneItem.get("comment"), "反馈内容应写入审批意见");

        // 10.1 经理追问 alice：待办列表与沟通状态都要能直接区分“追问”
        Map<String, Object> followTarget = new HashMap<>();
        followTarget.put("type", "USER");
        followTarget.put("id", "alice");
        followTarget.put("name", "alice");
        Map<String, Object> continueReq = new HashMap<>();
        continueReq.put("parentCommTaskRecordId", commRecordId);
        continueReq.put("content", "请补充合同附件");
        continueReq.put("targets", List.of(followTarget));
        ApiResponse continueResp = call(HttpMethod.POST, "/api/comm/continue", continueReq, "manager");
        assertEquals(200, continueResp.status(), "追问失败: " + continueResp.body());

        Map<String, Object> aliceFollowUpTodo = get("/api/wb/todo?type=comm&page=1&size=50", "alice");
        Map<String, Object> followUpTask = findInPage(aliceFollowUpTodo, pid);
        assertNotNull(followUpTask, "追问待办应出现在 alice 待办: " + listOf(aliceFollowUpTodo));
        assertEquals(true, followUpTask.get("isCommTask"));
        assertEquals(true, followUpTask.get("isFollowUp"), "追问任务应标记 isFollowUp=true");

        // 10.2 追问独立筛选：type=followup 只返回追问，审批与首轮沟通均被排除
        Map<String, Object> onlyFollowUp = get("/api/wb/todo?type=followup&page=1&size=50", "alice");
        assertEquals(1, ((Number) onlyFollowUp.get("total")).intValue(),
                "type=followup 应只返回 1 条追问: " + listOf(onlyFollowUp));
        Map<String, Object> onlyFollowUpItem = findInPage(onlyFollowUp, pid);
        assertNotNull(onlyFollowUpItem, "type=followup 中未找到追问: " + listOf(onlyFollowUp));
        assertEquals(true, onlyFollowUpItem.get("isFollowUp"));
        assertEquals(true, onlyFollowUpItem.get("isCommTask"));

        Map<?, ?> followUpStats = (Map<?, ?>) onlyFollowUp.get("stats");
        assertNotNull(followUpStats, "待办响应应带 stats");
        assertEquals(1, ((Number) followUpStats.get("followup")).intValue(), "stats.followup 统计不正确");
        assertEquals(1, ((Number) followUpStats.get("comm")).intValue(), "comm 应包含追问任务");

        Map<String, Object> aliceApprovalOnly = get("/api/wb/todo?type=approval&page=1&size=50", "alice");
        assertEquals(0, ((Number) aliceApprovalOnly.get("total")).intValue(),
                "alice 无审批待办，type=approval 应为空: " + listOf(aliceApprovalOnly));

        Map<String, Object> mgrStatus = get("/api/comm/status/" + mgrTaskId, "manager");
        List<?> statusCommTasks = (List<?>) mgrStatus.get("commTasks");
        assertNotNull(statusCommTasks);
        boolean foundFollowUp = false;
        for (Object o : statusCommTasks) {
            Map<?, ?> item = (Map<?, ?>) o;
            if (commRecordId.equals(item.get("parentCommTaskId"))) {
                assertEquals(true, item.get("isFollowUp"), "沟通状态中的追问任务应标记 isFollowUp=true");
                foundFollowUp = true;
            }
        }
        assertTrue(foundFollowUp, "沟通状态中未找到追问记录: " + statusCommTasks);

        // 11. 我的发起
        Map<String, Object> applicantStarted = get("/api/wb/started?page=1&size=50", "applicant");
        Map<String, Object> startedItem = findInPage(applicantStarted, pid);
        assertNotNull(startedItem, "我的发起中未找到流程");
        assertEquals("RUNNING", startedItem.get("state"));

        // 12. 未认证访问一律 401
        ApiResponse unauth = call(HttpMethod.GET, "/api/wb/todo", null, null);
        assertEquals(401, unauth.status(), "未认证应返回 401，实际: " + unauth.status());
    }
}
