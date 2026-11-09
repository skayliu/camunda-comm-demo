-- ============================================================================
-- V5 部门沟通子流程注册表 · H2 2.2
--
-- 主流程只提供「发起部门沟通」一个入口，具体走哪个子流程由本表决定：
--   DEPT_ID_ = '*'        通用流程（所有部门可用）
--   DEPT_ID_ = 具体部门ID  部门专属流程（只对该部门可见）
-- 新增一个部门专属流程只需两步：部署 BPMN + 在本表加一条记录，主流程无需改动。
--
-- VERSION_ 与 PROCESS_KEY_ 组成唯一键：同 key 部署新版本后在此登记新版本号，
-- 存量实例仍按启动时的流程定义版本跑完（Camunda 原生版本隔离）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS BIZ_SUBPROCESS_DEF_ (
    ID_ VARCHAR(64) NOT NULL,
    -- Camunda 流程定义 key（BPMN 里的 process id）
    PROCESS_KEY_ VARCHAR(100) NOT NULL,
    PROCESS_NAME_ VARCHAR(200) NOT NULL,
    -- '*' 表示全部门通用
    DEPT_ID_ VARCHAR(64) NOT NULL,
    DEPT_NAME_ VARCHAR(100),
    -- 适用事项类型，如 CONTRACT / BUDGET；GENERAL 表示不限事项
    BIZ_TYPE_ VARCHAR(50),
    VERSION_ INT NOT NULL DEFAULT 1,
    -- 0 停用 / 1 启用
    STATUS_ INT NOT NULL DEFAULT 1,
    DESCRIPTION_ VARCHAR(500),
    CREATE_TIME_ TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UPDATE_TIME_ TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT PK_BIZ_SUBPROCESS_DEF_ PRIMARY KEY (ID_)
);

CREATE UNIQUE INDEX IF NOT EXISTS UK_BIZ_SUBPROCESS_KEY
    ON BIZ_SUBPROCESS_DEF_ (PROCESS_KEY_, VERSION_);
CREATE INDEX IF NOT EXISTS IDX_BIZ_SUBPROCESS_DEPT
    ON BIZ_SUBPROCESS_DEF_ (DEPT_ID_, STATUS_);

-- 内置通用流程：文书中枢三节点（分发 → 经办人意见 → 汇总反馈）
INSERT INTO BIZ_SUBPROCESS_DEF_ (ID_, PROCESS_KEY_, PROCESS_NAME_, DEPT_ID_, DEPT_NAME_,
    BIZ_TYPE_, VERSION_, STATUS_, DESCRIPTION_)
SELECT 'sp_general_v1', 'dept_comm_general', '通用部门沟通子流程（文书中枢）', '*', '全部门通用',
    'GENERAL', 1, 1, '文书收文分发 → 经办人填写意见 → 文书汇总反馈，适用于大多数普通部门'
WHERE NOT EXISTS (SELECT 1 FROM BIZ_SUBPROCESS_DEF_ WHERE ID_ = 'sp_general_v1');

-- 示例专属流程：法务部合同审查（固定岗位、固定顺序，不走文书动态选人）
INSERT INTO BIZ_SUBPROCESS_DEF_ (ID_, PROCESS_KEY_, PROCESS_NAME_, DEPT_ID_, DEPT_NAME_,
    BIZ_TYPE_, VERSION_, STATUS_, DESCRIPTION_)
SELECT 'sp_legal_contract_v1', 'dept_comm_legal_contract', '法务部合同审查流程', 'dept_legal', '法务部',
    'CONTRACT', 1, 1, '合规初审 → 业务线专项审查 → 法务经理复核 → 出具正式法律意见'
WHERE NOT EXISTS (SELECT 1 FROM BIZ_SUBPROCESS_DEF_ WHERE ID_ = 'sp_legal_contract_v1');

-- 示例专属流程：财务部预算审核
INSERT INTO BIZ_SUBPROCESS_DEF_ (ID_, PROCESS_KEY_, PROCESS_NAME_, DEPT_ID_, DEPT_NAME_,
    BIZ_TYPE_, VERSION_, STATUS_, DESCRIPTION_)
SELECT 'sp_finance_budget_v1', 'dept_comm_finance_budget', '财务部预算审核流程', 'dept_finance', '财务部',
    'BUDGET', 1, 1, '预算专员初审 → 资金岗确认 → 财务经理审批 → 财务文书汇总出意见'
WHERE NOT EXISTS (SELECT 1 FROM BIZ_SUBPROCESS_DEF_ WHERE ID_ = 'sp_finance_budget_v1');
