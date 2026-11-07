package com.example.camundademo.support;

import com.example.camundademo.security.DemoUserInitializer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用的会话登录工具。
 *
 * <p>接口改成服务端会话后，请求不再携带 {@code Authorization: Basic ...}，
 * 而是先调 {@code POST /api/auth/login} 拿 {@code Set-Cookie: JSESSIONID=...}，
 * 后续请求放进 {@code Cookie} 头。</p>
 *
 * <p><b>CSRF</b>：启用 CSRF 校验后，写操作还必须带 {@code X-XSRF-TOKEN} 请求头，
 * 且令牌与 {@code XSRF-TOKEN} cookie 一致（双提交）。登录本身也受校验，
 * 所以这里先 {@code GET /api/auth/csrf} 取令牌，再带着它登录；
 * cookie 串同时包含 {@code JSESSIONID} 与 {@code XSRF-TOKEN}，
 * 调用方只需要把 {@link #csrfToken(int, String)} 塞进请求头即可。</p>
 *
 * <p>同一「端口 + 用户」复用同一个会话，避免每个断言都登录一次；
 * 端口作为 key 的一部分，是因为各测试类用 {@code RANDOM_PORT} 起独立上下文。</p>
 */
public final class TestSession {

    /** 一个账号的登录态：可直接放进 Cookie 头的串，以及配套的 CSRF 令牌。 */
    public record Session(String cookie, String csrfToken) {
    }

    private static final Map<String, Session> CACHE = new ConcurrentHashMap<>();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private TestSession() {
    }

    /**
     * 返回可直接放进 {@code Cookie} 请求头的字符串，
     * 形如 {@code JSESSIONID=XXXX; XSRF-TOKEN=YYYY}。
     */
    public static String cookie(int port, String userId) {
        return session(port, userId).cookie();
    }

    /**
     * 返回该会话的 CSRF 令牌，调用方写请求时放进 {@code X-XSRF-TOKEN} 头。
     */
    public static String csrfToken(int port, String userId) {
        return session(port, userId).csrfToken();
    }

    public static Session session(int port, String userId) {
        return CACHE.computeIfAbsent(port + "|" + userId, key -> login(port, userId));
    }

    /**
     * 清空缓存。切换数据库或重启上下文后应调用，避免拿旧会话打新服务。
     */
    public static void clear() {
        CACHE.clear();
    }

    private static Session login(int port, String userId) {
        // 1. 取 CSRF 令牌。这个接口免认证，且会把令牌写进 XSRF-TOKEN cookie。
        HttpResponse<String> csrfResponse = send(HttpRequest.newBuilder(uri(port, "/api/auth/csrf"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build());
        if (csrfResponse.statusCode() != 200) {
            throw new IllegalStateException("获取 CSRF 令牌失败: "
                    + csrfResponse.statusCode() + " " + csrfResponse.body());
        }
        String csrfToken = cookieValue(csrfResponse, "XSRF-TOKEN");
        if (csrfToken == null || csrfToken.isEmpty()) {
            throw new IllegalStateException("CSRF 接口没有下发 XSRF-TOKEN: "
                    + csrfResponse.headers().map());
        }

        // 2. 带令牌登录；请求头与 cookie 必须同时出现，服务端做双提交比对
        String body = "{\"userId\":\"" + userId + "\",\"password\":\""
                + DemoUserInitializer.DEMO_PASSWORD + "\"}";
        HttpResponse<String> loginResponse = send(HttpRequest.newBuilder(uri(port, "/api/auth/login"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Cookie", "XSRF-TOKEN=" + csrfToken)
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
        if (loginResponse.statusCode() != 200) {
            throw new IllegalStateException("登录失败 " + userId + ": "
                    + loginResponse.statusCode() + " " + loginResponse.body());
        }
        String sessionCookie = cookieValue(loginResponse, "JSESSIONID");
        if (sessionCookie == null) {
            throw new IllegalStateException("登录成功但响应没有 JSESSIONID: "
                    + loginResponse.headers().map());
        }
        // 登录响应若 refresh 了 CSRF cookie，以新的为准
        String refreshed = cookieValue(loginResponse, "XSRF-TOKEN");
        if (refreshed != null) {
            csrfToken = refreshed;
        }
        return new Session("JSESSIONID=" + sessionCookie + "; XSRF-TOKEN=" + csrfToken, csrfToken);
    }

    private static URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("请求失败: " + request.uri(), e);
        }
    }

    /** 从 Set-Cookie 里取某个 cookie 的值，取不到返回 null。 */
    private static String cookieValue(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .map(value -> value.split(";", 2)[0])
                .map(value -> value.substring(name.length() + 1))
                .findFirst()
                .orElse(null);
    }
}
