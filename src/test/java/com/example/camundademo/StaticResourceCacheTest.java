package com.example.camundademo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 静态资源缓存策略：拆包之后只有配上正确的 Cache-Control 才有收益。
 *
 * <p>两条相反的要求必须同时成立：</p>
 * <ul>
 *   <li>带内容哈希的 assets 可以长期强缓存，内容变化会换文件名，不存在旧内容风险；</li>
 *   <li>index.html 文件名固定，必须每次回源校验，否则升级后浏览器仍拿旧入口去请求已删除的旧 chunk，页面直接白屏。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaticResourceCacheTest {

    private static final Pattern ENTRY_ASSET = Pattern.compile("/app/assets/[^\"']+\\.js");

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void entryHtmlRevalidatesAndHashedAssetsCacheForever() throws Exception {
        HttpResponse<String> index = get("/app/index.html");
        // 前端未构建时 classpath 下没有产物，跳过而不是让测试失败
        assumeTrue(index.statusCode() == 200, "static/app 下无构建产物，跳过缓存头断言");

        String indexCache = index.headers().firstValue("Cache-Control").orElse("");
        assertTrue(indexCache.contains("no-cache"),
                "index.html 必须每次校验，实际 Cache-Control=" + indexCache);
        assertFalse(indexCache.contains("immutable"),
                "index.html 不能被强缓存，实际 Cache-Control=" + indexCache);

        // 从入口 HTML 里取真实文件名，避免在测试里硬编码 hash
        Matcher matcher = ENTRY_ASSET.matcher(index.body());
        assertTrue(matcher.find(), "index.html 中未找到入口 chunk 引用");

        HttpResponse<String> asset = get(matcher.group());
        assertEquals(200, asset.statusCode());
        String assetCache = asset.headers().firstValue("Cache-Control").orElse("");
        assertTrue(assetCache.contains("max-age=31536000"),
                "带哈希的 assets 应长缓存，实际 Cache-Control=" + assetCache);
        assertTrue(assetCache.contains("immutable"),
                "带哈希的 assets 应标记 immutable，实际 Cache-Control=" + assetCache);
        assertTrue(assetCache.contains("public"),
                "内网反向代理下需要 public 才可缓存，实际 Cache-Control=" + assetCache);
    }
}
