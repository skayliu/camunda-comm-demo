package com.example.camundademo.persistence;

import com.example.camundademo.entity.BizSignRecord;
import org.camunda.bpm.engine.impl.interceptor.Session;
import org.camunda.bpm.engine.impl.interceptor.SessionFactory;
import org.camunda.bpm.engine.impl.persistence.AbstractManager;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 加签记录管理器。所有读写都走 Camunda 自带的 DbSqlSession，
 * 因此可以和引擎命令（启动子流程、modification、complete）放在同一个事务里。
 */
public class BizSignRecordManager extends AbstractManager implements Session {

    public static final String MAPPER_NAMESPACE = "com.example.camundademo.persistence.BizSignRecordManager";

    public void insert(BizSignRecord record) {
        getDbEntityManager().insert(record);
    }

    public void update(BizSignRecord record) {
        getDbEntityManager().merge(record);
    }

    public BizSignRecord findById(String id) {
        clearQueryCache();
        return getDbEntityManager().selectById(BizSignRecord.class, id);
    }

    public List<BizSignRecord> findBySourceTaskId(String sourceTaskId) {
        return selectList(MAPPER_NAMESPACE + ".selectBySourceTaskId", sourceTaskId);
    }

    public List<BizSignRecord> findByProcessInstanceId(String processInstanceId) {
        return selectList(MAPPER_NAMESPACE + ".selectByProcessInstanceId", processInstanceId);
    }

    public List<BizSignRecord> findByRoundId(String signRoundId) {
        return selectList(MAPPER_NAMESPACE + ".selectByRoundId", signRoundId);
    }

    public BizSignRecord findByAddTaskId(String addTaskId) {
        clearQueryCache();
        return (BizSignRecord) getDbSqlSession().selectOne(
                MAPPER_NAMESPACE + ".selectByAddTaskId", addTaskId);
    }

    /**
     * 按子流程实例取本轮记录的代表行。
     *
     * <p>一个子流程实例（一轮加签）对应<b>多条</b>记录——加几个人就有几条，所以这里不能
     * 用 {@code selectOne}，否则一旦加签人数大于 1，引擎会抛
     * {@code TooManyResultsException}，表现为「提交加签意见直接 500」。
     * 同轮记录的 {@code SIGN_ROUND_ID_} 相同，取第一条即可满足调用方需求。</p>
     */
    @SuppressWarnings("unchecked")
    public BizSignRecord findBySubProcessInstanceId(String subProcessInstanceId) {
        clearQueryCache();
        List<BizSignRecord> records = (List<BizSignRecord>) getDbSqlSession()
                .selectList(MAPPER_NAMESPACE + ".selectBySubProcessInstanceId", subProcessInstanceId);
        return records.isEmpty() ? null : records.get(0);
    }

    /** 同一轮里同一人是否已存在未取消的记录（补人时去重） */
    public long countActiveByRoundAndUser(String signRoundId, String addUserId) {
        clearQueryCache();
        Map<String, Object> params = new HashMap<>();
        params.put("signRoundId", signRoundId);
        params.put("addUserId", addUserId);
        Long count = (Long) getDbSqlSession().selectOne(
                MAPPER_NAMESPACE + ".countActiveByRoundAndUser", params);
        return count == null ? 0 : count;
    }

    /** 该待办是否还有未完成的加签（用于锁定判断与前端按钮状态） */
    public long countPendingBySourceTask(String sourceTaskId) {
        clearQueryCache();
        Long count = (Long) getDbSqlSession().selectOne(
                MAPPER_NAMESPACE + ".countPendingBySourceTask", sourceTaskId);
        return count == null ? 0 : count;
    }

    public int finishRound(String signRoundId, String status, String opinion, Date finishTime) {
        clearQueryCache();
        Map<String, Object> params = new HashMap<>();
        params.put("signRoundId", signRoundId);
        params.put("status", status);
        params.put("opinion", opinion);
        params.put("finishTime", finishTime);
        return getDbSqlSession().getSqlSession()
                .update(MAPPER_NAMESPACE + ".finishRound", params);
    }

    public int cancelPendingBySourceTask(String sourceTaskId, Date finishTime) {
        clearQueryCache();
        Map<String, Object> params = new HashMap<>();
        params.put("sourceTaskId", sourceTaskId);
        params.put("finishTime", finishTime);
        return getDbSqlSession().getSqlSession()
                .update(MAPPER_NAMESPACE + ".cancelPendingBySourceTask", params);
    }

    @SuppressWarnings("unchecked")
    private List<BizSignRecord> selectList(String statement, Object parameter) {
        clearQueryCache();
        return (List<BizSignRecord>) getDbSqlSession().selectList(statement, parameter);
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
            return BizSignRecordManager.class;
        }

        @Override
        public Session openSession() {
            return new BizSignRecordManager();
        }
    }
}
