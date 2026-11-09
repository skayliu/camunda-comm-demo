package com.example.camundademo.entity;

import org.camunda.bpm.engine.impl.db.DbEntity;

import java.io.Serializable;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 传阅记录（一条记录 = 一个人收到的一次传阅）。
 *
 * <p><b>为什么不是 userTask</b>：待阅人不需要做任何决策，流程也不等他。
 * 用 userTask 实现会让流程卡在传阅节点、污染多实例计数和待办/已办列表。
 * 因此传阅只作为业务旁路数据存在，完全不进引擎执行链。</p>
 *
 * <p>已读只是状态标记，不是任务终结：标记已读只更新本表，
 * <b>绝不会</b>调用 {@code taskService.complete()}。</p>
 */
public class BizCcRecord implements DbEntity, Serializable {

    private static final long serialVersionUID = 1L;

    /** 节点传阅：某个审批节点结束时自动传阅 */
    public static final String TYPE_NODE = "NODE";
    /** 流程传阅：整个流程结束时传阅存档 */
    public static final String TYPE_INST = "INST";
    /** 手动传阅：审批过程中由审批人/发起人临时添加 */
    public static final String TYPE_MANUAL = "MANUAL";

    /** 发起人主动撤回 */
    public static final String REVOKE_REASON_REVOKED = "REVOKED";
    /** 流程驳回/撤回导致传阅失效 */
    public static final String REVOKE_REASON_INVALID = "INVALID";

    protected String id;
    protected String processInstanceId;
    /** 来源待办ID：节点/手动传阅有值，流程结束时发起的传阅为空 */
    protected String taskId;
    protected String processDefinitionKey;
    protected String taskDefinitionKey;
    protected String title;
    protected String ccUserId;
    protected String senderUserId;
    protected String ccType = TYPE_NODE;
    /** 传阅附言：发起人随传阅送达的一句说明，只读、待阅人不能回复，最长 200 字 */
    protected String note;
    /** 0 未读 / 1 已读 */
    protected Integer read;
    protected Date readTime;
    /** 0 正常 / 1 已撤回或已失效（软删除，保留审计痕迹） */
    protected Integer revoked;
    protected Date revokeTime;
    /** REVOKED 主动撤回 / INVALID 因流程驳回、撤回或结束而失效 */
    protected String revokeReason;
    protected Date createTime;
    protected Date updateTime;

    @Override
    public Object getPersistentState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("read", read);
        state.put("readTime", readTime);
        state.put("revoked", revoked);
        state.put("revokeTime", revokeTime);
        state.put("revokeReason", revokeReason);
        state.put("updateTime", updateTime);
        return state;
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
    public String getCcUserId() { return ccUserId; }
    public void setCcUserId(String ccUserId) { this.ccUserId = ccUserId; }
    public String getSenderUserId() { return senderUserId; }
    public void setSenderUserId(String senderUserId) { this.senderUserId = senderUserId; }
    public String getCcType() { return ccType; }
    public void setCcType(String ccType) { this.ccType = ccType; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Integer getRead() { return read; }
    public void setRead(Integer read) { this.read = read; }
    public Date getReadTime() { return readTime; }
    public void setReadTime(Date readTime) { this.readTime = readTime; }
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
