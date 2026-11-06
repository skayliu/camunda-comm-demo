-- ============================================================================
-- V4 代理办理方式 · H2 2.2
--
-- BIZ_AGENT_RULE_.DELEGATE_MODE_：CONFIRM 受托人只能交回 / SIGN 受托人可直接办结。
-- BIZ_TASK_DELEGATE_.DELEGATE_MODE_：把本次委托实际采用的方式留痕，
--   规则事后被改过也不会让历史记录变义。
--
-- 存量数据默认 CONFIRM：责任默认留在委托人身上，此次升级不改变任何已有任务的行为。
-- ============================================================================

ALTER TABLE BIZ_AGENT_RULE_
    ADD COLUMN IF NOT EXISTS DELEGATE_MODE_ VARCHAR(16) DEFAULT 'CONFIRM' NOT NULL;

ALTER TABLE BIZ_TASK_DELEGATE_
    ADD COLUMN IF NOT EXISTS DELEGATE_MODE_ VARCHAR(16) DEFAULT 'CONFIRM' NOT NULL;
