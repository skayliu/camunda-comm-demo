package com.example.camundademo.entity;

import java.io.Serializable;
import java.util.Date;

/**
 * 部门沟通子流程注册表实体（BIZ_SUBPROCESS_DEF_）。
 *
 * <p>这张表回答一个问题：<b>某个部门的某次沟通，应该启动哪个子流程？</b>
 * 主流程只在运行时按 {@link #processKey} 启动对应实例，BPMN 里没有任何
 * callActivity，因此新增部门流程不需要改主流程模型。</p>
 *
 * <p>同一 {@code processKey} 可以有多个版本，查询时只取启用中的最大版本；
 * 存量实例仍按启动时的流程定义版本继续执行。</p>
 */
public class BizSubProcessDef implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 全部门通用流程的 DEPT_ID_ 取值 */
    public static final String DEPT_ALL = "*";
    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    protected String id;
    protected String processKey;
    protected String processName;
    protected String deptId;
    protected String deptName;
    protected String bizType;
    protected Integer version;
    protected Integer status;
    protected String description;
    protected Date createTime;
    protected Date updateTime;

    /** 是否为全部门通用流程 */
    public boolean isGeneral() {
        return DEPT_ALL.equals(deptId);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProcessKey() { return processKey; }
    public void setProcessKey(String processKey) { this.processKey = processKey; }
    public String getProcessName() { return processName; }
    public void setProcessName(String processName) { this.processName = processName; }
    public String getDeptId() { return deptId; }
    public void setDeptId(String deptId) { this.deptId = deptId; }
    public String getDeptName() { return deptName; }
    public void setDeptName(String deptName) { this.deptName = deptName; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
