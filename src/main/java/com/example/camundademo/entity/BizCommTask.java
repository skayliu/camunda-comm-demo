package com.example.camundademo.entity;

import org.camunda.bpm.engine.impl.db.DbEntity;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 沟通任务实体（人/部门、多轮、子沟通）
 */
public class BizCommTask implements DbEntity, Serializable {
    private static final long serialVersionUID = 1L;

    public static final String TARGET_USER = "USER";
    public static final String TARGET_DEPT = "DEPT";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_REPLIED = "REPLIED";
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";

    protected String id;
    protected String sessionId;
    protected String parentCommTaskId;
    protected String sourceTaskId;
    protected String rootTaskId;
    protected String targetType;
    protected String targetId;
    protected String targetName;
    protected String content;
    protected String status;
    protected String replyContent;
    protected String replyUserId;
    protected Date replyTime;
    protected String deptProcInstId;
    protected Date createTime;

    @Override
    public Object getPersistentState() {
        Map<String, Object> state = new HashMap<>();
        state.put("status", status);
        state.put("replyContent", replyContent);
        state.put("replyUserId", replyUserId);
        state.put("replyTime", replyTime);
        return state;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getParentCommTaskId() { return parentCommTaskId; }
    public void setParentCommTaskId(String parentCommTaskId) { this.parentCommTaskId = parentCommTaskId; }
    public String getSourceTaskId() { return sourceTaskId; }
    public void setSourceTaskId(String sourceTaskId) { this.sourceTaskId = sourceTaskId; }
    public String getRootTaskId() { return rootTaskId; }
    public void setRootTaskId(String rootTaskId) { this.rootTaskId = rootTaskId; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReplyContent() { return replyContent; }
    public void setReplyContent(String replyContent) { this.replyContent = replyContent; }
    public String getReplyUserId() { return replyUserId; }
    public void setReplyUserId(String replyUserId) { this.replyUserId = replyUserId; }
    public Date getReplyTime() { return replyTime; }
    public void setReplyTime(Date replyTime) { this.replyTime = replyTime; }
    public String getDeptProcInstId() { return deptProcInstId; }
    public void setDeptProcInstId(String deptProcInstId) { this.deptProcInstId = deptProcInstId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
