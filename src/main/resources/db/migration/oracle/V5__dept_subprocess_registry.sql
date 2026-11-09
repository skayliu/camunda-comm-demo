-- ============================================================================
-- V5 部门沟通子流程注册表 · Oracle 19c
-- 与 H2 版的差异：无 IF NOT EXISTS、VARCHAR2(n CHAR)、数字列用 NUMBER。
-- ============================================================================

CREATE TABLE BIZ_SUBPROCESS_DEF_ (
    ID_ VARCHAR2(64 CHAR) NOT NULL,
    PROCESS_KEY_ VARCHAR2(100 CHAR) NOT NULL,
    PROCESS_NAME_ VARCHAR2(200 CHAR) NOT NULL,
    DEPT_ID_ VARCHAR2(64 CHAR) NOT NULL,
    DEPT_NAME_ VARCHAR2(100 CHAR),
    BIZ_TYPE_ VARCHAR2(50 CHAR),
    VERSION_ NUMBER(10) DEFAULT 1 NOT NULL,
    STATUS_ NUMBER(10) DEFAULT 1 NOT NULL,
    DESCRIPTION_ VARCHAR2(500 CHAR),
    CREATE_TIME_ TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UPDATE_TIME_ TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT PK_BIZ_SUBPROCESS_DEF_ PRIMARY KEY (ID_),
    CONSTRAINT UK_BIZ_SUBPROCESS_KEY UNIQUE (PROCESS_KEY_, VERSION_)
);

CREATE INDEX IDX_BIZ_SUBPROCESS_DEPT ON BIZ_SUBPROCESS_DEF_ (DEPT_ID_, STATUS_);

INSERT INTO BIZ_SUBPROCESS_DEF_ (ID_, PROCESS_KEY_, PROCESS_NAME_, DEPT_ID_, DEPT_NAME_,
    BIZ_TYPE_, VERSION_, STATUS_, DESCRIPTION_) VALUES
('sp_general_v1', 'dept_comm_general', '通用部门沟通子流程（文书中枢）', '*', '全部门通用',
    'GENERAL', 1, 1, '文书收文分发 → 经办人填写意见 → 文书汇总反馈，适用于大多数普通部门');

INSERT INTO BIZ_SUBPROCESS_DEF_ (ID_, PROCESS_KEY_, PROCESS_NAME_, DEPT_ID_, DEPT_NAME_,
    BIZ_TYPE_, VERSION_, STATUS_, DESCRIPTION_) VALUES
('sp_legal_contract_v1', 'dept_comm_legal_contract', '法务部合同审查流程', 'dept_legal', '法务部',
    'CONTRACT', 1, 1, '合规初审 → 业务线专项审查 → 法务经理复核 → 出具正式法律意见');

INSERT INTO BIZ_SUBPROCESS_DEF_ (ID_, PROCESS_KEY_, PROCESS_NAME_, DEPT_ID_, DEPT_NAME_,
    BIZ_TYPE_, VERSION_, STATUS_, DESCRIPTION_) VALUES
('sp_finance_budget_v1', 'dept_comm_finance_budget', '财务部预算审核流程', 'dept_finance', '财务部',
    'BUDGET', 1, 1, '预算专员初审 → 资金岗确认 → 财务经理审批 → 财务文书汇总出意见');

COMMIT;
