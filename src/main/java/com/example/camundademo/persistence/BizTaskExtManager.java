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

    /**
     * 命令内「待落库」覆盖表。
     *
     * <p>Camunda 的 DbEntityManager 只在命令结束时统一 flush，所以 {@link #update} 之后
     * 直接查库仍会读到旧值。加签收口就是典型场景：{@code onRoundEnd} 已把
     * {@code BIZ_STATUS_} 改为 {@code NORMAL}，但同一命令里随后 {@code taskService.complete}
     * 触发的拦截器/监听器仍读到 {@code WAIT_POST_SIGN}，导致「自己把自己锁住」。</p>
     *
     * <p>这里按命令（Session 实例）缓存最近一次写入的实体，读时优先命中，
     * 保证同一命令内「写完立刻读」是一致的。</p>
     */
    private final Map<String, BizTaskExt> pendingWrites = new HashMap<>();

    public void insert(BizTaskExt ext) {
        getDbEntityManager().insert(ext);
        remember(ext);
    }

    public void update(BizTaskExt ext) {
        getDbEntityManager().merge(ext);
        remember(ext);
    }

    private void remember(BizTaskExt ext) {
        if (ext != null && ext.getTaskId() != null) {
            pendingWrites.put(ext.getTaskId(), ext);
        }
    }

    public BizTaskExt findByTaskId(String taskId) {
        // 先看本命令内刚写过、尚未 flush 的状态
        BizTaskExt pending = pendingWrites.get(taskId);
        if (pending != null) {
            return pending;
        }
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
