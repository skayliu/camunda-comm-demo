package com.example.camundademo.service;

import com.example.camundademo.entity.BizCommSession;
import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.entity.BizTaskExt;
import com.example.camundademo.persistence.BizCommSessionManager;
import com.example.camundademo.persistence.BizCommTaskManager;
import com.example.camundademo.persistence.BizTaskExtManager;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 沟通状态机：收回与反馈。
 *
 * <p>两条状态迁移路径的唯一写入口。每次迁移都在<b>同一个引擎命令</b>内完成
 * “业务表状态 + 引擎任务 + 会话/待办锁定态”的更新，避免出现业务表说已收回、
 * 引擎任务却还挂在待办列表里的中间态。</p>
 *
 * <p>所有判定都基于命令开始时一次性查出的内存快照：Camunda 命令内不做中途 flush，
 * 改完再查库可能读到旧值。</p>
 */
@Service
public class CommStateService {

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProcessEngine processEngine;

    private ProcessEngineConfigurationImpl getEngineConfig() {
        return (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    }

    /**
     * 收回沟通（部分或全部）。
     *
     * <p>两种口径：</p>
     * <ul>
     *   <li><b>发起人</b>：按传入的记录收回其整棵子树；不传记录表示“全部收回”，直接结束本次沟通。</li>
     *   <li><b>上游沟通对象</b>：一次收回自己在这条链上的<b>全部下游未反馈记录</b>（含下级再转交的隔级记录）。
     *       例如 A→B，B→C、D，C→E：B 收回时 C、D、E 一并取消，B 仍可正常反馈，
     *       不需要逐条点击，也不会出现“收了下级却漏了更下一级”。</li>
     * </ul>
     *
     * @return 实际被收回的记录条数
     */
    public int withdrawCommunication(String sessionId, String operator, List<String> commTaskIds) {
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        return engineConfig.getCommandExecutorTxRequired().execute((Command<Integer>) commandContext -> {
            BizCommSessionManager sessionMgr = commandContext.getSession(BizCommSessionManager.class);
            BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);
            BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);

            BizCommSession session = sessionMgr.findById(sessionId);
            if (session == null) throw new BusinessException("会话不存在");

            // 一次性查出会话内全部沟通任务，之后全部在内存中判定状态，
            // 避免同一命令内“改了状态再查库”读到旧值（Camunda 命令内不做中途 flush）
            List<BizCommTask> allTasks = taskMgr.findBySessionId(sessionId);
            Map<String, BizCommTask> byId = CommChainRules.indexById(allTasks);
            boolean isInitiator = session.getInitiator() != null && session.getInitiator().equals(operator);

            Set<String> toWithdraw = new HashSet<>();
            if (commTaskIds == null || commTaskIds.isEmpty()) {
                // “全部收回”会直接结束本次沟通，仍然只允许发起人操作
                if (!isInitiator) {
                    throw new BusinessException("仅沟通发起人可一次性收回全部沟通");
                }
                for (BizCommTask t : allTasks) {
                    if (BizCommTask.STATUS_PENDING.equals(t.getStatus())) {
                        CommChainRules.collectChildren(allTasks, t.getId(), toWithdraw);
                    }
                }
            } else if (isInitiator) {
                for (String tid : commTaskIds) {
                    BizCommTask root = byId.get(tid);
                    if (root == null) {
                        throw new BusinessException("沟通记录不存在或不属于本次沟通");
                    }
                    CommChainRules.collectChildren(allTasks, tid, toWithdraw);
                }
            } else {
                // 上游沟通对象：一次收回自己下游的全部未反馈记录，而不是只收回被点击的那一条。
                // A→B、B→C/D、C→E 时，B 收回应当把 C、D、E 一起取消，B 自己那条保留以便继续反馈。
                Set<String> ownRecordIds = new HashSet<>();
                for (BizCommTask t : allTasks) {
                    if (operator.equals(t.getTargetId())) {
                        ownRecordIds.add(t.getId());
                    }
                }
                if (ownRecordIds.isEmpty()) {
                    throw new BusinessException("仅沟通发起人或上游沟通对象可收回");
                }
                Set<String> downstream = new HashSet<>();
                for (String ownId : ownRecordIds) {
                    CommChainRules.collectChildren(allTasks, ownId, downstream);
                }
                downstream.removeAll(ownRecordIds);

                // 传入的记录必须落在自己的下游里，避免借这个接口收回别人的分支
                for (String tid : commTaskIds) {
                    if (!downstream.contains(tid)) {
                        BizCommTask root = byId.get(tid);
                        if (root == null) {
                            throw new BusinessException("沟通记录不存在或不属于本次沟通");
                        }
                        throw new BusinessException("只能收回自己下游的沟通（"
                                + CommChainRules.commTargetLabel(root) + " 不在你的下游）");
                    }
                }
                boolean hasPendingDownstream = false;
                for (String id : downstream) {
                    BizCommTask d = byId.get(id);
                    if (d != null && BizCommTask.STATUS_PENDING.equals(d.getStatus())) {
                        hasPendingDownstream = true;
                        break;
                    }
                }
                if (!hasPendingDownstream) {
                    throw new BusinessException("你没有待反馈的下游沟通，请直接提交反馈");
                }
                toWithdraw.addAll(downstream);
            }

            int withdrawn = 0;
            Date withdrawnAt = new Date();
            for (String tid : toWithdraw) {
                BizCommTask ct = taskMgr.findById(tid);
                if (ct == null) continue;
                if (!BizCommTask.STATUS_PENDING.equals(ct.getStatus())) continue;
                ct.setStatus(BizCommTask.STATUS_WITHDRAWN);
                // 引擎历史会保留任务并把 DELETE_REASON_ 记为 deleted，但历史里没有
                // “谁在什么时候收回”的业务语义；收回时刻只能记在业务表上
                ct.setWithdrawTime(withdrawnAt);
                taskMgr.update(ct);
                withdrawn++;
                // 删除对应的Camunda沟通任务（cascade=true级联删除子任务）
                String camundaTaskId = ct.getCamundaTaskId();
                if (camundaTaskId != null) {
                    taskService.deleteTask(camundaTaskId, true);
                }
            }

            // 会话内仍有未处理（PENDING）的沟通任务时保持锁定：直接用内存快照判断
            boolean stillPending = false;
            for (BizCommTask t : allTasks) {
                if (toWithdraw.contains(t.getId())) continue;
                if (BizCommTask.STATUS_PENDING.equals(t.getStatus())) {
                    stillPending = true;
                    break;
                }
            }
            if (!stillPending) {
                session.setStatus(BizCommSession.STATUS_COMPLETED);
                session.setFinishTime(new Date());
                sessionMgr.update(session);

                BizTaskExt ext = extMgr.findByTaskId(session.getRootTaskId());
                if (ext != null) {
                    ext.setBizStatus(BizTaskExt.STATUS_NORMAL);
                    ext.setActiveSessionId(null);
                    ext.setUpdateTime(new Date());
                    extMgr.update(ext);
                }
            }
            return withdrawn;
        });
    }

    /**
     * 批量计算会话内每条记录当前用户能否收回（沟通反馈界面的“同轮反馈进度”直接用）。
     */
    public Map<String, Boolean> withdrawableRecordMap(String sessionId, String operator) {
        return getEngineConfig().getCommandExecutorTxRequired().execute(
                (Command<Map<String, Boolean>>) commandContext -> {
                    Map<String, Boolean> result = new HashMap<>();
                    BizCommSessionManager sessionMgr = commandContext.getSession(BizCommSessionManager.class);
                    BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);
                    BizCommSession session = sessionMgr.findById(sessionId);
                    if (session == null || !BizCommSession.STATUS_ACTIVE.equals(session.getStatus())) {
                        return result;
                    }
                    List<BizCommTask> allTasks = taskMgr.findBySessionId(sessionId);
                    Map<String, BizCommTask> byId = CommChainRules.indexById(allTasks);
                    for (BizCommTask t : allTasks) {
                        result.put(t.getId(),
                                CommChainRules.canWithdrawRecord(t, session, operator, byId));
                    }
                    return result;
                });
    }

    /**
     * 反馈沟通意见
     */
    public void replyCommTask(String commTaskRecordId, String userId, String replyContent) {
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        engineConfig.getCommandExecutorTxRequired().execute((Command<Void>) commandContext -> {
            BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);
            BizCommSessionManager sessionMgr = commandContext.getSession(BizCommSessionManager.class);
            BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);

            BizCommTask ct = taskMgr.findById(commTaskRecordId);
            if (ct == null) throw new BusinessException("沟通任务不存在");
            // 身份校验：只有该条沟通的接收人本人可反馈，杜绝代填/伪造反馈
            if (userId == null || !userId.equals(ct.getTargetId())) {
                throw new BusinessException("仅沟通对象本人可反馈该条沟通");
            }
            if (!BizCommTask.STATUS_PENDING.equals(ct.getStatus())) {
                throw new BusinessException("该沟通已处理或已收回");
            }

            // 先取会话快照，再基于快照做校验与判断（避免写后读）
            List<BizCommTask> allTasks = taskMgr.findBySessionId(ct.getSessionId());

            // 已经把问题转给别人（或追问）且对方还没反馈时，本条沟通不能先“完成反馈”：
            // 否则上级记录先办结、子沟通后续反馈就失去承接点，也会让人误以为整条链已闭环。
            BizCommTask pendingChild = CommChainRules.findPendingDescendant(ct.getId(), allTasks);
            if (pendingChild != null) {
                throw new BusinessException("还有未反馈的沟通（"
                        + CommChainRules.commTargetLabel(pendingChild)
                        + "），请等对方反馈，或由发起人/上游沟通对象收回后再提交反馈");
            }

            ct.setStatus(BizCommTask.STATUS_REPLIED);
            ct.setReplyContent(replyContent);
            ct.setReplyUserId(userId);
            ct.setReplyTime(new Date());
            taskMgr.update(ct);
            boolean stillPending = false;
            for (BizCommTask t : allTasks) {
                if (t.getId().equals(ct.getId())) continue;
                if (BizCommTask.STATUS_PENDING.equals(t.getStatus())) {
                    stillPending = true;
                    break;
                }
            }

            // 结束对应的 Camunda 旁路待办：反馈属于“办结”，必须 complete，不能 deleteTask。
            // deleteTask 只会把任务置为删除态（DELETE_REASON_ 有值），
            // 已办列表据此显示“已收回/删除”，且不会产生任何审批意见记录。
            String camundaTaskId = ct.getCamundaTaskId();
            if (camundaTaskId != null) {
                Task commTask = taskService.createTaskQuery().taskId(camundaTaskId).singleResult();
                if (commTask != null) {
                    // ① 先落审批意见：任务 complete 之后同一 taskId 已结束，
                    //    再 createComment 会失败；且意见必须挂在当前 taskId 上，
                    //    已办详情/流程图按 taskId 查评论才能一致显示。
                    String opinion = replyContent == null ? null : replyContent.trim();
                    if (!opinion.isEmpty()) {
                        // createComment 的第二个参数是流程实例ID（不是 userId）。
                        // 这里传旁路任务所属流程实例，意见才能按流程/任务双向查回。
                        taskService.createComment(camundaTaskId, commTask.getProcessInstanceId(), opinion);
                    }
                    // ② 办结任务：任务为 standalone（newTask 创建、无 executionId），
                    //    complete 只结束该旁路待办并写入历史（DELETE_REASON_ 为 null），
                    //    不会推进主流程，也不会影响源待办。
                    taskService.complete(camundaTaskId);
                }
            }

            // 会话内没有待处理沟通任务时（含多轮子沟通）才解锁主待办
            if (!stillPending) {
                BizCommSession session = sessionMgr.findById(ct.getSessionId());
                if (session != null) {
                    session.setStatus(BizCommSession.STATUS_COMPLETED);
                    session.setFinishTime(new Date());
                    sessionMgr.update(session);

                    BizTaskExt ext = extMgr.findByTaskId(session.getRootTaskId());
                    if (ext != null) {
                        ext.setBizStatus(BizTaskExt.STATUS_NORMAL);
                        ext.setActiveSessionId(null);
                        ext.setUpdateTime(new Date());
                        extMgr.update(ext);
                    }
                }
            }
            return null;
        });
    }
}
