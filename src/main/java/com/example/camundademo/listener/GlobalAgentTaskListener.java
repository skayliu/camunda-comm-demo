package com.example.camundademo.listener;

import com.example.camundademo.entity.BizAgentRule;
import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.entity.BizTaskDelegate;
import com.example.camundademo.entity.BizTaskExt;
import com.example.camundademo.persistence.BizAgentRuleManager;
import com.example.camundademo.persistence.BizTaskDelegateManager;
import com.example.camundademo.persistence.BizTaskExtManager;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.persistence.entity.TaskEntity;
import org.camunda.bpm.engine.task.DelegationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 全局代理监听器：任务分配给某人时，若此人当前有生效的代理规则，自动委托给代理人。
 *
 * <p><b>为什么挂在 create + assignment 两个事件</b>：</p>
 * <ul>
 *   <li>create：BPMN 里直接写死 assignee 的任务；</li>
 *   <li>assignment：候选人任务被 claim 认领时，assignee 从空变为具体人。
 *       只挂 create 会漏掉“认领后应该自动代理”的场景。</li>
 * </ul>
 *
 * <p><b>为什么不批量改回</b>：规则到期后只影响新任务，已经代理出去的任务保持原样。
 * 若到期时批量把 ASSIGNEE_ 改回原人，代理人处理到一半的任务会突然从待办里消失。</p>
 *
 * <p><b>两种办理方式</b>：{@code CONFIRM} 走引擎原生 {@code delegate}（责任人仍是委托人，
 * 受托人只能交回）；{@code SIGN} 直接 {@code setAssignee}（任务归受托人，可直接办结，
 * 流程按其审批结果继续流转）。SIGN 分支必须设置重入标记：{@code setAssignee} 会同步触发
 * assignment 事件，没有标记时会再次进入本监听器，若代理人自己也配了规则就会连环改派。</p>
 *
 * <p>本类由 {@code GlobalUserTaskParseListener} 注入到每个 userTask，
 * 不需要任何 BPMN 改动，也不能做成 Spring Bean（挂在解析期，随流程定义走）。</p>
 */
public class GlobalAgentTaskListener implements TaskListener {

    private static final Logger log = LoggerFactory.getLogger(GlobalAgentTaskListener.class);

    public static final String AUTO_REASON = "代理规则自动委托";

    /**
     * 同一线程内的重入标记：{@code setAssignee} 会同步回调 assignment 监听器。
     * 用标记而不是“先写记录再查”的方式，是因为插入尚未 flush 时查询可能读不到，
     * 依赖它挡重入在命令边界上不成立。
     */
    private static final ThreadLocal<Boolean> APPLYING = new ThreadLocal<>();

    @Override
    public void notify(DelegateTask delegateTask) {
        if (Boolean.TRUE.equals(APPLYING.get())) {
            return;
        }
        APPLYING.set(Boolean.TRUE);
        try {
            apply(delegateTask);
        } catch (RuntimeException e) {
            // 代理失败绝不能阻断任务创建：流程卡住比“没代理上”严重得多
            log.error("任务 {} 自动代理失败，按原办理人继续", delegateTask == null ? null : delegateTask.getId(), e);
        } finally {
            APPLYING.remove();
        }
    }

    private void apply(DelegateTask delegateTask) {
        if (delegateTask == null) {
            return;
        }
        // 沟通待办的办理人是沟通对象本人，改派会让反馈权限失效
        if (BizCommTask.isCommTaskKey(delegateTask.getTaskDefinitionKey())) {
            return;
        }
        String assignee = delegateTask.getAssignee();
        if (assignee == null || assignee.isBlank()) {
            // 候选任务不处理：等认领后再由 assignment 事件判断
            return;
        }
        if (DelegationState.PENDING.equals(((TaskEntity) delegateTask).getDelegationState())) {
            // 已经处于委托中（就是我们刚代理出去的那次 assignment 回调），不再套娃
            return;
        }
        CommandContext commandContext = Context.getCommandContext();
        if (commandContext == null) {
            return;
        }

        BizAgentRuleManager ruleMgr = commandContext.getSession(BizAgentRuleManager.class);
        List<BizAgentRule> rules = ruleMgr.findValidByOwner(assignee, new Date());
        if (rules.isEmpty()) {
            return;
        }
        String processKey = processKey(delegateTask.getProcessDefinitionId());
        BizAgentRule hit = null;
        for (BizAgentRule rule : rules) {
            if (rule.coversProcess(processKey)) {
                hit = rule;
                break;
            }
        }
        if (hit == null || assignee.equals(hit.getAgentUserId())) {
            return;
        }

        BizTaskDelegateManager delegateMgr = commandContext.getSession(BizTaskDelegateManager.class);
        if (delegateMgr.findPendingByTaskId(delegateTask.getId()) != null) {
            // 幂等：create 与 assignment 可能对同一任务各触发一次
            return;
        }

        // CONFIRM：等价于 taskService.delegateTask —— OWNER_=原办理人、ASSIGNEE_=代理人、DELEGATION_STATE_=PENDING。
        // SIGN：等价于 taskService.setAssignee —— 任务直接归代理人，DELEGATION_STATE_ 保持 null。
        // 直接操作 TaskEntity 而不走 TaskService，是为了避免在 create 事件里再发一次命令
        // （任务此时还在插入队列中，重新按 ID 查询并不可靠）。
        TaskEntity task = (TaskEntity) delegateTask;
        Date now = new Date();

        BizTaskDelegate record = new BizTaskDelegate();
        record.setId(UUID.randomUUID().toString().replace("-", ""));
        record.setTaskId(task.getId());
        record.setProcessInstanceId(task.getProcessInstanceId());
        record.setTaskDefKey(task.getTaskDefinitionKey());
        record.setTaskName(task.getName());
        record.setOwnerUserId(assignee);
        record.setDelegateUserId(hit.getAgentUserId());
        record.setSource(BizTaskDelegate.SOURCE_AGENT);
        record.setDelegateMode(hit.isSignMode() ? BizTaskDelegate.MODE_SIGN : BizTaskDelegate.MODE_CONFIRM);
        record.setAgentRuleId(hit.getId());
        record.setReason(AUTO_REASON);
        record.setStatus(BizTaskDelegate.STATUS_PENDING);
        record.setCreateTime(now);
        record.setUpdateTime(now);

        if (hit.isSignMode()) {
            // OWNER_ 仍记原办理人：审计能看出“这是谁的活、由谁受托办理”，收回也据此回到本人
            task.setOwner(assignee);
            task.setAssignee(hit.getAgentUserId());
        } else {
            task.delegate(hit.getAgentUserId());
        }
        // 记录随任务一起落库：SIGN 模式下这是“该任务已被代理”的唯一持久标记
        delegateMgr.insert(record);

        syncExt(commandContext, task, hit.getAgentUserId(), now);

        log.info("任务 {} 自动代理（{}）：{} -> {}（规则 {}）",
                task.getId(), hit.isSignMode() ? "可直接办结" : "需本人确认",
                assignee, hit.getAgentUserId(), hit.getId());
    }

    /**
     * 同步扩展表的 ASSIGNEE_。
     *
     * <p>create 事件里扩展记录由后续的 {@code TaskCreateInitListener} 创建（顺序已保证），
     * 此处查不到属正常，直接跳过——它建记录时会读取代理后的 assignee。
     * 只在 assignment（claim 认领）场景下记录已存在，需要在这里改掉。</p>
     */
    private void syncExt(CommandContext commandContext, TaskEntity task, String assignee, Date now) {
        BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);
        BizTaskExt ext = extMgr.findByTaskId(task.getId());
        if (ext == null) {
            return;
        }
        ext.setAssignee(assignee);
        ext.setUpdateTime(now);
        extMgr.update(ext);
    }

    private String processKey(String processDefinitionId) {
        if (processDefinitionId == null || processDefinitionId.isBlank()) {
            return null;
        }
        int idx = processDefinitionId.indexOf(':');
        return idx > 0 ? processDefinitionId.substring(0, idx) : processDefinitionId;
    }
}
