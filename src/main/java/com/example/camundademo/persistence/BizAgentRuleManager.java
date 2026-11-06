package com.example.camundademo.persistence;

import com.example.camundademo.entity.BizAgentRule;
import org.camunda.bpm.engine.impl.interceptor.Session;
import org.camunda.bpm.engine.impl.interceptor.SessionFactory;
import org.camunda.bpm.engine.impl.persistence.AbstractManager;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BizAgentRule 管理器：代理规则的读写。
 *
 * <p>{@link #findValidByOwner} 会被全局任务创建监听器调用，
 * 因此同样必须通过 CommandContext 的 Session 取得，不能做成 Spring Bean 直接查库——
 * 否则监听器里的查询会跑到引擎事务之外，读不到同一命令内刚写入的数据。</p>
 */
public class BizAgentRuleManager extends AbstractManager implements Session {

    public static final String MAPPER_NAMESPACE = "com.example.camundademo.persistence.BizAgentRuleManager";

    public void insert(BizAgentRule rule) {
        getDbEntityManager().insert(rule);
    }

    /** 规则修改/停用后 merge，才会在命令收尾时产生 UPDATE */
    public void update(BizAgentRule rule) {
        getDbEntityManager().merge(rule);
    }

    public BizAgentRule findById(String id) {
        return (BizAgentRule) getDbSqlSession().selectOne(MAPPER_NAMESPACE + ".selectBizAgentRule", id);
    }

    public List<BizAgentRule> findByOwner(String ownerUserId) {
        return selectList(MAPPER_NAMESPACE + ".selectByOwner", ownerUserId);
    }

    /** 管理员视图：全部规则，按开始时间倒序 */
    public List<BizAgentRule> findAll() {
        return selectList(MAPPER_NAMESPACE + ".selectAll", null);
    }

    /** 某人在此刻生效的规则（按创建时间倒序，调用方取第一条） */
    public List<BizAgentRule> findValidByOwner(String ownerUserId, Date now) {
        Map<String, Object> params = new HashMap<>();
        params.put("ownerUserId", ownerUserId);
        params.put("now", now);
        return selectList(MAPPER_NAMESPACE + ".selectValidByOwner", params);
    }

    @SuppressWarnings("unchecked")
    private List<BizAgentRule> selectList(String statement, Object parameter) {
        getDbSqlSession().getSqlSession().clearCache();
        return (List<BizAgentRule>) getDbSqlSession().selectList(statement, parameter);
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
            return BizAgentRuleManager.class;
        }

        @Override
        public Session openSession() {
            return new BizAgentRuleManager();
        }
    }
}
