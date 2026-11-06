package com.example.camundademo;

import com.example.camundademo.entity.BizTaskExt;
import com.example.camundademo.listener.CommunicationCompleteGuardListener;
import com.example.camundademo.persistence.BizTaskExtManager;
import com.example.camundademo.service.CommunicationLockedException;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * 兜底监听器单测：验证它<b>不依赖命令内部的 taskId 字段</b>，仅凭任务本身就能判定。
 *
 * <p>测试直接在引擎命令上下文里调用监听器，模拟真实 complete 事件的环境
 * （{@code Context.getCommandContext()} 可用、Session 可查）。</p>
 */
@SpringBootTest
class CommunicationCompleteGuardTest {

    @Autowired
    private ProcessEngine processEngine;

    private ProcessEngineConfigurationImpl config() {
        return (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    }

    private void insertExt(String taskId, String status) {
        BizTaskExt ext = new BizTaskExt();
        ext.setId(UUID.randomUUID().toString().replace("-", ""));
        ext.setTaskId(taskId);
        ext.setBizStatus(status);
        ext.setAssignee("manager");
        ext.setCreateTime(new Date());
        ext.setUpdateTime(new Date());
        config().getCommandExecutorTxRequired().execute(ctx -> {
            ctx.getSession(BizTaskExtManager.class).insert(ext);
            return null;
        });
    }

    private DelegateTask mockTask(String taskId, String taskDefinitionKey) {
        DelegateTask task = Mockito.mock(DelegateTask.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getTaskDefinitionKey()).thenReturn(taskDefinitionKey);
        return task;
    }

    @Test
    void blocksCompletingTaskInCommunication() {
        String taskId = "guard-probe-" + UUID.randomUUID();
        insertExt(taskId, BizTaskExt.STATUS_COMMUNICATING);
        DelegateTask task = mockTask(taskId, "manager_review");

        assertThrows(CommunicationLockedException.class, () ->
                config().getCommandExecutorTxRequired().execute(ctx -> {
                    new CommunicationCompleteGuardListener().notify(task);
                    return null;
                }));
    }

    @Test
    void allowsNormalTask() {
        String taskId = "guard-probe-" + UUID.randomUUID();
        insertExt(taskId, BizTaskExt.STATUS_NORMAL);
        DelegateTask task = mockTask(taskId, "manager_review");

        assertDoesNotThrow(() ->
                config().getCommandExecutorTxRequired().execute(ctx -> {
                    new CommunicationCompleteGuardListener().notify(task);
                    return null;
                }));
    }

    @Test
    void allowsCommunicationTaskItself() {
        // 沟通任务自身的 complete 就是“反馈”，必须放行；即使扩展表里没有对应记录
        DelegateTask task = mockTask("comm-task-" + UUID.randomUUID(), "manager_review#COMM");

        assertDoesNotThrow(() ->
                config().getCommandExecutorTxRequired().execute(ctx -> {
                    new CommunicationCompleteGuardListener().notify(task);
                    return null;
                }));
    }
}
