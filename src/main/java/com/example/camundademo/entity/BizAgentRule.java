package com.example.camundademo.entity;

import org.camunda.bpm.engine.impl.db.DbEntity;

import java.io.Serializable;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 代理规则：某人在某段时间内，把自己名下（可按流程范围限定）的待办自动委托给代理人。
 *
 * <p>与转办/委托的区别：它不是针对某一张任务的操作，而是<b>只对新创建的任务生效</b>。
 * 到期后新任务不再自动代理，已经代理出去的任务保持在代理人手里——
 * 不做批量改回，否则代理人处理到一半的任务会突然消失。</p>
 *
 * <p>{@code processDefinitionKeys} 为逗号分隔的流程定义 key，空/NULL 表示全部流程。</p>
 *
 * <p><b>{@code delegateMode} 决定代理出去的待办归谁处置</b>：
 * {@code CONFIRM} 走引擎原生委托（{@code delegateTask}，ASSIGNEE_ = 代理人、OWNER_ = 本人、
 * DELEGATION_STATE_ = PENDING），代理人只能提交反馈再交回，本人确认后流程才继续；
 * {@code SIGN} 走改派（{@code setAssignee}，任务直接归代理人），代理人可正常审批办结，
 * 适用于“出差休假期间所有待办全权交给张三”的场景。</p>
 */
public class BizAgentRule implements DbEntity, Serializable {

    private static final long serialVersionUID = 1L;

    /** 受托人只能提交反馈，由委托人确认办结（引擎原生委托语义） */
    public static final String MODE_CONFIRM = "CONFIRM";
    /** 受托人可直接办结，流程按其审批结果继续流转（改派语义） */
    public static final String MODE_SIGN = "SIGN";

    protected String id;
    protected String ownerUserId;
    protected String agentUserId;
    protected Date startTime;
    protected Date endTime;
    /** 逗号分隔的流程定义 key；空表示全部 */
    protected String processDefinitionKeys;
    /** 1 生效 / 0 停用。用整型而不是布尔，7 种方言的映射更一致 */
    protected Integer enabled;
    /** CONFIRM 需本人确认 / SIGN 代理人可直接办结 */
    protected String delegateMode = MODE_CONFIRM;
    protected Date createTime;
    protected Date updateTime;

    @Override
    public Object getPersistentState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("agentUserId", agentUserId);
        state.put("startTime", startTime);
        state.put("endTime", endTime);
        state.put("processDefinitionKeys", processDefinitionKeys);
        state.put("enabled", enabled);
        state.put("delegateMode", delegateMode);
        state.put("updateTime", updateTime);
        return state;
    }

    /**
     * 注意不能叫 {@code isEnabled()}：它和 {@code getEnabled()} 会构成同一属性的重载 getter，
     * MyBatis 反射时报 "Illegal overloaded getter method with ambiguous type for property 'enabled'"。
     */
    public boolean isActive() {
        return enabled != null && enabled == 1;
    }

    /**
     * 是否“代理人可直接办结”。
     *
     * <p>存量数据与人工委托都不带这个字段，缺省按 CONFIRM 处理：
     * 责任默认留在委托人身上，只有显式选择了 SIGN 才让出处置权。</p>
     */
    public boolean isSignMode() {
        return MODE_SIGN.equals(delegateMode);
    }

    /** 规则是否对给定流程定义 key 生效（空列表视为全部流程） */
    public boolean coversProcess(String processDefinitionKey) {
        if (processDefinitionKeys == null || processDefinitionKeys.isBlank()) {
            return true;
        }
        if (processDefinitionKey == null) {
            return false;
        }
        for (String key : processDefinitionKeys.split(",")) {
            if (key.trim().equals(processDefinitionKey)) {
                return true;
            }
        }
        return false;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getAgentUserId() { return agentUserId; }
    public void setAgentUserId(String agentUserId) { this.agentUserId = agentUserId; }
    public Date getStartTime() { return startTime; }
    public void setStartTime(Date startTime) { this.startTime = startTime; }
    public Date getEndTime() { return endTime; }
    public void setEndTime(Date endTime) { this.endTime = endTime; }
    public String getProcessDefinitionKeys() { return processDefinitionKeys; }
    public void setProcessDefinitionKeys(String processDefinitionKeys) { this.processDefinitionKeys = processDefinitionKeys; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public String getDelegateMode() { return delegateMode; }
    public void setDelegateMode(String delegateMode) { this.delegateMode = delegateMode; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
