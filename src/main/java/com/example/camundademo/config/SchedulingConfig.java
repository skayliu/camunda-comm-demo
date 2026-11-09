package com.example.camundademo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关：阅办逾期标记依赖 {@code @Scheduled}。
 * 单独成类，避免把调度能力混进 Camunda 引擎配置。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
