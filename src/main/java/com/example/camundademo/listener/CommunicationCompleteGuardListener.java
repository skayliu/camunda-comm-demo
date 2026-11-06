package com.example.camundademo.listener;

import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.entity.BizTaskExt;
import com.example.camundademo.persistence.BizTaskExtManager;
import com.example.camundademo.service.CommunicationLockedException;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;

/**
 * 任务完成前的兜底校验：待办正在沟通中时禁止 complete。
 *
 * <p>与 {@code CommunicationCommandInterceptor} 的区别：</p>
 * <ul>
 *   <li>拦截器在命令执行前判断，能给出更早、更明确的业务异常，但需要反射读取命令内部的 taskId；</li>
 *   <li>本监听器挂在任务的 {@code complete} 事件上，拿的是 {@link DelegateTask} 本身，
 *       <b>不依赖任何命令字段名</b>，因此 Camunda 升级导致反射失效时仍能兜住；</li>
 *   <li>覆盖范围同样完整：REST、Cockpit、自研接口最终都会走 {@code TaskEntity.complete()} 并触发该事件。</li>
 * </ul>
 *
 * <p>沟通任务自身（key 以 {@code #COMM} 结尾）直接放行——它的 complete 就是“反馈”，必须允许。</p>
 */
public class CommunicationCompleteGuardListener implements TaskListener {

    @Override
    public void notify(DelegateTask delegateTask) {
        if (delegateTask == null) {
            return;
        }
        if (BizCommTask.isCommTaskKey(delegateTask.getTaskDefinitionKey())) {
            return;
        }
        CommandContext commandContext = Context.getCommandContext();
        if (commandContext == null) {
            // 没有命令上下文（脱离引擎线程）时不在这里判定，交给拦截器
            return;
        }
        BizTaskExt ext = commandContext.getSession(BizTaskExtManager.class)
                .findByTaskId(delegateTask.getId());
        if (ext != null && BizTaskExt.STATUS_COMMUNICATING.equals(ext.getBizStatus())) {
            throw new CommunicationLockedException("当前待办正在沟通中，请等待沟通完成或收回沟通后再审批");
        }
    }
}
