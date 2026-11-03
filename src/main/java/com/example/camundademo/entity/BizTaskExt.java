package com.example.camundademo.entity;

import org.camunda.bpm.engine.impl.db.DbEntity;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 主流程待办业务扩展表，用于存沟通状态（不修改ACT_RU_TASK）
 */
public class BizTaskExt implements DbEntity, Serializable {
    private static final long serialVersionUID = 1L;

    public static final String STATUS_NORMAL = "NORMAL";
    public static final String STATUS_COMMUNICATING = "COMMUNICATING";

    protected String id;
    protected String taskId;
    protected String processInstanceId;
    protected String assignee;
    protected String bizStatus;
    protected String activeSessionId;
    protected Date createTime;
    protected Date updateTime;

    @Override
    public Object getPersistentState() {
        Map<String, Object> state = new HashMap<>();
        state.put("bizStatus", bizStatus);
        state.put("activeSessionId", activeSessionId);
        state.put("updateTime", updateTime);
        return state;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }
    public String getAssignee() { return assignee; }
    public void setAssignee(String assignee) { this.assignee = assignee; }
    public String getBizStatus() { return bizStatus; }
    public void setBizStatus(String bizStatus) { this.bizStatus = bizStatus; }
    public String getActiveSessionId() { return activeSessionId; }
    public void setActiveSessionId(String activeSessionId) { this.activeSessionId = activeSessionId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
