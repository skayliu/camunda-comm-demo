package com.example.camundademo.entity;

import org.camunda.bpm.engine.impl.db.DbEntity;

import java.io.Serializable;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 待办转办记录。
 *
 * <p>转办不新增流程节点、不改 BPMN，只是把 {@code ACT_RU_TASK.ASSIGNEE_} 换成另一个人。
 * 但引擎只保留「当前办理人」这一个值，转给谁、谁转的、为什么转都会丢，
 * 因此单独落一张业务表，用于待办列表标识、待办详情与流程跟踪的追溯。</p>
 *
 * <p>记录只追加不更新：同一待办多次转办会产生多条记录，按 {@code CREATE_TIME_} 升序即为转办链。</p>
 */
public class BizTaskTransfer implements DbEntity, Serializable {

    private static final long serialVersionUID = 1L;

    protected String id;
    protected String taskId;
    protected String processInstanceId;
    protected String taskDefKey;
    protected String taskName;
    protected String fromUserId;
    protected String toUserId;
    protected String reason;
    protected Date createTime;

    /**
     * 转办记录写入后不再变更，这里只声明可变字段，
     * 避免 Camunda 在命令收尾时产生无意义的 UPDATE。
     */
    @Override
    public Object getPersistentState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("reason", reason);
        return state;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }
    public String getTaskDefKey() { return taskDefKey; }
    public void setTaskDefKey(String taskDefKey) { this.taskDefKey = taskDefKey; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public String getFromUserId() { return fromUserId; }
    public void setFromUserId(String fromUserId) { this.fromUserId = fromUserId; }
    public String getToUserId() { return toUserId; }
    public void setToUserId(String toUserId) { this.toUserId = toUserId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
