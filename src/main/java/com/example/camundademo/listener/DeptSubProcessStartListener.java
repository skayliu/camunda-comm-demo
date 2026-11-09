package com.example.camundademo.listener;

import com.example.camundademo.service.BusinessException;
import com.example.camundademo.service.DeptCommProcessService;
import com.example.camundademo.service.SubProcessDefService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;

/**
 * 部门沟通子流程的统一启动监听器。
 *
 * <p>所有部门子流程（通用三节点、法务、财务……）的开始事件都绑定本类，
 * 保证三件事只写一遍：</p>
 * <ol>
 *   <li>校验统一入参（主流程实例ID、主待办ID、沟通记录ID、部门ID）；</li>
 *   <li>解析部门文书（{@code clerkUserId}），子流程内部不必各自实现；</li>
 *   <li>锁定主流程待办，沟通期间禁止审批/转办。</li>
 * </ol>
 */
@Component("deptSubProcessStartListener")
public class DeptSubProcessStartListener implements ExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(DeptSubProcessStartListener.class);

    @Autowired
    private TaskService taskService;

    @Autowired
    private SubProcessDefService subProcessDefService;

    @Override
    public void notify(DelegateExecution execution) {
        String sourceProcInstId = asString(execution.getVariable(DeptCommProcessService.VAR_PARENT_PROC_INST_ID));
        String sourceTaskId = asString(execution.getVariable(DeptCommProcessService.VAR_SOURCE_TASK_ID));
        String recordId = asString(execution.getVariable(DeptCommProcessService.VAR_COMM_RECORD_ID));
        String deptId = asString(execution.getVariable(DeptCommProcessService.VAR_DEPT_ID));

        if (sourceProcInstId == null || sourceTaskId == null || recordId == null || deptId == null) {
            // 统一契约被破坏时必须立刻失败：否则子流程会跑到最后却没有可回写的主待办
            throw new BusinessException("部门子流程缺少统一入参：sourceProcInstId / sourceTaskId / commRecordId / deptId");
        }

        String clerkUserId = asString(execution.getVariable("clerkUserId"));
        if (clerkUserId == null || clerkUserId.isBlank()) {
            clerkUserId = subProcessDefService.resolveClerk(deptId);
            execution.setVariable("clerkUserId", clerkUserId);
        }

        // 经办人意见容器：通用流程由经办节点写入，专属流程按节点 key 写入
        if (execution.getVariable("opinionMap") == null) {
            execution.setVariable("opinionMap", new HashMap<String, Object>());
        }

        // 锁主待办，并记录子流程实例ID，便于主流程侧排查
        taskService.setVariable(sourceTaskId, "commLocked", true);
        taskService.setVariable(sourceTaskId, "commSubProcInstId", execution.getProcessInstanceId());

        log.info("部门子流程启动：key={}, dept={}, clerk={}, record={}, pid={}",
                execution.getProcessDefinitionId(), deptId, clerkUserId, recordId,
                execution.getProcessInstanceId());
    }

    private String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
