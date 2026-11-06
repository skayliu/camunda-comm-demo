package com.example.camundademo.interceptor;

import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.entity.BizTaskExt;
import com.example.camundademo.persistence.BizTaskExtManager;
import com.example.camundademo.service.CommunicationLockedException;
import org.camunda.bpm.engine.impl.cmd.CompleteTaskCmd;
import org.camunda.bpm.engine.impl.cmd.SubmitTaskFormCmd;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.interceptor.CommandInterceptor;
import org.camunda.bpm.engine.impl.persistence.entity.TaskEntity;

/**
 * 引擎命令拦截器：完成任务前校验是否处于沟通中，引擎内核层面兜底，所有渠道无法绕过。
 *
 * <p>taskId 的读取交由 {@link CommandTaskId}，本类只保留拦截判定。
 * 若读取失败（例如 Camunda 升级后字段改名），这里会放行并打印告警，
 * 真正的拦截由任务 {@code complete} 监听器 {@code CommunicationCompleteGuardListener} 完成——
 * 监听器不依赖任何命令内部结构，因此“读不到字段”不会退化成“锁定失效”。</p>
 */
public class CommunicationCommandInterceptor extends CommandInterceptor {

    @Override
    public <T> T execute(Command<T> command) {
        // 拦截普通完成任务 和 表单提交完成任务两种审批场景
        if (command instanceof CompleteTaskCmd || command instanceof SubmitTaskFormCmd) {
            String taskId = CommandTaskId.resolve(command);
            if (taskId == null) {
                // 不阻断执行：监听器会在 complete 事件上做同样校验
                System.err.println("[CommInterceptor] 无法从 " + command.getClass().getName()
                        + " 解析 taskId，已降级由 complete 监听器兜底");
                return next.execute(command);
            }
            TaskEntity task = getContext().getTaskManager().findTaskById(taskId);
            if (task != null) {
                // 沟通任务可以直接完成（相当于反馈），直接放行
                // key 形如「源节点key#COMM」，按后缀判断
                if (BizCommTask.isCommTaskKey(task.getTaskDefinitionKey())) {
                    return next.execute(command);
                }
                // 主流程待办：校验沟通状态
                BizTaskExtManager extMgr = getContext().getSession(BizTaskExtManager.class);
                BizTaskExt ext = extMgr.findByTaskId(taskId);
                if (ext != null && BizTaskExt.STATUS_COMMUNICATING.equals(ext.getBizStatus())) {
                    throw new CommunicationLockedException("当前待办正在沟通中，请等待沟通完成或收回沟通后再审批");
                }
            }
        }
        return next.execute(command);
    }

    private CommandContext getContext() {
        return Context.getCommandContext();
    }
}
