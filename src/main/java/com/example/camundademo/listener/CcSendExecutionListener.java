package com.example.camundademo.listener;

import com.example.camundademo.entity.BizCcRecord;
import com.example.camundademo.persistence.BizCcRecordManager;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 静态传阅发送监听器：在节点结束或流程结束时写入传阅记录。
 *
 * <p>由 {@link com.example.camundademo.config.GlobalCcParseListener} 在 BPMN 解析期挂载，
 * 监听器实例不是 Spring Bean，因此这里直接从当前 Camunda 命令上下文取
 * {@link BizCcRecordManager}，与流程推进共用同一个事务。</p>
 *
 * <p><b>不创建任何引擎任务</b>：待阅人看不看都不影响流程。</p>
 */
public class CcSendExecutionListener implements ExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(CcSendExecutionListener.class);

    private final String ccUsers;
    private final String ccType;

    public CcSendExecutionListener(String ccUsers, String ccType) {
        this.ccUsers = ccUsers;
        this.ccType = ccType == null || ccType.isBlank() ? BizCcRecord.TYPE_NODE : ccType;
    }

    @Override
    public void notify(DelegateExecution execution) {
        CommandContext commandContext = Context.getCommandContext();
        if (commandContext == null || execution == null) {
            // 理论上不会发生；裸跑解析器单测时允许静默跳过
            return;
        }
        Set<String> users = parseUsers(ccUsers);
        if (users.isEmpty()) {
            return;
        }
        try {
            BizCcRecordManager manager = commandContext.getSession(BizCcRecordManager.class);
            String processInstanceId = execution.getProcessInstanceId();
            String title = resolveTitle(execution);
            String sender = resolveSender(execution);
            String processDefinitionKey = processDefinitionKey(execution.getProcessDefinitionId());
            Date now = new Date();
            int created = 0;
            for (String userId : users) {
                // 同一条单子不重复传阅同一个人，避免重复通知
                if (manager.countActive(processInstanceId, userId) > 0) {
                    continue;
                }
                BizCcRecord record = new BizCcRecord();
                record.setId(java.util.UUID.randomUUID().toString().replace("-", ""));
                record.setProcessInstanceId(processInstanceId);
                record.setTaskId(null);
                record.setProcessDefinitionKey(processDefinitionKey);
                record.setTaskDefinitionKey(execution.getCurrentActivityId());
                record.setTitle(title);
                record.setCcUserId(userId);
                record.setSenderUserId(sender);
                record.setCcType(ccType);
                record.setRead(0);
                record.setRevoked(0);
                record.setCreateTime(now);
                record.setUpdateTime(now);
                manager.insert(record);
                created++;
            }
            log.info("静态传阅已发送：process={}, node={}, type={}, created={}",
                    processInstanceId, execution.getCurrentActivityId(), ccType, created);
        } catch (RuntimeException e) {
            // 传阅是旁路能力：不能因为传阅写库失败把流程推进一起回滚
            log.error("静态传阅写入失败：process={}, node={}",
                    execution.getProcessInstanceId(), execution.getCurrentActivityId(), e);
        }
    }

    private Set<String> parseUsers(String raw) {
        Set<String> users = new LinkedHashSet<>();
        if (raw == null) {
            return users;
        }
        for (String part : raw.split("[,;，；]")) {
            String user = part.trim();
            if (!user.isEmpty()) {
                users.add(user);
            }
        }
        return users;
    }

    /**
     * 传阅标题优先取流程变量 title，其次业务键，最后退化为流程 key。
     * 变量在流程结束时可能已被清理，因此每一层都单独兜底。
     */
    private String resolveTitle(DelegateExecution execution) {
        try {
            Object title = execution.getVariable("title");
            if (title != null && !String.valueOf(title).isBlank()) {
                return String.valueOf(title);
            }
        } catch (RuntimeException ignored) {
            // 变量作用域已销毁，继续走兜底
        }
        try {
            if (execution instanceof org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity entity) {
                String businessKey = entity.getBusinessKey();
                if (businessKey != null && !businessKey.isBlank()) {
                    return businessKey;
                }
            }
        } catch (RuntimeException ignored) {
            // 同上
        }
        String key = processDefinitionKey(execution.getProcessDefinitionId());
        return key == null ? "流程传阅" : "流程传阅 · " + key;
    }

    /** 发送人：优先取申请人变量（完成节点的人通常就是审批人，业务上等价于发起人） */
    private String resolveSender(DelegateExecution execution) {
        try {
            Object applicant = execution.getVariable("applicant");
            if (applicant != null && !String.valueOf(applicant).isBlank()) {
                return String.valueOf(applicant);
            }
        } catch (RuntimeException ignored) {
            // 变量已被清理
        }
        return "system";
    }

    private String processDefinitionKey(String processDefinitionId) {
        return processDefinitionId == null ? null : processDefinitionId.split(":")[0];
    }
}
