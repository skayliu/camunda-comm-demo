package com.example.camundademo.service;

/**
 * 待办处于沟通中，禁止审批。
 * <p>
 * 单独定义类型的原因：这是<b>可预期的业务拦截</b>，不是流程执行错误。
 * 接口层只把这一类异常翻译成 200 + error:true，其余异常（变量缺失、
 * 任务不存在、数据库错误等）必须保留 5xx，避免真实故障被误读成“沟通中”。
 */
public class CommunicationLockedException extends RuntimeException {

    public static final String CODE = "TASK_COMMUNICATING";

    public CommunicationLockedException(String message) {
        super(message);
    }
}
