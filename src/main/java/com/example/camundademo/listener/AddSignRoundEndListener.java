package com.example.camundademo.listener;

import com.example.camundademo.service.SignService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 加签子轮次结束监听器（挂在 addSignParallel / addSignSequential 的 process end 上）。
 *
 * <p>通过 {@code delegateExpression="${addSignRoundEndListener}"} 由 Spring 容器解析，
 * 因此可以直接注入 {@link SignService}。真正的收口逻辑放在服务里，
 * 保证“回写记录 + 解锁原任务 + 合并原意见”与引擎命令处于同一事务。</p>
 */
@Component("addSignRoundEndListener")
public class AddSignRoundEndListener implements ExecutionListener {

    @Autowired
    private SignService signService;

    @Override
    public void notify(DelegateExecution execution) {
        CommandContext commandContext = Context.getCommandContext();
        if (commandContext == null) {
            // 不在引擎命令线程内（理论上不会发生）：不在这里推进主流程，避免脱离事务
            return;
        }
        Object raw = execution.getVariable("addSignRejected");
        boolean rejected = Boolean.TRUE.equals(raw) || "true".equalsIgnoreCase(String.valueOf(raw));
        signService.onRoundEnd(commandContext, execution.getProcessInstanceId(), rejected);
    }
}
