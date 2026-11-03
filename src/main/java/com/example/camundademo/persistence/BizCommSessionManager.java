package com.example.camundademo.persistence;

import com.example.camundademo.entity.BizCommSession;
import org.camunda.bpm.engine.impl.interceptor.Session;
import org.camunda.bpm.engine.impl.interceptor.SessionFactory;
import org.camunda.bpm.engine.impl.persistence.AbstractManager;

/**
 * BizCommSession 管理器
 */
public class BizCommSessionManager extends AbstractManager implements Session {

    public static final String MAPPER_NAMESPACE = "com.example.camundademo.persistence.BizCommSessionManager";

    public void insert(BizCommSession session) {
        getDbEntityManager().insert(session);
    }

    public void update(BizCommSession session) {
        getDbEntityManager().merge(session);
    }

    public BizCommSession findById(String id) {
        return getDbEntityManager().selectById(BizCommSession.class, id);
    }

    public BizCommSession findActiveByRootTask(String rootTaskId) {
        clearQueryCache();
        return (BizCommSession) getDbSqlSession().selectOne(
                MAPPER_NAMESPACE + ".selectActiveByRootTask", rootTaskId);
    }

    /**
     * 取该待办最近一次沟通会话（含已结束会话），用于会话关闭后的历史回看。
     */
    public BizCommSession findLatestByRootTask(String rootTaskId) {
        clearQueryCache();
        return (BizCommSession) getDbSqlSession().selectOne(
                MAPPER_NAMESPACE + ".selectLatestByRootTask", rootTaskId);
    }

    /**
     * 避免同一命令内同参数查询命中 MyBatis 一级缓存而读到旧状态。
     */
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
            return BizCommSessionManager.class;
        }

        @Override
        public Session openSession() {
            return new BizCommSessionManager();
        }
    }
}
