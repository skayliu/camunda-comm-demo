package com.example.camundademo.listener;

import com.example.camundademo.persistence.BizCcRecordManager;
import com.example.camundademo.persistence.BizHandleRecordManager;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

/**
 * 流程结束时的旁路数据收尾。
 *
 * <ul>
 *   <li>未完成的阅办：一律标记失效。流程都结束了，还挂着“待承办”只会让用户点进去发现单子已归档。</li>
 *   <li>未读传阅：仅当流程被驳回/撤销（存在删除原因）时失效；正常结束的传阅继续保留，
 *       用户仍可在“我的待阅”里查到这份副本。</li>
 * </ul>
 *
 * <p>已完成/已读的记录不动，审计需要保留“他确实办过/看过”的痕迹。</p>
 */
public class CcProcessEndListener implements ExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(CcProcessEndListener.class);

    @Override
    public void notify(DelegateExecution execution) {
        CommandContext commandContext = Context.getCommandContext();
        if (commandContext == null || execution == null) {
            return;
        }
        String processInstanceId = execution.getProcessInstanceId();
        Date now = new Date();
        try {
            BizHandleRecordManager handleManager = commandContext.getSession(BizHandleRecordManager.class);
            int invalidHandles = handleManager.invalidateByProcInst(processInstanceId, now);
            if (invalidHandles > 0) {
                log.info("流程结束，未完成阅办失效：process={}, count={}", processInstanceId, invalidHandles);
            }

            String deleteReason = deleteReason(execution);
            if (deleteReason != null && !deleteReason.isBlank()) {
                BizCcRecordManager ccManager = commandContext.getSession(BizCcRecordManager.class);
                int invalidCc = ccManager.invalidateByProcInst(processInstanceId, now);
                if (invalidCc > 0) {
                    log.info("流程被撤销/驳回，未读传阅失效：process={}, reason={}, count={}",
                            processInstanceId, deleteReason, invalidCc);
                }
            }
        } catch (RuntimeException e) {
            // 收尾失败不能阻断流程结束本身
            log.error("流程结束旁路数据收尾失败：process={}", processInstanceId, e);
        }
    }

    private String deleteReason(DelegateExecution execution) {
        if (execution instanceof ExecutionEntity) {
            return ((ExecutionEntity) execution).getDeleteReason();
        }
        return null;
    }
}
