package com.example.camundademo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置类：注册自定义Camunda引擎插件为Spring Bean，Camunda会自动检测并加载
 */
@Configuration
public class CamundaConfig {

    @Bean
    public CommProcessEnginePlugin commProcessEnginePlugin() {
        return new CommProcessEnginePlugin();
    }
}
