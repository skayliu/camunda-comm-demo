package com.example.camundademo.interceptor;

import com.example.camundademo.entity.BizTaskExt;
import com.example.camundademo.persistence.BizTaskExtManager;
import com.example.camundademo.service.CommunicationLockedException;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandInterceptor;
import org.camunda.bpm.engine.impl.persistence.entity.TaskEntity;
import org.camunda.bpm.engine.impl.cmd.CompleteTaskCmd;
import org.camunda.bpm.engine.impl.cmd.SubmitTaskFormCmd;

import java.lang.reflect.Field;

/**
 * 引擎命令拦截器：完成任务前校验是否处于沟通中，引擎内核层面兜底，所有渠道无法绕过
 */
public class CommunicationCommandInterceptor extends CommandInterceptor {

    @Override
    public <T> T execute(Command<T> command) {
        // 拦截普通完成任务 和 表单提交完成任务两种审批场景
        if (command instanceof CompleteTaskCmd || command instanceof SubmitTaskFormCmd) {
            String taskId = extractTaskId(command);
            if (taskId != null) {
                TaskEntity task = getContext().getTaskManager().findTaskById(taskId);
                if (task != null) {
                    // 沟通任务可以直接完成（相当于反馈），直接放行
                    if ("COMM".equals(task.getTaskDefinitionKey())) {
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
        }
        return next.execute(command);
    }

    /**
     * 反射安全读取命令类中的私有taskId字段，兼容不同Camunda版本内部结构
     */
    private String extractTaskId(Object cmd) {
        try {
            Class<?> clazz = cmd.getClass();
            while (clazz != null) {
                try {
                    Field field = clazz.getDeclaredField("taskId");
                    field.setAccessible(true);
                    return (String) field.get(cmd);
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (Exception e) {
            System.err.println("[CommInterceptor] 无法读取taskId: " + e.getMessage());
        }
        return null;
    }

    private org.camunda.bpm.engine.impl.interceptor.CommandContext getContext() {
        return org.camunda.bpm.engine.impl.context.Context.getCommandContext();
    }
}