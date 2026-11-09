package com.example.camundademo.listener;

import com.example.camundademo.entity.BizCommSession;
import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.entity.BizTaskExt;
import com.example.camundademo.persistence.BizCommSessionManager;
import com.example.camundademo.persistence.BizCommTaskManager;
import com.example.camundademo.persistence.BizTaskExtManager;
import com.example.camundademo.service.DeptCommProcessService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.identity.Authentication;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 部门沟通子流程的完成监听器：把部门意见回写到沟通记录。
 *
 * <p>{@code dept_handle} 完成后，部门这次沟通就算闭环，必须把结果落到
 * {@code BIZ_COMM_TASK_}：否则发起人只看到一条“待反馈”，部门说了什么完全查不到。
 * 同时，如果会话里已经没有其它待反馈记录，要解锁源待办——这一步与
 * {@link com.example.camundademo.service.CommStateService#replyCommTask} 的口径一致。</p>
 *
 * <p>监听器运行在 complete 命令内，直接复用当前 {@link CommandContext} 的 Session，
 * 不再另开事务，保证“业务表 + 引擎任务”一次性提交。</p>
 */
@Component("deptCommReplyListener")
public class DeptCommReplyListener implements TaskListener {

    private static final Logger log = LoggerFactory.getLogger(DeptCommReplyListener.class);

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProcessEngine processEngine;

    @Override
    public void notify(DelegateTask delegateTask) {
        String recordId = (String) delegateTask.getVariable(DeptCommProcessService.VAR_COMM_RECORD_ID);
        String reply = (String) delegateTask.getVariable(DeptCommProcessService.VAR_REPLY);
        String handler = delegateTask.getAssignee();
        String replyText = reply == null ? "" : reply.trim();

        // 审批意见落到引擎任务上，「我的已办」才能显示部门给了什么意见；
        // 必须在任务结束前写，任务结束后同一 taskId 已不可写评论。
        // 评论作者取 Camunda 认证上下文，这里按任务办理人临时设置：
        // 从待办中心提交时外层已设过（CommStateService.replyDeptCommTask），
        // 但从 Camunda Tasklist 等其它入口完成时上下文为空，不兜底就会写出 USER_ID_ = null 的意见。
        if (!replyText.isEmpty()) {
            org.camunda.bpm.engine.IdentityService identity = processEngine.getIdentityService();
            if (handler != null && !handler.isBlank()) {
                identity.setAuthenticatedUserId(handler);
            }
            try {
                taskService.createComment(delegateTask.getId(),
                        delegateTask.getProcessInstanceId(), replyText);
            } catch (RuntimeException e) {
                // 评论写失败不应让部门已经做完的沟通作废，业务表仍会记录意见
                log.warn("部门沟通意见写评论失败：task={}", delegateTask.getId(), e);
            } finally {
                identity.clearAuthentication();
            }
        }

        if (recordId == null) {
            log.warn("部门沟通任务缺少 commRecordId 变量，跳过回写：task={}", delegateTask.getId());
            return;
        }

        CommandContext commandContext = Context.getCommandContext();
        if (commandContext != null) {
            apply(commandContext, recordId, handler, replyText);
            return;
        }
        // 理论上不会走到这里（监听器总在命令内执行）；兜底另开一个命令，避免静默丢数据
        ProcessEngineConfigurationImpl config =
                (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
        config.getCommandExecutorTxRequired().execute((Command<Void>) ctx -> {
            apply(ctx, recordId, handler, replyText);
            return null;
        });
    }

    /**
     * 供其它子流程的结束监听器复用：在当前命令内把部门意见回写到沟通记录。
     *
     * <p>三节点通用流程、法务/财务专属流程的结束事件都调这里，
     * 保证「回写字段 + 解锁条件」只有一份实现。</p>
     */
    public void writeBack(CommandContext commandContext, String recordId, String handler, String replyText) {
        apply(commandContext, recordId, handler, replyText == null ? "" : replyText.trim());
    }

    /** 命令外兜底：另开一个命令执行回写 */
    public void writeBackOutsideCommand(String recordId, String handler, String replyText) {
        ProcessEngineConfigurationImpl config =
                (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
        config.getCommandExecutorTxRequired().execute((Command<Void>) ctx -> {
            apply(ctx, recordId, handler, replyText == null ? "" : replyText.trim());
            return null;
        });
    }

    private void apply(CommandContext commandContext, String recordId, String handler, String replyText) {
        BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);
        BizCommSessionManager sessionMgr = commandContext.getSession(BizCommSessionManager.class);
        BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);

        BizCommTask ct = taskMgr.findById(recordId);
        if (ct == null) {
            log.warn("部门沟通记录不存在，无法回写：{}", recordId);
            return;
        }
        if (!BizCommTask.STATUS_PENDING.equals(ct.getStatus())) {
            // 已被发起人收回/已反馈，重复回写会覆盖历史结论，直接跳过
            log.info("部门沟通记录已是 {}，忽略回写：{}", ct.getStatus(), recordId);
            return;
        }

        List<BizCommTask> allTasks = taskMgr.findBySessionId(ct.getSessionId());
        Date now = new Date();
        ct.setStatus(BizCommTask.STATUS_REPLIED);
        ct.setReplyContent(replyText);
        ct.setReplyUserId(handler);
        ct.setReplyTime(now);
        taskMgr.update(ct);

        boolean stillPending = false;
        for (BizCommTask t : allTasks) {
            if (t.getId().equals(ct.getId())) {
                continue;
            }
            if (BizCommTask.STATUS_PENDING.equals(t.getStatus())) {
                stillPending = true;
                break;
            }
        }
        if (stillPending) {
            return;
        }

        BizCommSession session = sessionMgr.findById(ct.getSessionId());
        if (session == null) {
            return;
        }
        session.setStatus(BizCommSession.STATUS_COMPLETED);
        session.setFinishTime(now);
        sessionMgr.update(session);

        BizTaskExt ext = extMgr.findByTaskId(session.getRootTaskId());
        if (ext != null) {
            ext.setBizStatus(BizTaskExt.STATUS_NORMAL);
            ext.setActiveSessionId(null);
            ext.setUpdateTime(now);
            extMgr.update(ext);
        }
        log.info("部门沟通已回写并解锁源待办：record={}, handler={}", recordId, handler);
    }
}
