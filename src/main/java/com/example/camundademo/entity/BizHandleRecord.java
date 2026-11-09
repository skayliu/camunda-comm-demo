package com.example.camundademo.entity;

import org.camunda.bpm.engine.impl.db.DbEntity;

import java.io.Serializable;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阅办（承办）记录。
 *
 * <p>阅办和传阅的本质区别：传阅只是“让他知道”，阅办是“让他做，但不拍板”。
 * 因此阅办有办理时限、办理结果与逾期状态，但同样<b>不创建引擎任务、不阻塞流程</b>，
 * 办理结果只落业务表，不写流程变量、不参与引擎决策。</p>
 */
public class BizHandleRecord implements DbEntity, Serializable {

    private static final long serialVersionUID = 1L;

    /** 待阅：已送达，尚未开始办理 */
    public static final String STATUS_PENDING = "PENDING";
    /** 办理中：承办人已打开/开始办理 */
    public static final String STATUS_HANDLING = "HANDLING";
    /** 已完成：已反馈办理结果 */
    public static final String STATUS_DONE = "DONE";
    /** 逾期：超过办理时限仍未完成（由定时任务自动标记） */
    public static final String STATUS_OVERDUE = "OVERDUE";

    /** 发起人主动撤回 */
    public static final String REVOKE_REASON_REVOKED = "REVOKED";
    /** 流程驳回/撤回/结束导致未完成阅办失效 */
    public static final String REVOKE_REASON_INVALID = "INVALID";

    protected String id;
    protected String processInstanceId;
    /** 来源待办ID */
    protected String taskId;
    protected String processDefinitionKey;
    protected String taskDefinitionKey;
    protected String title;
    protected String assigneeId;
    protected String senderId;
    /** 办理要求 / 批示内容 */
    protected String instruction;
    /** 办理时限，可为空（不限期） */
    protected Date deadline;
    protected String handleStatus = STATUS_PENDING;
    /** 办理结果反馈 */
    protected String handleResult;
    protected Date handleTime;
    /** 0 正常 / 1 已撤回或已失效 */
    protected Integer revoked;
    protected Date revokeTime;
    protected String revokeReason;
    protected Date createTime;
    protected Date updateTime;

    @Override
    public Object getPersistentState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("handleStatus", handleStatus);
        state.put("handleResult", handleResult);
        state.put("handleTime", handleTime);
        state.put("revoked", revoked);
        state.put("revokeTime", revokeTime);
        state.put("revokeReason", revokeReason);
        state.put("updateTime", updateTime);
        return state;
    }

    public boolean isFinished() {
        return STATUS_DONE.equals(handleStatus);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getProcessDefinitionKey() { return processDefinitionKey; }
    public void setProcessDefinitionKey(String processDefinitionKey) { this.processDefinitionKey = processDefinitionKey; }
    public String getTaskDefinitionKey() { return taskDefinitionKey; }
    public void setTaskDefinitionKey(String taskDefinitionKey) { this.taskDefinitionKey = taskDefinitionKey; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAssigneeId() { return assigneeId; }
    public void setAssigneeId(String assigneeId) { this.assigneeId = assigneeId; }
    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public String getInstruction() { return instruction; }
    public void setInstruction(String instruction) { this.instruction = instruction; }
    public Date getDeadline() { return deadline; }
    public void setDeadline(Date deadline) { this.deadline = deadline; }
    public String getHandleStatus() { return handleStatus; }
    public void setHandleStatus(String handleStatus) { this.handleStatus = handleStatus; }
    public String getHandleResult() { return handleResult; }
    public void setHandleResult(String handleResult) { this.handleResult = handleResult; }
    public Date getHandleTime() { return handleTime; }
    public void setHandleTime(Date handleTime) { this.handleTime = handleTime; }
    public Integer getRevoked() { return revoked; }
    public void setRevoked(Integer revoked) { this.revoked = revoked; }
    public Date getRevokeTime() { return revokeTime; }
    public void setRevokeTime(Date revokeTime) { this.revokeTime = revokeTime; }
    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String revokeReason) { this.revokeReason = revokeReason; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
