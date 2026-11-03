package com.example.camundademo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 注意：不要加 @EnableProcessApplication。
 * Camunda Spring Boot Starter 已自动配置 ProcessApplication；显式启用会关闭
 * springConfiguration#deploymentResourcePattern 自动部署，导致 processes/*.bpmn 不被部署。
 */
@SpringBootApplication
public class CamundaCommDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(CamundaCommDemoApplication.class, args);
    }
}
