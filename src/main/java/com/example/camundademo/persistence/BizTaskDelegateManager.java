package com.example.camundademo.persistence;

import com.example.camundademo.entity.BizTaskDelegate;
import org.camunda.bpm.engine.impl.interceptor.Session;
import org.camunda.bpm.engine.impl.interceptor.SessionFactory;
import org.camunda.bpm.engine.impl.persistence.AbstractManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BizTaskDelegate 管理器：委托记录读写。
 *
 * <p>与 {@link BizTaskTransferManager} 一样走 DbSqlSession，保证
 * 「delegateTask/resolveTask + 落记录」在同一个引擎命令、同一个事务里完成。</p>
 */
public class BizTaskDelegateManager extends AbstractManager implements Session {

    public static final String MAPPER_NAMESPACE = "com.example.camundademo.persistence.BizTaskDelegateManager";

    public void insert(BizTaskDelegate delegate) {
        getDbEntityManager().insert(delegate);
    }

    /** 查询结果不是 DbEntityManager 的受管实体，修改后必须 merge 才会产生 UPDATE */
    public void update(BizTaskDelegate delegate) {
        getDbEntityManager().merge(delegate);
    }

    public List<BizTaskDelegate> findByTaskId(String taskId) {
        return selectList(MAPPER_NAMESPACE + ".selectByTaskId", taskId);
    }

    /** 待办列表整页批量查：单条查询会退化成 N 次 SQL */
    public List<BizTaskDelegate> findByTaskIds(Collection<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("list", taskIds);
        return selectList(MAPPER_NAMESPACE + ".selectByTaskIds", params);
    }

    public List<BizTaskDelegate> findByProcessInstanceId(String processInstanceId) {
        return selectList(MAPPER_NAMESPACE + ".selectByProcessInstanceId", processInstanceId);
    }

    /** 我委托出去的（含已交回/已收回，用于「我的委托」列表） */
    public List<BizTaskDelegate> findByOwner(String ownerUserId) {
        return selectList(MAPPER_NAMESPACE + ".selectByOwner", ownerUserId);
    }

    /** 我受托的（全部状态，前端按 status 分组） */
    public List<BizTaskDelegate> findByDelegateUser(String delegateUserId) {
        return selectList(MAPPER_NAMESPACE + ".selectByDelegateUser", delegateUserId);
    }

    /** 一批规则下仍在委托中的记录（停用规则时统计待收回条数） */
    public List<BizTaskDelegate> findPendingByAgentRuleIds(Collection<String> agentRuleIds) {
        if (agentRuleIds == null || agentRuleIds.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("list", agentRuleIds);
        return selectList(MAPPER_NAMESPACE + ".selectPendingByAgentRuleIds", params);
    }

    public BizTaskDelegate findPendingByTaskId(String taskId) {
        List<BizTaskDelegate> list = selectList(MAPPER_NAMESPACE + ".selectPendingByTaskId", taskId);
        return list.isEmpty() ? null : list.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<BizTaskDelegate> selectList(String statement, Object parameter) {
        // 同一命令内重复查询时清一级缓存，避免读到旧结果
        getDbSqlSession().getSqlSession().clearCache();
        return (List<BizTaskDelegate>) getDbSqlSession().selectList(statement, parameter);
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
            return BizTaskDelegateManager.class;
        }

        @Override
        public Session openSession() {
            return new BizTaskDelegateManager();
        }
    }
}
