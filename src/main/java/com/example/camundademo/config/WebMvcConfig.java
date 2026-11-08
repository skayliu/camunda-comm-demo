package com.example.camundademo.config;

import java.time.Duration;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 前端入口跳转与静态资源缓存策略：待办中心静态资源由 Vite 构建到 /static/app（访问路径 /app/）。
 * 这里把根路径直接导向前端，避免用户看到 Whitelabel 页面。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 构建产物带内容哈希（如 index-DKAumfTY.js），内容一变文件名就变，
     * 因此可以长期强缓存；入口 HTML 文件名固定，必须每次回源校验，
     * 否则前端升级后浏览器仍拿着旧 index.html 去加载已删除的旧 chunk。
     */
    private static final String ASSETS_PATTERN = "/app/assets/**";
    private static final String APP_PATTERN = "/app/**";

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/app/");
        registry.addRedirectViewController("/workbench", "/app/");
        // Spring Boot 的欢迎页只对根路径 "/" 生效，子目录 /app/ 不会被解析成 /app/index.html，
        // 直接访问会落到 Whitelabel 404。这里显式 forward，路径尾斜杠在 Spring 6 不再自动等价，两个都注册。
        registry.addViewController("/app").setViewName("forward:/app/index.html");
        registry.addViewController("/app/").setViewName("forward:/app/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 更具体的 /app/assets/** 优先匹配，落到下面的 /app/** 规则之前。
        registry.addResourceHandler(ASSETS_PATTERN)
                .addResourceLocations("classpath:/static/app/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .resourceChain(true);

        // index.html 等文件名不变的资源：允许缓存但每次必须校验，可命中 304 又不会用到旧入口。
        registry.addResourceHandler(APP_PATTERN)
                .addResourceLocations("classpath:/static/app/")
                .setCacheControl(CacheControl.noCache().mustRevalidate());
    }
}
