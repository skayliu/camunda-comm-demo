package com.example.camundademo.listener;

import com.example.camundademo.service.DeptCommProcessService;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 部门沟通子流程的统一结束监听器。
 *
 * <p>所有部门子流程的结束事件都绑定本类，负责三件事：</p>
 * <ol>
 *   <li>把 {@code finalOpinion} 回写到沟通记录；</li>
 *   <li>会话内没有其它待反馈记录时解锁主流程待办；</li>
 *   <li>清掉主待办上「沟通中」的标记。</li>
 * </ol>
 *
 * <p>回写逻辑与旧的单节点部门流程共用 {@link DeptCommReplyListener}，
 * 避免两套实现各自演化出不同口径（例如一边解锁、一边没解锁）。</p>
 */
@Component("deptSubProcessEndListener")
public class DeptSubProcessEndListener implements ExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(DeptSubProcessEndListener.class);

    @Autowired
    private DeptCommReplyListener replyListener;

    @Autowired
    private org.camunda.bpm.engine.TaskService taskService;

    @Override
    public void notify(DelegateExecution execution) {
        String recordId = asString(execution.getVariable(DeptCommProcessService.VAR_COMM_RECORD_ID));
        String sourceTaskId = asString(execution.getVariable(DeptCommProcessService.VAR_SOURCE_TASK_ID));
        String finalOpinion = asString(execution.getVariable("finalOpinion"));
        String clerkUserId = asString(execution.getVariable("clerkUserId"));

        if (recordId == null) {
            log.warn("部门子流程结束时缺少 commRecordId，跳过回写：pid={}", execution.getProcessInstanceId());
            return;
        }

        CommandContext commandContext = Context.getCommandContext();
        if (commandContext != null) {
            replyListener.writeBack(commandContext, recordId, clerkUserId, finalOpinion);
        } else {
            // 监听器总在命令内执行；兜底只为了防止将来被别的入口调用时静默丢结果
            replyListener.writeBackOutsideCommand(recordId, clerkUserId, finalOpinion);
        }

        // 解锁主待办：writeBack 只处理业务表与会话，引擎任务上的锁标记要在这里清掉
        if (sourceTaskId != null) {
            try {
                taskService.removeVariable(sourceTaskId, "commLocked");
                taskService.removeVariable(sourceTaskId, "commSubProcInstId");
            } catch (RuntimeException e) {
                // 主待办已结束/已删除时无需解锁，不能让收尾动作失败
                log.debug("清理主待办沟通锁失败（可能已结束）：{}", sourceTaskId, e);
            }
        }
        log.info("部门子流程结束：record={}, pid={}", recordId, execution.getProcessInstanceId());
    }

    private String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
