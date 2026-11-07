package com.example.camundademo.listener;

import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.persistence.BizCommTaskManager;
import com.example.camundademo.service.DeptCommProcessService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;

/**
 * 主流程结束时的级联终止：把挂在它名下的部门沟通子流程一并结束。
 *
 * <p>部门沟通是独立启动的流程实例，引擎不会因为父流程结束而自动清理；
 * 不主动终止就会留下“主单子都没了，部门还在收到待办”的孤儿流程。
 * 本监听器由 {@link com.example.camundademo.config.GlobalProcessParseListener}
 * 注入到每个流程实例的 end 事件，模型文件零改动。</p>
 *
 * <p><b>绝不能让级联失败影响主流程结束</b>：主流程正常办结是首要事实，
 * 因此异常在这里全部吞掉并记日志，不向上抛。</p>
 */
public class DeptCommCascadeListener implements ExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(DeptCommCascadeListener.class);

    @Override
    public void notify(DelegateExecution execution) {
        try {
            apply(execution);
        } catch (RuntimeException e) {
            log.error("主流程结束时级联终止部门沟通失败：pid={}",
                    execution == null ? null : execution.getProcessInstanceId(), e);
        }
    }

    private void apply(DelegateExecution execution) {
        String parentProcessInstanceId = execution.getProcessInstanceId();
        CommandContext commandContext = Context.getCommandContext();
        if (commandContext == null) {
            return;
        }
        RuntimeService runtimeService = Context.getProcessEngineConfiguration().getRuntimeService();
        List<ProcessInstance> children = runtimeService.createProcessInstanceQuery()
                .processDefinitionKey(DeptCommProcessService.PROCESS_KEY)
                .variableValueEquals(DeptCommProcessService.VAR_PARENT_PROC_INST_ID, parentProcessInstanceId)
                .list();
        if (children.isEmpty()) {
            return;
        }

        BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);
        Date now = new Date();
        for (ProcessInstance child : children) {
            Object recordId = runtimeService.getVariable(child.getId(),
                    DeptCommProcessService.VAR_COMM_RECORD_ID);
            if (recordId != null) {
                BizCommTask record = taskMgr.findById(String.valueOf(recordId));
                if (record != null && BizCommTask.STATUS_PENDING.equals(record.getStatus())) {
                    // 主流程都结束了，部门记录不能再显示“等待反馈”，按已收回收尾
                    record.setStatus(BizCommTask.STATUS_WITHDRAWN);
                    record.setWithdrawTime(now);
                    taskMgr.update(record);
                }
            }
            runtimeService.deleteProcessInstance(child.getId(), "主流程已结束，级联终止部门沟通");
            log.info("级联终止部门沟通子流程：parent={}, child={}, record={}",
                    parentProcessInstanceId, child.getId(), recordId);
        }
    }
}
