package com.example.camundademo.entity;

import org.camunda.bpm.engine.impl.db.DbEntity;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 沟通会话实体
 */
public class BizCommSession implements DbEntity, Serializable {
    private static final long serialVersionUID = 1L;

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    protected String id;
    protected String rootTaskId;
    protected String initiator;
    protected String initialContent;
    protected String status;
    protected Integer currentRound;
    protected Date createTime;
    protected Date finishTime;

    @Override
    public Object getPersistentState() {
        Map<String, Object> state = new HashMap<>();
        state.put("status", status);
        state.put("currentRound", currentRound);
        state.put("finishTime", finishTime);
        return state;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRootTaskId() { return rootTaskId; }
    public void setRootTaskId(String rootTaskId) { this.rootTaskId = rootTaskId; }
    public String getInitiator() { return initiator; }
    public void setInitiator(String initiator) { this.initiator = initiator; }
    public String getInitialContent() { return initialContent; }
    public void setInitialContent(String initialContent) { this.initialContent = initialContent; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getCurrentRound() { return currentRound; }
    public void setCurrentRound(Integer currentRound) { this.currentRound = currentRound; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getFinishTime() { return finishTime; }
    public void setFinishTime(Date finishTime) { this.finishTime = finishTime; }
}
