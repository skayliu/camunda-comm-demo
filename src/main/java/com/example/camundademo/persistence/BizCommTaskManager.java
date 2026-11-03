package com.example.camundademo.persistence;

import com.example.camundademo.entity.BizCommTask;
import org.camunda.bpm.engine.impl.interceptor.Session;
import org.camunda.bpm.engine.impl.interceptor.SessionFactory;
import org.camunda.bpm.engine.impl.persistence.AbstractManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BizCommTask 管理器
 */
public class BizCommTaskManager extends AbstractManager implements Session {

    public static final String MAPPER_NAMESPACE = "com.example.camundademo.persistence.BizCommTaskManager";

    public void insert(BizCommTask task) {
        getDbEntityManager().insert(task);
    }

    public void update(BizCommTask task) {
        getDbEntityManager().merge(task);
    }

    public void deleteById(String id) {
        BizCommTask task = getDbEntityManager().selectById(BizCommTask.class, id);
        if (task != null) {
            getDbEntityManager().delete(task);
        }
    }

    public BizCommTask findById(String id) {
        clearQueryCache();
        return getDbEntityManager().selectById(BizCommTask.class, id);
    }

    public List<BizCommTask> findBySessionId(String sessionId) {
        return selectList(MAPPER_NAMESPACE + ".selectBySessionId", sessionId);
    }

    public List<BizCommTask> findPendingBySession(String sessionId) {
        return selectList(MAPPER_NAMESPACE + ".selectPendingBySession", sessionId);
    }

    public List<BizCommTask> findTopLevelPendingBySession(String sessionId) {
        return selectList(MAPPER_NAMESPACE + ".selectTopLevelPendingBySession", sessionId);
    }

    public List<BizCommTask> findChildren(String parentCommTaskId) {
        return selectList(MAPPER_NAMESPACE + ".selectByParentId", parentCommTaskId);
    }

    public List<BizCommTask> findBySourceTaskId(String sourceTaskId) {
        return selectList(MAPPER_NAMESPACE + ".selectBySourceTaskId", sourceTaskId);
    }

    /**
     * 按 Camunda 任务ID 查询沟通记录（DEPT_PROC_INST_ID_ 实际存放旁路任务ID）
     */
    public BizCommTask findByCamundaTaskId(String camundaTaskId) {
        clearQueryCache();
        return (BizCommTask) getDbSqlSession().selectOne(
                MAPPER_NAMESPACE + ".selectByCamundaTaskId", camundaTaskId);
    }

    @SuppressWarnings("unchecked")
    private List<BizCommTask> selectList(String statement, Object parameter) {
        clearQueryCache();
        return (List<BizCommTask>) getDbSqlSession().selectList(statement, parameter);
    }

    public long countPendingTopLevel(String sessionId) {
        clearQueryCache();
        Long count = (Long) getDbSqlSession().selectOne(
                MAPPER_NAMESPACE + ".countPendingTopLevel", sessionId);
        return count == null ? 0 : count;
    }

    /**
     * 清理 MyBatis 一级缓存，避免同一命令内同参数查询复用旧结果。
     * <p>
     * 注意：这里<b>不能</b>调用 {@code getDbEntityManager().flush()}。
     * Camunda 的 DbOperationManager 是一次性队列，只允许在命令结束时统一 flush；
     * 中途 flush 会让同一批操作在命令收尾时被再次提交，实测会触发
     * {@code ACT_RU_TASK_METER_LOG} 主键冲突。因此业务侧改为
     * “先一次性查出会话内全部沟通任务，再在内存中判定状态”，不做写后读。
     */
    private void clearQueryCache() {
        getDbSqlSession().getSqlSession().clearCache();
    }

    public List<BizCommTask> findByTargetUserAndStatus(String userId, String status) {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        params.put("status", status);
        return selectList(MAPPER_NAMESPACE + ".selectByTargetAndStatus", params);
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
            return BizCommTaskManager.class;
        }

        @Override
        public Session openSession() {
            return new BizCommTaskManager();
        }
    }
}
