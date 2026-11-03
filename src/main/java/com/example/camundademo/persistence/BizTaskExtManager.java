package com.example.camundademo.persistence;

import com.example.camundademo.entity.BizTaskExt;
import org.camunda.bpm.engine.impl.interceptor.Session;
import org.camunda.bpm.engine.impl.interceptor.SessionFactory;
import org.camunda.bpm.engine.impl.persistence.AbstractManager;
import org.apache.ibatis.session.SqlSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BizTaskExt 管理器，使用Camunda原生DbSqlSession
 */
public class BizTaskExtManager extends AbstractManager implements Session {

    public static final String MAPPER_NAMESPACE = "com.example.camundademo.persistence.BizTaskExtManager";

    public void insert(BizTaskExt ext) {
        getDbEntityManager().insert(ext);
    }

    public void update(BizTaskExt ext) {
        getDbEntityManager().merge(ext);
    }

    public BizTaskExt findByTaskId(String taskId) {
        // 只清 MyBatis 一级缓存；不能在此处调用 DbEntityManager.flush()：
        // Camunda 的 DbOperationManager 仅允许在命令结束时统一 flush，
        // 中途 flush 会导致操作被重复提交（实测 ACT_RU_TASK_METER_LOG 主键冲突）
        getDbSqlSession().getSqlSession().clearCache();
        return (BizTaskExt) getDbSqlSession().selectOne(MAPPER_NAMESPACE + ".selectByTaskId", taskId);
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
            return BizTaskExtManager.class;
        }

        @Override
        public Session openSession() {
            return new BizTaskExtManager();
        }
    }
}
