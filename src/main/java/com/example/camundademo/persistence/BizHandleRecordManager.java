package com.example.camundademo.persistence;

import com.example.camundademo.entity.BizHandleRecord;
import org.camunda.bpm.engine.impl.interceptor.Session;
import org.camunda.bpm.engine.impl.interceptor.SessionFactory;
import org.camunda.bpm.engine.impl.persistence.AbstractManager;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 阅办（承办）记录管理器。
 *
 * <p>阅办不创建引擎任务，只在这里维护办理状态与结果；
 * 办理结果不写流程变量，避免引擎误把承办意见当成审批结论。</p>
 */
public class BizHandleRecordManager extends AbstractManager implements Session {

    public static final String MAPPER_NAMESPACE = "com.example.camundademo.persistence.BizHandleRecordManager";

    public void insert(BizHandleRecord record) {
        getDbEntityManager().insert(record);
    }

    public void update(BizHandleRecord record) {
        getDbEntityManager().merge(record);
    }

    public void deleteById(String id) {
        BizHandleRecord record = getDbEntityManager().selectById(BizHandleRecord.class, id);
        if (record != null) {
            getDbEntityManager().delete(record);
        }
    }

    public BizHandleRecord findById(String id) {
        clearQueryCache();
        return getDbEntityManager().selectById(BizHandleRecord.class, id);
    }

    /** 同一待办、同一承办人是否已有未完成的阅办（去重） */
    public long countActive(String processInstanceId, String taskId, String assigneeId) {
        clearQueryCache();
        Map<String, Object> params = new HashMap<>();
        params.put("processInstanceId", processInstanceId);
        params.put("taskId", taskId);
        params.put("assigneeId", assigneeId);
        Long count = (Long) getDbSqlSession().selectOne(MAPPER_NAMESPACE + ".countActive", params);
        return count == null ? 0 : count;
    }

    public List<BizHandleRecord> findByAssignee(String assigneeId) {
        return selectList(MAPPER_NAMESPACE + ".selectByAssignee", assigneeId);
    }

    /** 我发出的阅办：按发起人反查 */
    public List<BizHandleRecord> findBySender(String senderId) {
        return selectList(MAPPER_NAMESPACE + ".selectBySender", senderId);
    }

    public long countPending(String assigneeId) {
        clearQueryCache();
        Long count = (Long) getDbSqlSession().selectOne(
                MAPPER_NAMESPACE + ".countPending", assigneeId);
        return count == null ? 0 : count;
    }

    public List<BizHandleRecord> findByProcInst(String processInstanceId) {
        return selectList(MAPPER_NAMESPACE + ".selectByProcInst", processInstanceId);
    }

    /** 报表取数：since 为空表示全部，聚合在服务层完成 */
    public List<BizHandleRecord> findForReport(Date since) {
        clearQueryCache();
        Map<String, Object> params = new HashMap<>();
        params.put("since", since);
        return selectList(MAPPER_NAMESPACE + ".selectForReport", params);
    }

    public boolean existsByProcInstAndUser(String processInstanceId, String userId) {
        clearQueryCache();
        Map<String, Object> params = new HashMap<>();
        params.put("processInstanceId", processInstanceId);
        params.put("assigneeId", userId);
        Long count = (Long) getDbSqlSession().selectOne(
                MAPPER_NAMESPACE + ".countByProcInstAndUser", params);
        return count != null && count > 0;
    }

    public int updateStatus(String id, String status, String handleResult, Date handleTime) {
        clearQueryCache();
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("status", status);
        params.put("handleResult", handleResult);
        params.put("handleTime", handleTime);
        return getDbSqlSession().getSqlSession()
                .update(MAPPER_NAMESPACE + ".updateStatus", params);
    }

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

    /** 流程结束时未完成的阅办自动失效；已完成的结果照常归档 */
    public int invalidateByProcInst(String processInstanceId, Date revokeTime) {
        clearQueryCache();
        Map<String, Object> params = new HashMap<>();
        params.put("processInstanceId", processInstanceId);
        params.put("revokeTime", revokeTime);
        return getDbSqlSession().getSqlSession()
                .update(MAPPER_NAMESPACE + ".invalidateByProcInst", params);
    }

    /** 定时任务：把已过办理时限且未完成的记录标记为逾期 */
    public int markOverdue(Date now) {
        clearQueryCache();
        Map<String, Object> params = new HashMap<>();
        params.put("now", now);
        return getDbSqlSession().getSqlSession()
                .update(MAPPER_NAMESPACE + ".markOverdue", params);
    }

    @SuppressWarnings("unchecked")
    private List<BizHandleRecord> selectList(String statement, Object parameter) {
        clearQueryCache();
        return (List<BizHandleRecord>) getDbSqlSession().selectList(statement, parameter);
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
            return BizHandleRecordManager.class;
        }

        @Override
        public Session openSession() {
            return new BizHandleRecordManager();
        }
    }
}
