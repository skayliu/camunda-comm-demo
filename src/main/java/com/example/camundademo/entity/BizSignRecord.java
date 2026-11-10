package com.example.camundademo.entity;

import org.camunda.bpm.engine.impl.db.DbEntity;

import java.io.Serializable;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 加签记录（前加签 / 后加签 / 并加签）。
 *
 * <p>加签<b>不是</b>往 ACT_RU_TASK 插一条待办，而是通过引擎 API 增加活动实例：
 * 并加签用 {@code ProcessInstanceModification.startBeforeActivity} 在当前多实例节点扩容，
 * 前/后加签另起一个加签子流程（独立流程实例，按 {@code SIGN_ROUND_ID_} 归组）。</p>
 *
 * <p>本表只记录“谁在哪个任务上加了谁、这一轮的结果如何”，
 * 流程推进完全交给引擎；减签、驳回等动作都以本表的记录为入口。</p>
 */
public class BizSignRecord implements DbEntity, Serializable {

    private static final long serialVersionUID = 1L;

    /** 并加签：在当前节点扩容，与新老审批人同轮 */
    public static final String TYPE_PARALLEL = "PARALLEL";
    /** 前加签：锁原任务，前置插入子轮次 */
    public static final String TYPE_PRE = "PRE";
    /** 后加签：暂存原意见，后置插入子轮次 */
    public static final String TYPE_POST = "POST";

    /** 多实例任务定义 key（加签子流程内部固定为此值） */
    public static final String ADD_SIGN_TASK_KEY = "addSignTask";
    /** 加签子流程定义 key 前缀 */
    public static final String SUB_PROCESS_KEY_PREFIX = "addSign";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 完成条件：全部通过 */
    public static final String APPROVE_ALL = "ALL";
    /** 完成条件：任一通过 */
    public static final String APPROVE_ANY = "ANY";

    protected String id;
    protected String signRoundId;
    protected String processInstanceId;
    /** 前/后加签的子轮次流程实例；并加签为空 */
    protected String subProcessInstanceId;
    protected String sourceTaskId;
    protected String sourceTaskDefKey;
    /** 发起加签时原任务的 activityInstanceId（多实例节点上是发起人自己那一张） */
    protected String sourceActivityInstanceId;
    protected String signType;
    /** PRE/POST 的多人顺序：PARALLEL / SEQUENTIAL */
    protected String signMode;
    /** 完成条件快照：ALL / ANY（由管理员配置，发起人不可改） */
    protected String approveMode;
    protected String initiatorId;
    protected String addUserId;
    /** 加签人的引擎任务 ID（子流程任务或并加签新任务） */
    protected String addTaskId;
    /** 并加签新实例的 activityInstanceId，减签时用 */
    protected String addActivityInstanceId;
    protected String signStatus = STATUS_PENDING;
    /** 加签人提交的意见（原记录语义，保持兼容） */
    protected String opinion;
    /** 发起人的加签说明：为什么加签、请对方看什么；不会被加签人意见覆盖 */
    protected String signOpinion;
    protected Integer sequenceNo;
    protected Date createTime;
    protected Date updateTime;
    protected Date finishTime;

    @Override
    public Object getPersistentState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("subProcessInstanceId", subProcessInstanceId);
        state.put("addTaskId", addTaskId);
        state.put("addActivityInstanceId", addActivityInstanceId);
        state.put("signStatus", signStatus);
        state.put("opinion", opinion);
        state.put("signOpinion", signOpinion);
        state.put("updateTime", updateTime);
        state.put("finishTime", finishTime);
        return state;
    }

    public boolean isPending() {
        return STATUS_PENDING.equals(signStatus);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSignRoundId() { return signRoundId; }
    public void setSignRoundId(String signRoundId) { this.signRoundId = signRoundId; }
    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }
    public String getSubProcessInstanceId() { return subProcessInstanceId; }
    public void setSubProcessInstanceId(String subProcessInstanceId) { this.subProcessInstanceId = subProcessInstanceId; }
    public String getSourceTaskId() { return sourceTaskId; }
    public void setSourceTaskId(String sourceTaskId) { this.sourceTaskId = sourceTaskId; }
    public String getSourceTaskDefKey() { return sourceTaskDefKey; }
    public void setSourceTaskDefKey(String sourceTaskDefKey) { this.sourceTaskDefKey = sourceTaskDefKey; }
    public String getSourceActivityInstanceId() { return sourceActivityInstanceId; }
    public void setSourceActivityInstanceId(String sourceActivityInstanceId) { this.sourceActivityInstanceId = sourceActivityInstanceId; }
    public String getSignType() { return signType; }
    public void setSignType(String signType) { this.signType = signType; }
    public String getSignMode() { return signMode; }
    public void setSignMode(String signMode) { this.signMode = signMode; }
    public String getApproveMode() { return approveMode; }
    public void setApproveMode(String approveMode) { this.approveMode = approveMode; }
    public String getInitiatorId() { return initiatorId; }
    public void setInitiatorId(String initiatorId) { this.initiatorId = initiatorId; }
    public String getAddUserId() { return addUserId; }
    public void setAddUserId(String addUserId) { this.addUserId = addUserId; }
    public String getAddTaskId() { return addTaskId; }
    public void setAddTaskId(String addTaskId) { this.addTaskId = addTaskId; }
    public String getAddActivityInstanceId() { return addActivityInstanceId; }
    public void setAddActivityInstanceId(String addActivityInstanceId) { this.addActivityInstanceId = addActivityInstanceId; }
    public String getSignStatus() { return signStatus; }
    public void setSignStatus(String signStatus) { this.signStatus = signStatus; }
    public String getOpinion() { return opinion; }
    public void setOpinion(String opinion) { this.opinion = opinion; }
    public String getSignOpinion() { return signOpinion; }
    public void setSignOpinion(String signOpinion) { this.signOpinion = signOpinion; }
    public Integer getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(Integer sequenceNo) { this.sequenceNo = sequenceNo; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public Date getFinishTime() { return finishTime; }
    public void setFinishTime(Date finishTime) { this.finishTime = finishTime; }
}
