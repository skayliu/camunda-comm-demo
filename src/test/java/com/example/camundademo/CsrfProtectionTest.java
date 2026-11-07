package com.example.camundademo;

import com.example.camundademo.support.TestSession;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSRF 防护的端到端测试。
 *
 * <p>会话登录后，身份凭据是浏览器自动携带的 {@code JSESSIONID}，跨站页面
 * 不需要读到它也能让浏览器发出请求，因此写操作必须额外证明“请求来自本站页面”。
 * 这里钉住四件事：</p>
 *
 * <ol>
 *   <li>令牌接口免认证可用，并下发 {@code XSRF-TOKEN} cookie；</li>
 *   <li>只带 Cookie、不带请求头的写请求被 403 拒绝，且错误码可用于前端识别；</li>
 *   <li>读请求（GET）不受影响——加校验的意义是防跨站写，不是给所有请求加负担；</li>
 *   <li>非 {@code /api/**} 路径不归本应用的 CSRF 过滤器管，避免与
 *       Camunda Webapp 自带的 {@code CsrfPreventionFilter} 互相拦截。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // 独立 H2 库：会话与身份数据不与其他测试类互相影响
        "spring.datasource.url=jdbc:h2:mem:csrfdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class CsrfProtectionTest {

    @LocalServerPort
    private int port;

    private final ObjectMapper json = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private record Resp(int status, String body) {
    }

    private Resp get(String path, String cookie) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return send(builder.build());
    }

    /** token 为 null 模拟“只带 Cookie、不带请求头”的跨站请求。 */
    private Resp post(String path, String cookie, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody());
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if (token != null) {
            builder.header("X-XSRF-TOKEN", token);
        }
        return send(builder.build());
    }

    private Resp send(HttpRequest request) {
        return sendRaw(request).resp();
    }

    private record RawResp(Resp resp, HttpResponse<String> raw) {
    }

    private RawResp sendRaw(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new RawResp(new Resp(response.statusCode(), response.body()), response);
        } catch (Exception e) {
            throw new IllegalStateException("请求失败: " + request.uri(), e);
        }
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private Map<String, Object> parse(String body) {
        try {
            return json.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("响应不是 JSON 对象: " + body, e);
        }
    }

    private String setCookieValue(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .map(value -> value.split(";", 2)[0])
                .map(value -> value.substring(name.length() + 1))
                .findFirst()
                .orElse(null);
    }

    @Test
    void csrfTokenIsIssuedWithoutLogin() {
        RawResp response = sendRaw(HttpRequest.newBuilder(uri("/api/auth/csrf"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build());

        assertEquals(200, response.resp().status(),
                "取令牌接口应当免认证可用: " + response.resp().body());
        String cookieToken = setCookieValue(response.raw(), "XSRF-TOKEN");
        assertNotNull(cookieToken, "应当下发 XSRF-TOKEN cookie: "
                + response.raw().headers().map());

        Map<String, Object> body = parse(response.resp().body());
        assertEquals(cookieToken, body.get("token"), "响应体里的令牌应与 cookie 一致");
        assertEquals("X-XSRF-TOKEN", body.get("headerName"), "前端需要知道放进哪个请求头");
    }

    @Test
    void writeWithoutTokenIsRejected() {
        String cookie = TestSession.cookie(port, "alice");
        assertEquals(200, get("/api/auth/me", cookie).status(), "前置条件：会话应当有效");

        // 只有 Cookie、没有请求头：这正是跨站页面能发出的请求形态
        Resp rejected = post("/api/auth/logout", cookie, null);
        assertEquals(403, rejected.status(),
                "缺少 CSRF 令牌的写请求应当被拒绝，实际: " + rejected.body());
        assertTrue(rejected.body().contains("CSRF_INVALID"),
                "前端要靠错误码区分“令牌失效”和“没有权限”，实际: " + rejected.body());

        // 带上与 cookie 配对的令牌后应当放行
        Resp accepted = post("/api/auth/logout", cookie, TestSession.csrfToken(port, "alice"));
        assertEquals(200, accepted.status(), "带令牌的写请求应当成功: " + accepted.body());
    }

    @Test
    void readRequestsDoNotRequireToken() {
        String cookie = TestSession.cookie(port, "bob");
        assertEquals(200, get("/api/auth/me", cookie).status(),
                "GET 只读请求不应被 CSRF 校验拦住");
    }

    @Test
    void nonApiPathsAreNotCsrfChecked() {
        // 不存在的静态路径：可能是 404，也可能被 SPA 兜底成 200，
        // 但绝不能是 CSRF 的 403 —— 否则 Camunda Webapp 的 POST 会被本应用拦掉
        Resp response = post("/app/does-not-exist", null, null);
        assertNotEquals(403, response.status(),
                "非 /api 路径不应受本应用 CSRF 过滤器影响，实际: " + response.body());
    }
}
