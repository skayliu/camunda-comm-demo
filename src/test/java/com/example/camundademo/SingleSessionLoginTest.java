package com.example.camundademo;

import com.example.camundademo.security.DemoUserInitializer;
import org.camunda.bpm.engine.IdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单账号单会话（新登录顶掉旧会话）的端到端测试。
 *
 * <p>要钉住的是三件事：</p>
 * <ol>
 *   <li>同一账号第二次登录后，第一次拿到的 JSESSIONID 立刻失效，且返回的是
 *       “已在其它设备登录”而不是泛化的“未认证”；</li>
 *   <li>不同账号互不影响——一个人换设备登录，不应该把别人踢下线；</li>
 *   <li>退出登录会释放该账号的会话名额，旧 Cookie 不再可用。</li>
 * </ol>
 *
 * <p>这里不复用 {@code TestSession}：它按「端口 + 用户」缓存 Cookie，同一用户只会登录一次，
 * 而本用例恰恰需要同一用户的两个并发会话。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // 独立 H2 库，避免与其它测试类互相污染身份数据
        "spring.datasource.url=jdbc:h2:mem:singlesessiondb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class SingleSessionLoginTest {

    @LocalServerPort
    private int port;

    @Autowired
    private IdentityService identityService;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private record Resp(int status, String body, String cookie) {
    }

    private record Csrf(String cookie, String token) {
    }

    /** 取一份新的 CSRF 令牌；服务端同时把令牌写进 XSRF-TOKEN cookie。 */
    private Csrf csrf() {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/auth/csrf"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build());
        assertEquals(200, response.statusCode(), "取 CSRF 令牌应当成功: " + response.body());
        String token = setCookieValue(response, "XSRF-TOKEN");
        assertNotNull(token, "CSRF 接口应当下发 XSRF-TOKEN: " + response.headers().map());
        return new Csrf("XSRF-TOKEN=" + token, token);
    }

    /** 登录并返回可直接放进 Cookie 请求头的 JSESSIONID + XSRF-TOKEN。 */
    private String login(String userId) {
        Csrf csrf = csrf();
        String json = "{\"userId\":\"" + userId + "\",\"password\":\""
                + DemoUserInitializer.DEMO_PASSWORD + "\"}";
        HttpRequest request = HttpRequest.newBuilder(uri("/api/auth/login"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json;charset=UTF-8")
                // 登录本身就受 CSRF 保护：头与 cookie 必须成对出现
                .header("Cookie", csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        assertEquals(200, response.statusCode(), "登录应当成功: " + response.body());
        String sessionId = setCookieValue(response, "JSESSIONID");
        assertNotNull(sessionId, "登录成功但响应没有 JSESSIONID: " + response.headers().map());
        String refreshed = setCookieValue(response, "XSRF-TOKEN");
        return "JSESSIONID=" + sessionId + "; XSRF-TOKEN="
                + (refreshed == null ? csrf.token() : refreshed);
    }

    /** 从 Set-Cookie 里取某个 cookie 的值，取不到返回 null。 */
    private String setCookieValue(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .map(value -> value.split(";", 2)[0])
                .map(value -> value.substring(name.length() + 1))
                .findFirst()
                .orElse(null);
    }

    /** 从请求用的 Cookie 串里取某个 cookie 的值。 */
    private String cookieValue(String cookies, String name) {
        for (String part : cookies.split(";")) {
            String item = part.trim();
            if (item.startsWith(name + "=")) {
                return item.substring(name.length() + 1);
            }
        }
        return null;
    }

    private Resp get(String path, String cookie) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        HttpResponse<String> response = send(builder.build());
        return new Resp(response.statusCode(), response.body(), cookie);
    }

    private Resp post(String path, String cookie) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody());
        if (cookie != null) {
            builder.header("Cookie", cookie);
            builder.header("X-XSRF-TOKEN", cookieValue(cookie, "XSRF-TOKEN"));
        }
        HttpResponse<String> response = send(builder.build());
        return new Resp(response.statusCode(), response.body(), cookie);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("请求失败: " + request.uri(), e);
        }
    }

    /**
     * 其它测试类可能让同名账号的密码发生变化，这里把账号拉回已知状态。
     * （与 WorkbenchApiTest 的做法一致，避免用例成败取决于执行顺序。）
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

    @Test
    void secondLoginKicksThePreviousSession() {
        ensureDemoUser("manager");

        String first = login("manager");
        assertEquals(200, get("/api/auth/me", first).status(), "首次登录的会话应当有效");

        String second = login("manager");
        assertNotEquals(first, second, "第二次登录必须换新的 JSESSIONID");

        Resp kicked = get("/api/auth/me", first);
        assertEquals(401, kicked.status(), "旧会话应当被踢下线");
        assertTrue(kicked.body().contains("其它设备"),
                "旧会话应明确提示已在别处登录，实际响应: " + kicked.body());

        assertEquals(200, get("/api/auth/me", second).status(), "新会话应当保持有效");
    }

    @Test
    void differentUsersDoNotKickEachOther() {
        ensureDemoUser("manager");
        ensureDemoUser("alice");

        String manager = login("manager");
        String alice = login("alice");

        assertEquals(200, get("/api/auth/me", manager).status(), "manager 的会话不应被 alice 登录影响");
        assertEquals(200, get("/api/auth/me", alice).status(), "alice 的会话不应被 manager 登录影响");
    }

    @Test
    void logoutReleasesTheSessionSlot() {
        ensureDemoUser("manager");

        String session = login("manager");
        assertEquals(200, get("/api/auth/me", session).status(), "退出前会话应当有效");

        assertEquals(200, post("/api/auth/logout", session).status(), "退出接口应当幂等成功");

        Resp afterLogout = get("/api/auth/me", session);
        assertEquals(401, afterLogout.status(), "退出后旧 Cookie 不应再通过认证");
        assertFalse(afterLogout.body().contains("其它设备"),
                "退出后的 401 不应被当成“被顶下线”，实际响应: " + afterLogout.body());

        // 退出释放名额后重新登录，新会话应直接可用
        String again = login("manager");
        assertEquals(200, get("/api/auth/me", again).status(), "退出后重新登录应当成功");
    }
}
