package com.example.camundademo.listener;

import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.entity.BizTaskExt;
import com.example.camundademo.persistence.BizTaskExtManager;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.persistence.entity.TaskEntity;

import java.util.Date;
import java.util.UUID;

/**
 * 全局任务创建监听器：自动为每个流程任务初始化BizTaskExt记录
 */
public class TaskCreateInitListener implements TaskListener {

    @Override
    public void notify(DelegateTask delegateTask) {
        TaskEntity task = (TaskEntity) delegateTask;
        // 沟通任务我们自己在创建时就初始化过扩展记录，跳过
        String taskDefKey = task.getTaskDefinitionKey();
        if (BizCommTask.isCommTaskKey(taskDefKey)) {
            return;
        }

        // 流程任务自动初始化扩展记录
        BizTaskExtManager extMgr = Context.getCommandContext().getSession(BizTaskExtManager.class);
        BizTaskExt existing = extMgr.findByTaskId(task.getId());
        if (existing != null) {
            return;
        }

        BizTaskExt ext = new BizTaskExt();
        ext.setId(UUID.randomUUID().toString().replace("-", ""));
        ext.setTaskId(task.getId());
        ext.setProcessInstanceId(task.getProcessInstanceId());
        ext.setAssignee(task.getAssignee());
        ext.setBizStatus(BizTaskExt.STATUS_NORMAL);
        ext.setCreateTime(new Date());
        ext.setUpdateTime(new Date());
        extMgr.insert(ext);
    }
}
