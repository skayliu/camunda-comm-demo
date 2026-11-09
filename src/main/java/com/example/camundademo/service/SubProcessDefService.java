package com.example.camundademo.service;

import com.example.camundademo.entity.BizSubProcessDef;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.identity.Group;
import org.camunda.bpm.engine.identity.User;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 部门沟通子流程注册表：可选列表、可用性校验、文书解析。
 *
 * <p>注册表是纯配置数据（读多写少），用 Spring JdbcTemplate 读取，
 * 不挂到 Camunda 的自定义 Session 上——它不参与沟通事务的写入，
 * 没必要为一个只读查询增加一级引擎缓存。</p>
 */
@Service
public class SubProcessDefService {

    private static final Logger log = LoggerFactory.getLogger(SubProcessDefService.class);

    /** 旧版单节点部门流程：历史数据仍在跑，查询/收回链路必须继续认它 */
    public static final String LEGACY_KEY = "dept_communication";
    /** 通用三节点流程 key（与 V5 种子数据一致） */
    public static final String GENERAL_KEY = "dept_comm_general";

    /** 通用流程的节点 key（文书分发 / 多实例经办 / 文书汇总） */
    public static final String GENERAL_DISPATCH_KEY = "Task_ClerkDispatch";
    public static final String GENERAL_HANDLER_KEY = "Task_HandlerOpinion";
    public static final String GENERAL_SUMMARY_KEY = "Task_ClerkSummary";

    private static final String SELECT_COLUMNS =
            "ID_, PROCESS_KEY_, PROCESS_NAME_, DEPT_ID_, DEPT_NAME_, BIZ_TYPE_, VERSION_, STATUS_, "
            + "DESCRIPTION_, CREATE_TIME_, UPDATE_TIME_";

    private static final RowMapper<BizSubProcessDef> MAPPER = new RowMapper<>() {
        @Override
        public BizSubProcessDef mapRow(ResultSet rs, int rowNum) throws SQLException {
            BizSubProcessDef d = new BizSubProcessDef();
            d.setId(rs.getString("ID_"));
            d.setProcessKey(rs.getString("PROCESS_KEY_"));
            d.setProcessName(rs.getString("PROCESS_NAME_"));
            d.setDeptId(rs.getString("DEPT_ID_"));
            d.setDeptName(rs.getString("DEPT_NAME_"));
            d.setBizType(rs.getString("BIZ_TYPE_"));
            d.setVersion(rs.getInt("VERSION_"));
            d.setStatus(rs.getInt("STATUS_"));
            d.setDescription(rs.getString("DESCRIPTION_"));
            d.setCreateTime(rs.getTimestamp("CREATE_TIME_"));
            d.setUpdateTime(rs.getTimestamp("UPDATE_TIME_"));
            return d;
        }
    };

    private final JdbcTemplate jdbc;
    private final RepositoryService repositoryService;
    private final IdentityService identityService;

    public SubProcessDefService(JdbcTemplate jdbc, RepositoryService repositoryService,
                                IdentityService identityService) {
        this.jdbc = jdbc;
        this.repositoryService = repositoryService;
        this.identityService = identityService;
    }

    /**
     * 某部门可选的子流程：全部门通用 + 该部门专属，每个 key 只保留启用中的最新版本。
     *
     * @param deptId 为空时只返回通用流程
     */
    public List<BizSubProcessDef> listAvailable(String deptId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLUMNS)
                .append(" FROM BIZ_SUBPROCESS_DEF_ d WHERE d.STATUS_ = ?");
        args.add(BizSubProcessDef.STATUS_ENABLED);
        if (deptId == null || deptId.isBlank()) {
            sql.append(" AND d.DEPT_ID_ = ?");
            args.add(BizSubProcessDef.DEPT_ALL);
        } else {
            sql.append(" AND (d.DEPT_ID_ = ? OR d.DEPT_ID_ = ?)");
            args.add(BizSubProcessDef.DEPT_ALL);
            args.add(deptId);
        }
        // 同一 key 的多个版本只留最新一条；已停用的老版本在上面的 STATUS_ 条件里已被排除
        sql.append(" AND d.VERSION_ = (SELECT MAX(v.VERSION_) FROM BIZ_SUBPROCESS_DEF_ v")
                .append(" WHERE v.PROCESS_KEY_ = d.PROCESS_KEY_ AND v.STATUS_ = ?)");
        args.add(BizSubProcessDef.STATUS_ENABLED);
        sql.append(" ORDER BY CASE WHEN d.DEPT_ID_ = ? THEN 0 ELSE 1 END, d.PROCESS_NAME_");
        args.add(BizSubProcessDef.DEPT_ALL);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** 按 key 取启用中的最新登记记录；不存在或已停用返回 null */
    public BizSubProcessDef findEnabled(String processKey) {
        if (processKey == null || processKey.isBlank()) {
            return null;
        }
        List<BizSubProcessDef> list = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM BIZ_SUBPROCESS_DEF_"
                        + " WHERE PROCESS_KEY_ = ? AND STATUS_ = ? ORDER BY VERSION_ DESC",
                MAPPER, processKey, BizSubProcessDef.STATUS_ENABLED);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 注册表中登记过的全部 key（含停用），用于识别“这个流程实例是不是部门沟通” */
    public List<String> allRegisteredKeys() {
        return jdbc.queryForList("SELECT DISTINCT PROCESS_KEY_ FROM BIZ_SUBPROCESS_DEF_", String.class);
    }

    /** 该 key 是否属于部门沟通子流程（注册表登记过，或旧版单节点流程） */
    public boolean isDeptSubProcessKey(String processDefinitionKey) {
        if (processDefinitionKey == null) {
            return false;
        }
        if (LEGACY_KEY.equals(processDefinitionKey)) {
            return true;
        }
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM BIZ_SUBPROCESS_DEF_ WHERE PROCESS_KEY_ = ?",
                Integer.class, processDefinitionKey);
        return n != null && n > 0;
    }

    /**
     * 校验某个子流程现在能不能用：注册表启用 + 引擎里确实部署过。
     *
     * <p>只查注册表不够：BPMN 没部署或部署失败时，启动会抛
     * "no processes deployed with key"，用户看到的是 500 而不是可理解的提示。</p>
     */
    public BizSubProcessDef requireAvailable(String processKey) {
        BizSubProcessDef def = findEnabled(processKey);
        if (def == null) {
            throw new BusinessException("该部门流程暂不可用（未登记或已停用），请选择其他流程");
        }
        ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processKey)
                .latestVersion()
                .singleResult();
        if (pd == null) {
            throw new BusinessException("流程 " + def.getProcessName() + " 尚未部署到引擎，请联系管理员");
        }
        return def;
    }

    /** 默认子流程：注册表里的通用三节点流程；未登记时回退到旧版单节点流程 */
    public String defaultProcessKey(String deptId) {
        List<BizSubProcessDef> available = listAvailable(deptId);
        for (BizSubProcessDef d : available) {
            if (BizSubProcessDef.DEPT_ALL.equals(d.getDeptId())
                    && !LEGACY_KEY.equals(d.getProcessKey())) {
                return d.getProcessKey();
            }
        }
        return LEGACY_KEY;
    }

    /**
     * 解析部门文书（接口人）。
     *
     * <p>解析顺序：{@code <deptId>_clerk} 组（部门自我维护的文书岗）→ 部门成员按账号排序第一个。
     * 找不到任何人时抛业务异常，避免子流程启动后卡在一个没人能看见的文书节点上。</p>
     */
    public String resolveClerk(String deptId) {
        if (deptId == null || deptId.isBlank()) {
            throw new BusinessException("部门标识不能为空，无法解析文书");
        }
        String clerkGroup = deptId + "_clerk";
        List<User> clerks = identityService.createUserQuery().memberOfGroup(clerkGroup).list();
        if (!clerks.isEmpty()) {
            return clerks.get(0).getId();
        }
        List<User> members = identityService.createUserQuery().memberOfGroup(deptId).list();
        if (!members.isEmpty()) {
            return members.get(0).getId();
        }
        Group group = identityService.createGroupQuery().groupId(deptId).singleResult();
        throw new BusinessException("部门 " + (group == null || group.getName() == null
                ? deptId : group.getName()) + " 暂无成员，无法指定文书");
    }

    /** 部门成员账号列表（前端分发选人用，后端也会重新校验） */
    public List<String> deptMembers(String deptId) {
        List<String> ids = new ArrayList<>();
        for (User u : identityService.createUserQuery().memberOfGroup(deptId).list()) {
            ids.add(u.getId());
        }
        return ids;
    }

    /** 用户是否属于该部门（分发经办人时的硬校验） */
    public boolean isDeptMember(String userId, String deptId) {
        if (userId == null || deptId == null) {
            return false;
        }
        return identityService.createUserQuery().userId(userId).memberOfGroup(deptId).count() > 0;
    }
}
