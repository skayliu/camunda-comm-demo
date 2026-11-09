-- ============================================================================
-- V5 部门沟通子流程注册表 · SQL Server 2019+
-- 与 H2 版的差异：NVARCHAR、DATETIME2、无 IF NOT EXISTS。
-- ============================================================================

CREATE TABLE BIZ_SUBPROCESS_DEF_ (
    ID_ NVARCHAR(64) NOT NULL,
    PROCESS_KEY_ NVARCHAR(100) NOT NULL,
    PROCESS_NAME_ NVARCHAR(200) NOT NULL,
    DEPT_ID_ NVARCHAR(64) NOT NULL,
    DEPT_NAME_ NVARCHAR(100) NULL,
    BIZ_TYPE_ NVARCHAR(50) NULL,
    VERSION_ INT NOT NULL DEFAULT 1,
    STATUS_ INT NOT NULL DEFAULT 1,
    DESCRIPTION_ NVARCHAR(500) NULL,
    CREATE_TIME_ DATETIME2 DEFAULT SYSDATETIME(),
    UPDATE_TIME_ DATETIME2 DEFAULT SYSDATETIME(),
    CONSTRAINT PK_BIZ_SUBPROCESS_DEF_ PRIMARY KEY (ID_),
    CONSTRAINT UK_BIZ_SUBPROCESS_KEY UNIQUE (PROCESS_KEY_, VERSION_)
);

CREATE INDEX IDX_BIZ_SUBPROCESS_DEPT ON BIZ_SUBPROCESS_DEF_ (DEPT_ID_, STATUS_);

INSERT INTO BIZ_SUBPROCESS_DEF_ (ID_, PROCESS_KEY_, PROCESS_NAME_, DEPT_ID_, DEPT_NAME_,
    BIZ_TYPE_, VERSION_, STATUS_, DESCRIPTION_) VALUES
('sp_general_v1', N'dept_comm_general', N'通用部门沟通子流程（文书中枢）', N'*', N'全部门通用',
    N'GENERAL', 1, 1, N'文书收文分发 → 经办人填写意见 → 文书汇总反馈，适用于大多数普通部门'),
('sp_legal_contract_v1', N'dept_comm_legal_contract', N'法务部合同审查流程', N'dept_legal', N'法务部',
    N'CONTRACT', 1, 1, N'合规初审 → 业务线专项审查 → 法务经理复核 → 出具正式法律意见'),
('sp_finance_budget_v1', N'dept_comm_finance_budget', N'财务部预算审核流程', N'dept_finance', N'财务部',
    N'BUDGET', 1, 1, N'预算专员初审 → 资金岗确认 → 财务经理审批 → 财务文书汇总出意见');
