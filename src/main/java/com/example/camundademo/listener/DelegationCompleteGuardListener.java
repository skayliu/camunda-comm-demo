package com.example.camundademo.listener;

import com.example.camundademo.service.BusinessException;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.persistence.entity.TaskEntity;
import org.camunda.bpm.engine.task.DelegationState;

/**
 * 委托状态的完成兜底：处于委托中（PENDING）的任务不允许被 complete。
 *
 * <p>受托人提交受托意见走的是 {@code resolveTask}（不触发 complete 事件），
 * 因此这里拦住的正是“受托人直接点同意”的那条路径——它会把委托退化成转办，
 * 原办理人连确认的机会都没有。委托人想中止委托，应走"收回委托"，
 * 引擎会把 ASSIGNEE_ 还给 OWNER_，之后才能正常办结。</p>
 *
 * <p>不区分操作人：Camunda 原生语义下 PENDING 任务本就不该由 complete 结束，
 * 一律要求先 resolve。这样接口层、Cockpit、REST 三条路径的行为完全一致。</p>
 */
public class DelegationCompleteGuardListener implements TaskListener {

    @Override
    public void notify(DelegateTask delegateTask) {
        if (delegateTask == null) {
            return;
        }
        // DelegateTask 接口不暴露委托状态，实现类是 TaskEntity
        if (!DelegationState.PENDING.equals(((TaskEntity) delegateTask).getDelegationState())) {
            return;
        }
        throw new BusinessException("该待办处于委托中，请由受托人提交反馈或委托人收回后再办结");
    }
}
