package com.example.camundademo.persistence;

import com.example.camundademo.entity.BizCcRecord;
import org.camunda.bpm.engine.impl.interceptor.Session;
import org.camunda.bpm.engine.impl.interceptor.SessionFactory;
import org.camunda.bpm.engine.impl.persistence.AbstractManager;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 传阅记录管理器。
 *
 * <p>走 DbSqlSession 与引擎命令共用事务：节点结束监听器创建传阅记录时，
 * 记录与流程推进要么一起提交、要么一起回滚，不会出现“流程走了但传阅丢了”。</p>
 */
public class BizCcRecordManager extends AbstractManager implements Session {

    public static final String MAPPER_NAMESPACE = "com.example.camundademo.persistence.BizCcRecordManager";

    public void insert(BizCcRecord record) {
        getDbEntityManager().insert(record);
    }

    public void update(BizCcRecord record) {
        getDbEntityManager().merge(record);
    }

    public void deleteById(String id) {
        BizCcRecord record = getDbEntityManager().selectById(BizCcRecord.class, id);
        if (record != null) {
            getDbEntityManager().delete(record);
        }
    }

    public BizCcRecord findById(String id) {
        clearQueryCache();
        return getDbEntityManager().selectById(BizCcRecord.class, id);
    }

    /** 同一流程实例下某人是否已有一条有效传阅（用于去重，已撤回的可以重新传阅） */
    public long countActive(String processInstanceId, String ccUserId) {
        clearQueryCache();
        Map<String, Object> params = new HashMap<>();
        params.put("processInstanceId", processInstanceId);
        params.put("ccUserId", ccUserId);
        Long count = (Long) getDbSqlSession().selectOne(
                MAPPER_NAMESPACE + ".countActive", params);
        return count == null ? 0 : count;
    }

    /** 同一流程实例下全部有效待阅人（批量去重用） */
    public List<BizCcRecord> findActiveByProcInst(String processInstanceId) {
        return selectList(MAPPER_NAMESPACE + ".selectActiveByProcInst", processInstanceId);
    }

    /** 我的传阅：未读优先，再按时间倒序 */
    public List<BizCcRecord> findByCcUser(String userId) {
        return selectList(MAPPER_NAMESPACE + ".selectByCcUser", userId);
    }

    /** 我发出的传阅：按发送人反查，发送时间倒序 */
    public List<BizCcRecord> findBySender(String senderUserId) {
        return selectList(MAPPER_NAMESPACE + ".selectBySender", senderUserId);
    }

    public long countUnread(String userId) {
        clearQueryCache();
        Long count = (Long) getDbSqlSession().selectOne(
                MAPPER_NAMESPACE + ".countUnread", userId);
        return count == null ? 0 : count;
    }

    public List<BizCcRecord> findByProcInst(String processInstanceId) {
        return selectList(MAPPER_NAMESPACE + ".selectByProcInst", processInstanceId);
    }

    public boolean existsByProcInstAndUser(String processInstanceId, String ccUserId) {
        return countActive(processInstanceId, ccUserId) > 0;
    }

    /**
     * 报表取数：since 为空表示全部。
     *
     * <p>这里只做时间过滤，不做 count/group by：报表口径（已读率、撤回量、Top 组合）
     * 全部在服务层用 Java 计算，避免各数据库日期函数与空值排序差异导致同一份报表
     * 在 H2 和 MySQL 上给出不同数字。</p>
     */
    public List<BizCcRecord> findForReport(Date since) {
        clearQueryCache();
        Map<String, Object> params = new HashMap<>();
        params.put("since", since);
        return selectList(MAPPER_NAMESPACE + ".selectForReport", params);
    }

    /** 单条标记已读：只更新状态字段，绝不触碰引擎任务 */
    public int markRead(String id, String ccUserId, Date readTime) {
        clearQueryCache();
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("ccUserId", ccUserId);
        params.put("readTime", readTime);
        return getDbSqlSession().getSqlSession()
                .update(MAPPER_NAMESPACE + ".markRead", params);
    }

    /** 批量标记已读，仅限本人、未读、未撤回的记录 */
    public int batchMarkRead(Collection<String> ids, String ccUserId, Date readTime) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        clearQueryCache();
        Map<String, Object> params = new HashMap<>();
        params.put("list", ids);
        params.put("ccUserId", ccUserId);
        params.put("readTime", readTime);
        return getDbSqlSession().getSqlSession()
                .update(MAPPER_NAMESPACE + ".batchMarkRead", params);
    }

    /** 软撤回：只允许撤回本人发出、对方未读、且尚未撤回的记录 */
    public int revoke(String id, String senderUserId, Date revokeTime, String reason) {
        clearQueryCache();
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("senderUserId", senderUserId);
        params.put("revokeTime", revokeTime);
        params.put("revokeReason", reason);
        return getDbSqlSession().getSqlSession()
                .update(MAPPER_NAMESPACE + ".revoke", params);
    }

    /**
     * 流程驳回/撤回/结束时让未读传阅失效。
     * 已读的不动——对方已经看过，强行标记失效会让审计说不清楚。
     */
    public int invalidateByProcInst(String processInstanceId, Date revokeTime) {
        clearQueryCache();
        Map<String, Object> params = new HashMap<>();
        params.put("processInstanceId", processInstanceId);
        params.put("revokeTime", revokeTime);
        return getDbSqlSession().getSqlSession()
                .update(MAPPER_NAMESPACE + ".invalidateByProcInst", params);
    }

    @SuppressWarnings("unchecked")
    private List<BizCcRecord> selectList(String statement, Object parameter) {
        clearQueryCache();
        return (List<BizCcRecord>) getDbSqlSession().selectList(statement, parameter);
    }

    private void clearQueryCache() {
        getDbSqlSession().getSqlSession().clearCache();
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    public static class Factory implements SessionFactory {
        @Override
        public Class<?> getSessionType() {
            return BizCcRecordManager.class;
        }

        @Override
        public Session openSession() {
            return new BizCcRecordManager();
        }
    }
}
