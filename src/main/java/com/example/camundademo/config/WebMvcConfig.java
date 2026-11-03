package com.example.camundademo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 前端入口跳转：待办中心静态资源由 Vite 构建到 /static/app（访问路径 /app/）。
 * 这里把根路径直接导向前端，避免用户看到 Whitelabel 页面。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/app/");
        registry.addRedirectViewController("/workbench", "/app/");
    }
}
