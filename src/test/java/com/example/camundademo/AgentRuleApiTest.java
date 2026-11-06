package com.example.camundademo;

import com.example.camundademo.security.DemoUserInitializer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.identity.Group;
import org.camunda.bpm.engine.identity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代理规则接口级测试：时间段重叠与「管理员只能停用、不能替他人启用」。
 *
 * <p><b>为什么走 HTTP 而不是直接调 {@code AgentRuleService}</b>：
 * 这两条约束的真正入口在控制器层——{@code owner} 必须取自认证上下文，
 * 管理员能力取决于 Camunda Identity 里的组。{@code DelegateAgentTest} 直接注入服务，
 * 用的是方法入参里的 operator，绕过了“operator 是否可被客户端伪造”这条链路。
 * 本类用 Basic 认证从接口打进去，才能钉住这两点。</p>
 *
 * <p><b>关于业务错误的状态码</b>：{@code AgentRuleController#handleBusiness} 把
 * {@code BusinessException} 统一转成 200 + {@code {"error": true, "message": ...}}，
 * 因此断言要同时看状态码与 {@code error} 字段，不能只看非 2xx。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@org.springframework.test.context.TestPropertySource(properties = {
        // 独立 H2 库：规则的重叠判定依赖“同一个人已有哪些规则”，
        // 与其他测试类共用内存库会让断言取决于执行顺序。
        "spring.datasource.url=jdbc:h2:mem:agentruledb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class AgentRuleApiTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 3_600_000L;

    @LocalServerPort
    private int port;

    @Autowired
    private IdentityService identityService;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * 每个请求独立创建客户端：TestRestTemplate 会按 host 缓存 Basic 凭据，
     * 在多用户场景下会串号（同一 host 换人请求仍带旧凭据）。
     */
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @BeforeEach
    void ensureIdentity() {
        for (String user : List.of("manager", "alice", "bob", "carol", "frank", "erin", "admin")) {
            ensureUser(user);
        }
        // 管理员能力来自组，不能假设初始化顺序
        ensureMembership("admin", "admin");
        ensureMembership("admin", "camunda-admin");
    }

    private void ensureUser(String userId) {
        User user = identityService.createUserQuery().userId(userId).singleResult();
        if (user == null) {
            user = identityService.newUser(userId);
            user.setFirstName(userId);
            user.setLastName("(demo)");
        }
        user.setPassword(DemoUserInitializer.DEMO_PASSWORD);
        identityService.saveUser(user);
    }

    private void ensureMembership(String userId, String groupId) {
        if (identityService.createGroupQuery().groupId(groupId).count() == 0) {
            Group group = identityService.newGroup(groupId);
            group.setName(groupId);
            group.setType("WORKFLOW");
            identityService.saveGroup(group);
        }
        if (identityService.createGroupQuery().groupMember(userId).groupId(groupId).count() == 0) {
            identityService.createMembership(userId, groupId);
        }
    }

    // ===== HTTP 辅助 =====

    private record ApiResponse(int status, String body) {
    }

    private ApiResponse call(HttpMethod method, String path, Object body, String user) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (user != null) {
            String raw = user + ":" + DemoUserInitializer.DEMO_PASSWORD;
            builder.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
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

    private Map<String, Object> parse(String body) {
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

    /** 成功调用：2xx 且不带 error 标记 */
    private Map<String, Object> postOk(String path, Object body, String user) {
        ApiResponse resp = call(HttpMethod.POST, path, body, user);
        assertTrue(resp.status() / 100 == 2,
                "POST " + path + " 期望成功，实际 " + resp.status() + " " + resp.body());
        Map<String, Object> parsed = parse(resp.body());
        assertNull(parsed.get("error"), "POST " + path + " 返回了业务错误: " + resp.body());
        return parsed;
    }

    private List<Map<String, Object>> getListOk(String path, String user) {
        ApiResponse resp = call(HttpMethod.GET, path, null, user);
        assertTrue(resp.status() / 100 == 2,
                "GET " + path + " 期望成功，实际 " + resp.status() + " " + resp.body());
        return parseList(resp.body());
    }

    /** 业务拒绝：约定为 200 + error=true + message */
    private String postBizError(String path, Object body, String user) {
        return bizError(call(HttpMethod.POST, path, body, user), "POST " + path);
    }

    private String getBizError(String path, String user) {
        return bizError(call(HttpMethod.GET, path, null, user), "GET " + path);
    }

    private String bizError(ApiResponse resp, String what) {
        assertEquals(200, resp.status(), what + " 业务拒绝应返回 200，实际 " + resp.status() + " " + resp.body());
        Map<String, Object> parsed = parse(resp.body());
        assertEquals(Boolean.TRUE, parsed.get("error"), what + " 期望被拒绝，实际返回: " + resp.body());
        String message = String.valueOf(parsed.get("message"));
        assertNotNull(message, what + " 拒绝时缺少 message");
        return message;
    }

    private Map<String, Object> rule(String agentUserId, long start, long end, String keys) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentUserId", agentUserId);
        body.put("startTime", start);
        body.put("endTime", end);
        body.put("processDefinitionKeys", keys);
        return body;
    }

    private Map<String, Object> rule(String id, String agentUserId, long start, long end, String keys) {
        Map<String, Object> body = rule(agentUserId, start, end, keys);
        body.put("id", id);
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> savedRule(Map<String, Object> resp) {
        Map<String, Object> rule = (Map<String, Object>) resp.get("rule");
        assertNotNull(rule, "保存规则未返回 rule 对象: " + resp);
        return rule;
    }

    private Map<String, Object> findByOwner(List<Map<String, Object>> rules, String owner, String agent) {
        for (Map<String, Object> item : rules) {
            if (owner.equals(item.get("owner")) && agent.equals(item.get("agentUser"))) {
                return item;
            }
        }
        return null;
    }

    // ===== 用例 1：时间段重叠 =====

    @Test
    @Order(1)
    void overlapRejectsIntersectionAndContainmentButAllowsAdjacent() {
        long base = System.currentTimeMillis();

        // 基准规则：base-1h ~ base+1h
        Map<String, Object> baseRule = savedRule(postOk("/api/agent/rules",
                rule("bob", base - HOUR, base + HOUR, "approval"), "manager"));
        assertEquals("ACTIVE", baseRule.get("state"));
        assertEquals(1, baseRule.get("enabled"));

        // 部分交叉：起点落在基准规则内部
        assertTrue(postBizError("/api/agent/rules",
                rule("carol", base + 30 * MINUTE, base + 2 * HOUR, null), "manager").contains("重叠"),
                "部分交叉必须被拒绝");

        // 完全包含：新规则把基准规则整段包住
        assertTrue(postBizError("/api/agent/rules",
                rule("carol", base - 2 * HOUR, base + 2 * HOUR, null), "manager").contains("重叠"),
                "完全包含必须被拒绝");

        // 被包含：新规则落在基准规则内部（半开区间判定容易漏掉的方向）
        assertTrue(postBizError("/api/agent/rules",
                rule("carol", base - 30 * MINUTE, base + 30 * MINUTE, null), "manager").contains("重叠"),
                "被包含必须被拒绝");

        // 相邻不算重叠：首尾相接时区间交集为空，应允许
        Map<String, Object> after = savedRule(postOk("/api/agent/rules",
                rule("carol", base + HOUR, base + 3 * HOUR, null), "manager"));
        assertEquals("UPCOMING", after.get("state"), "起点在未来的规则应为 UPCOMING");

        Map<String, Object> before = savedRule(postOk("/api/agent/rules",
                rule("dave", base - 3 * HOUR, base - HOUR, null), "manager"));
        assertEquals("EXPIRED", before.get("state"), "终点在过去的规则应为 EXPIRED");

        // 库内应恰好留下这三条（两次被拒的没有落库）
        List<Map<String, Object>> mine = getListOk("/api/agent/rules", "manager");
        assertEquals(3, mine.size(), "被拒绝的规则不应落库: " + mine);
    }

    // ===== 用例 2：停用规则不参与重叠判定 =====

    @Test
    @Order(2)
    void disabledRuleDoesNotBlockSamePeriod() {
        long base = System.currentTimeMillis();

        Map<String, Object> first = savedRule(postOk("/api/agent/rules",
                rule("bob", base - HOUR, base + HOUR, null), "alice"));
        String firstId = String.valueOf(first.get("id"));

        // 启用状态下的同段规则先确认会被拒，证明下面的放行不是因为“判定坏了”
        assertTrue(postBizError("/api/agent/rules",
                rule("carol", base - HOUR, base + HOUR, null), "alice").contains("重叠"));

        postOk("/api/agent/rules/" + firstId + "/disable", null, "alice");

        Map<String, Object> second = savedRule(postOk("/api/agent/rules",
                rule("carol", base - HOUR, base + HOUR, null), "alice"));
        assertEquals("ACTIVE", second.get("state"));

        // 新规则生效后，再建同段仍然要被拒——说明重叠判定只忽略停用规则，不是整体失效
        assertTrue(postBizError("/api/agent/rules",
                rule("bob", base - HOUR, base + HOUR, null), "alice").contains("重叠"));

        List<Map<String, Object>> mine = getListOk("/api/agent/rules", "alice");
        assertEquals(2, mine.size(), "alice 应只有两条规则: " + mine);
        assertEquals("DISABLED", findByOwner(mine, "alice", "bob").get("state"));
        assertEquals("ACTIVE", findByOwner(mine, "alice", "carol").get("state"));
    }

    // ===== 用例 3：管理员只能停用，不能替他人启用 =====

    @Test
    @Order(3)
    void adminCanDisableButNeverEnableOthersRule() {
        long base = System.currentTimeMillis();

        Map<String, Object> created = savedRule(postOk("/api/agent/rules",
                rule("bob", base - HOUR, base + HOUR, "approval"), "frank"));
        String ruleId = String.valueOf(created.get("id"));

        // 非本人且非管理员：连停用都不行
        assertTrue(postBizError("/api/agent/rules/" + ruleId + "/disable", null, "bob").contains("本人或管理员"));
        assertTrue(postBizError("/api/agent/rules/" + ruleId + "/enable", null, "bob").contains("本人或管理员"));

        // 管理员停用：允许
        Map<String, Object> disabled = savedRule(postOk("/api/agent/rules/" + ruleId + "/disable", null, "admin"));
        assertEquals(0, disabled.get("enabled"));
        assertEquals("DISABLED", disabled.get("state"));
        assertEquals("frank", disabled.get("owner"), "停用不得改变规则归属");

        // 管理员启用：拒绝
        assertTrue(postBizError("/api/agent/rules/" + ruleId + "/enable", null, "admin").contains("不能替他人启用"),
                "管理员启用他人规则必须被拒绝");

        // 管理员修改内容：同样拒绝，避免“停用不成改用编辑”
        assertTrue(postBizError("/api/agent/rules",
                rule(ruleId, "carol", base - HOUR, base + HOUR, null), "admin").contains("只能修改自己的代理规则"));

        // 本人启用：允许
        Map<String, Object> enabled = savedRule(postOk("/api/agent/rules/" + ruleId + "/enable", null, "frank"));
        assertEquals(1, enabled.get("enabled"));
        assertEquals("ACTIVE", enabled.get("state"));
    }

    // ===== 用例 4：列表可见范围 =====

    @Test
    @Order(4)
    void listScopeSeparatesOwnerFromAdmin() {
        // 本人列表只有自己的：manager 在这个库里只有用例 1 建的三条
        List<Map<String, Object>> mine = getListOk("/api/agent/rules", "manager");
        assertTrue(mine.size() >= 3, "manager 本人规则缺失: " + mine);
        for (Map<String, Object> item : mine) {
            assertEquals("manager", item.get("owner"), "本人列表不应出现他人规则: " + item);
        }

        // 非管理员看全部：拒绝
        assertTrue(getBizError("/api/agent/rules/all", "bob").contains("仅管理员"));

        // 管理员看全部：能覆盖多个 owner，且能看到 frank 那条
        List<Map<String, Object>> all = getListOk("/api/agent/rules/all", "admin");
        assertTrue(all.stream().anyMatch(r -> "frank".equals(r.get("owner"))), "管理员视图缺少 frank 的规则: " + all);
        long owners = all.stream().map(r -> String.valueOf(r.get("owner"))).distinct().count();
        assertTrue(owners >= 2, "管理员视图应跨用户: " + all);
    }

    // ===== 用例 5：入参校验 =====

    @Test
    @Order(5)
    void validationGuards() {
        long base = System.currentTimeMillis();

        // 未认证
        ApiResponse anonymous = call(HttpMethod.GET, "/api/agent/rules", null, null);
        assertEquals(401, anonymous.status(), "未认证访问 /api/agent/rules 必须 401: " + anonymous.body());

        assertTrue(postBizError("/api/agent/rules", rule("erin", base, base + HOUR, null), "erin").contains("不能代理给自己"));
        assertTrue(postBizError("/api/agent/rules", rule("nobody", base, base + HOUR, null), "erin").contains("代理人不存在"));
        assertTrue(postBizError("/api/agent/rules", rule("bob", base + HOUR, base, null), "erin").contains("开始时间必须早于结束时间"));
        assertTrue(postBizError("/api/agent/rules", rule("bob", base, base, null), "erin").contains("开始时间必须早于结束时间"));

        Map<String, Object> missingTime = new LinkedHashMap<>();
        missingTime.put("agentUserId", "bob");
        assertTrue(postBizError("/api/agent/rules", missingTime, "erin").contains("请填写代理开始与结束时间"));

        Map<String, Object> badTime = rule("bob", base, base + HOUR, null);
        badTime.put("startTime", "2026-13-99 10:00");
        assertTrue(postBizError("/api/agent/rules", badTime, "erin").contains("时间格式不正确"));

        assertTrue(postBizError("/api/agent/rules",
                rule("not-exists", "bob", base, base + HOUR, null), "erin").contains("代理规则不存在"));

        // 修改自己的规则：id 不变、owner 不变
        Map<String, Object> created = savedRule(postOk("/api/agent/rules",
                rule("bob", base - HOUR, base + HOUR, "approval"), "erin"));
        String id = String.valueOf(created.get("id"));

        Map<String, Object> updated = savedRule(postOk("/api/agent/rules",
                rule(id, "carol", base - 2 * HOUR, base + 2 * HOUR, "approval,leave"), "erin"));
        assertEquals(id, updated.get("id"));
        assertEquals("erin", updated.get("owner"));
        assertEquals("carol", updated.get("agentUser"));
        assertEquals("approval,leave", updated.get("processDefinitionKeys"), "流程范围应保留原始顺序并去空白");

        // 编辑不应产生新规则
        List<Map<String, Object>> mine = getListOk("/api/agent/rules", "erin");
        assertEquals(1, mine.size(), "编辑不应新增规则: " + mine);
    }
}
