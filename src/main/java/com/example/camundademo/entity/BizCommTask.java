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

    /**
     * 沟通旁路任务 taskDefinitionKey 的后缀。
     * 实际取值形如「源节点key#COMM」（如 manager_review#COMM），
     * 便于直接看出该沟通任务由哪个流程节点发起。
     */
    public static final String TASK_KEY_SUFFIX = "#COMM";

    /** 早期版本沟通任务使用固定 key "COMM"，判断时需一并兼容 */
    private static final String LEGACY_TASK_KEY = "COMM";

    /**
     * 部门沟通子流程的处理节点 key（与 dept_communication.bpmn 的 userTask id 一致）。
     * 部门沟通没有旁路任务，任务来自独立流程实例，但对待办中心、代理规则、
     * 审批按钮而言它同样是“沟通任务”，必须和 "#COMM" 一样被识别。
     */
    public static final String DEPT_TASK_KEY = "dept_handle";

    /**
     * 构造沟通旁路任务的 taskDefinitionKey：源节点 key + #COMM
     */
    public static String buildCommTaskKey(String sourceTaskDefinitionKey) {
        if (sourceTaskDefinitionKey == null) {
            return LEGACY_TASK_KEY;
        }
        return sourceTaskDefinitionKey + TASK_KEY_SUFFIX;
    }

    /**
     * 判断是否为沟通旁路任务（兼容历史的固定 "COMM"）
     */
    public static boolean isCommTaskKey(String taskDefinitionKey) {
        return taskDefinitionKey != null
                && (LEGACY_TASK_KEY.equals(taskDefinitionKey)
                    || taskDefinitionKey.endsWith(TASK_KEY_SUFFIX)
                    || DEPT_TASK_KEY.equals(taskDefinitionKey));
    }

    /**
     * 从沟通任务的 taskDefinitionKey 还原源节点 key（如 manager_review#COMM → manager_review）。
     * 用于历史数据兜底：早期记录的 SOURCE_TASK_DEF_KEY_ 为空，可从引擎任务 key 推导。
     */
    public static String parseSourceTaskDefKey(String commTaskDefinitionKey) {
        if (commTaskDefinitionKey == null) {
            return null;
        }
        if (LEGACY_TASK_KEY.equals(commTaskDefinitionKey)) {
            return null;
        }
        if (commTaskDefinitionKey.endsWith(TASK_KEY_SUFFIX)) {
            return commTaskDefinitionKey.substring(
                    0, commTaskDefinitionKey.length() - TASK_KEY_SUFFIX.length());
        }
        return commTaskDefinitionKey;
    }

    protected String id;
    protected String sessionId;
    protected String parentCommTaskId;
    protected String sourceTaskId;
    protected String rootTaskId;
    /**
     * 发起该沟通的流程节点 key（如 manager_review）。
     * 与 taskDefinitionKey 的前缀同源，但单独落库，便于报表按节点聚合，
     * 不必再从 "源节点key#COMM" 里截字符串。
     */
    protected String sourceTaskDefKey;
    protected String targetType;
    protected String targetId;
    protected String targetName;
    protected String content;
    protected String status;
    protected String replyContent;
    protected String replyUserId;
    protected Date replyTime;
    /**
     * 收回时间。收回走的是「改状态 + 删除引擎任务」，引擎侧不留任何痕迹，
     * 只能自己落库，否则跟踪图里的已收回记录无法说明是什么时候收回的。
     */
    protected Date withdrawTime;
    /**
     * 沟通旁路任务对应的 Camunda 任务 ID（newTask 创建出的 standalone 任务）。
     * 收回时 deleteTask、反馈时 complete、待办中心反查记录，全部靠它定位。
     * 创建后不再变化，因此不参与 update 的 SET 列表。
     */
    protected String camundaTaskId;
    /**
     * 部门沟通子流程实例 ID（沟通对象类型为 DEPT 时才有值）。
     * 收回沟通、主流程结束级联终止都靠它定位整个子流程；
     * 与 {@link #camundaTaskId}（子流程内 dept_handle 的任务ID）各归各位。
     */
    protected String deptProcInstId;
    protected Date createTime;

    @Override
    public Object getPersistentState() {
        Map<String, Object> state = new HashMap<>();
        state.put("status", status);
        state.put("replyContent", replyContent);
        state.put("replyUserId", replyUserId);
        state.put("replyTime", replyTime);
        state.put("withdrawTime", withdrawTime);
        // 部门沟通子流程是「先落记录、再启动流程」，实例ID 与部门任务ID
        // 都在 insert 之后才能拿到，必须参与脏检查，否则第二次 update 不会发 SQL
        state.put("camundaTaskId", camundaTaskId);
        state.put("deptProcInstId", deptProcInstId);
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
    public String getSourceTaskDefKey() { return sourceTaskDefKey; }
    public void setSourceTaskDefKey(String sourceTaskDefKey) { this.sourceTaskDefKey = sourceTaskDefKey; }
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
    public Date getWithdrawTime() { return withdrawTime; }
    public void setWithdrawTime(Date withdrawTime) { this.withdrawTime = withdrawTime; }
    public String getCamundaTaskId() { return camundaTaskId; }
    public void setCamundaTaskId(String camundaTaskId) { this.camundaTaskId = camundaTaskId; }
    public String getDeptProcInstId() { return deptProcInstId; }
    public void setDeptProcInstId(String deptProcInstId) { this.deptProcInstId = deptProcInstId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
