package com.example.camundademo.entity;

import org.camunda.bpm.engine.impl.db.DbEntity;

import java.io.Serializable;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 委托执行记录（含代理自动委托）。
 *
 * <p>委托用的是 Camunda 原生的 {@code delegateTask / resolveTask}：
 * ASSIGNEE_ 变成受托人、OWNER_ 保留原办理人、DELEGATION_STATE_ 变成 PENDING。
 * 引擎只保留「当前状态」，两次委托之间、以及谁为什么委托，都会丢，
 * 因此单独落一张业务表。</p>
 *
 * <p>一条记录对应一次委托生命周期：PENDING → RESOLVED（受托人交回）
 * 或 PENDING → CANCELED（委托人主动收回），以及 SIGN 模式下的
 * PENDING → COMPLETED（受托人直接办结）。同一任务可以有多条记录
 * （先委托 A、A 交回后再委托 B）。</p>
 *
 * <p>{@code source} 区分人工委托与代理规则自动委托，{@code agentRuleId} 指向触发的代理规则，
 * 这样「代理出去的任务」在审计时能和「手工委托」分开统计。</p>
 */
public class BizTaskDelegate implements DbEntity, Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_CANCELED = "CANCELED";
    /** 代理人以「可直接办结」方式审批完成，流程已按其结果继续流转 */
    public static final String STATUS_COMPLETED = "COMPLETED";

    public static final String MODE_CONFIRM = "CONFIRM";
    public static final String MODE_SIGN = "SIGN";

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_AGENT = "AGENT";

    protected String id;
    protected String taskId;
    protected String processInstanceId;
    protected String taskDefKey;
    protected String taskName;
    /** 委托人 = 委托发生时的原办理人（引擎侧 OWNER_） */
    protected String ownerUserId;
    /** 受托人 = 委托发生时的实际办理人（引擎侧 ASSIGNEE_） */
    protected String delegateUserId;
    /** MANUAL / AGENT */
    protected String source;
    /** CONFIRM 需委托人确认 / SIGN 受托人可直接办结；记录本次委托实际采用的方式 */
    protected String delegateMode = MODE_CONFIRM;
    /** 代理自动委托时命中的规则 ID，人工委托为 null */
    protected String agentRuleId;
    protected String reason;
    protected String status;
    /** 受托人交回时填写的代办意见，与最终审批意见分开存储 */
    protected String resolveContent;
    protected Date resolveTime;
    protected Date createTime;
    protected Date updateTime;

    /**
     * 只有状态相关字段会变更，其余字段写入后不再修改。
     * 若把不可变字段也放进来，命令收尾时会产生大量无意义的 UPDATE。
     */
    @Override
    public Object getPersistentState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", status);
        state.put("resolveContent", resolveContent);
        state.put("resolveTime", resolveTime);
        state.put("updateTime", updateTime);
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
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getDelegateUserId() { return delegateUserId; }
    public void setDelegateUserId(String delegateUserId) { this.delegateUserId = delegateUserId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getDelegateMode() { return delegateMode; }
    public void setDelegateMode(String delegateMode) { this.delegateMode = delegateMode; }
    public String getAgentRuleId() { return agentRuleId; }
    public void setAgentRuleId(String agentRuleId) { this.agentRuleId = agentRuleId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResolveContent() { return resolveContent; }
    public void setResolveContent(String resolveContent) { this.resolveContent = resolveContent; }
    public Date getResolveTime() { return resolveTime; }
    public void setResolveTime(Date resolveTime) { this.resolveTime = resolveTime; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
