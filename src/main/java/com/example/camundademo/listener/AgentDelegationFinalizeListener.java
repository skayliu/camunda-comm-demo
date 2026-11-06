package com.example.camundademo.listener;

import com.example.camundademo.entity.BizTaskDelegate;
import com.example.camundademo.persistence.BizTaskDelegateManager;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;

import java.util.Date;

/**
 * 「可直接办结」代理的任务办结时，把委托记录从 PENDING 收尾成 COMPLETED。
 *
 * <p>CONFIRM 模式走 {@code resolveTask}，由 {@code TaskDelegateService} 自己改记录；
 * SIGN 模式没有 resolve 这一步，任务被代理人的 complete 直接结束，
 * 若不在这里收尾，记录会永远停在 PENDING——委托记录页的筛选和“委托中”角标
 * 只能靠 {@code taskActive} 兜底，语义上是错的。</p>
 *
 * <p>放在 complete 事件里、与 {@code DelegationCompleteGuardListener} 同一阶段：
 * 后者先执行（只拦 PENDING），SIGN 模式 DELEGATION_STATE_ 为 null 会直接放行；
 * 若后续节点抛错导致整个命令回滚，这条 UPDATE 一并回滚，不会出现“记录已完结但任务没走”。</p>
 */
public class AgentDelegationFinalizeListener implements TaskListener {

    @Override
    public void notify(DelegateTask delegateTask) {
        if (delegateTask == null) {
            return;
        }
        CommandContext commandContext = Context.getCommandContext();
        if (commandContext == null) {
            return;
        }
        BizTaskDelegateManager delegateMgr = commandContext.getSession(BizTaskDelegateManager.class);
        BizTaskDelegate record = delegateMgr.findPendingByTaskId(delegateTask.getId());
        // 只处理 SIGN：CONFIRM 模式的 PENDING 记录由 resolve 收尾，走到这里说明有人绕过守卫，
        // 那种情况交给守卫抛错，不在这里伪造“已办结”
        if (record == null || !BizTaskDelegate.MODE_SIGN.equals(record.getDelegateMode())) {
            return;
        }
        Date now = new Date();
        record.setStatus(BizTaskDelegate.STATUS_COMPLETED);
        record.setResolveTime(now);
        record.setUpdateTime(now);
        delegateMgr.update(record);
    }
}
