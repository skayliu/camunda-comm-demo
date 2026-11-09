-- ============================================================================
-- V5 部门沟通子流程注册表 · MySQL 8.0
-- 与 H2 版的差异：DATETIME(3)、不支持 CREATE INDEX IF NOT EXISTS（幂等由 Flyway 保证）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS BIZ_SUBPROCESS_DEF_ (
    ID_ VARCHAR(64) NOT NULL,
    PROCESS_KEY_ VARCHAR(100) NOT NULL,
    PROCESS_NAME_ VARCHAR(200) NOT NULL,
    DEPT_ID_ VARCHAR(64) NOT NULL,
    DEPT_NAME_ VARCHAR(100) NULL,
    BIZ_TYPE_ VARCHAR(50) NULL,
    VERSION_ INT NOT NULL DEFAULT 1,
    STATUS_ INT NOT NULL DEFAULT 1,
    DESCRIPTION_ VARCHAR(500) NULL,
    CREATE_TIME_ DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    UPDATE_TIME_ DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT PK_BIZ_SUBPROCESS_DEF_ PRIMARY KEY (ID_),
    CONSTRAINT UK_BIZ_SUBPROCESS_KEY UNIQUE (PROCESS_KEY_, VERSION_)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX IDX_BIZ_SUBPROCESS_DEPT ON BIZ_SUBPROCESS_DEF_ (DEPT_ID_, STATUS_);

INSERT INTO BIZ_SUBPROCESS_DEF_ (ID_, PROCESS_KEY_, PROCESS_NAME_, DEPT_ID_, DEPT_NAME_,
    BIZ_TYPE_, VERSION_, STATUS_, DESCRIPTION_) VALUES
('sp_general_v1', 'dept_comm_general', '通用部门沟通子流程（文书中枢）', '*', '全部门通用',
    'GENERAL', 1, 1, '文书收文分发 → 经办人填写意见 → 文书汇总反馈，适用于大多数普通部门'),
('sp_legal_contract_v1', 'dept_comm_legal_contract', '法务部合同审查流程', 'dept_legal', '法务部',
    'CONTRACT', 1, 1, '合规初审 → 业务线专项审查 → 法务经理复核 → 出具正式法律意见'),
('sp_finance_budget_v1', 'dept_comm_finance_budget', '财务部预算审核流程', 'dept_finance', '财务部',
    'BUDGET', 1, 1, '预算专员初审 → 资金岗确认 → 财务经理审批 → 财务文书汇总出意见');
