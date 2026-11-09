package com.example.camundademo.service;

import com.example.camundademo.entity.BizSubProcessDef;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.identity.Group;
import org.camunda.bpm.engine.identity.User;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.repository.ProcessDefinitionQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    // ===== 维护菜单 =====

    /** 自动登记时无法判定归属部门的占位值：不进可选列表，等待人工指定 */
    public static final String DEPT_UNKNOWN = "UNKNOWN";

    private static final int ID_MAX_LENGTH = 64;

    /**
     * 菜单列表：全部登记记录（含停用），并带上引擎侧的最新版本。
     *
     * <p>版本对不齐是这张表最常见的故障源，所以列表不直接相信 VERSION_，
     * 而是每条再查一次引擎：已部署显示引擎版本，未部署显示空，前端据此标红。</p>
     *
     * <p>注册表是配置数据，量级在几十条，逐条查引擎可以接受；若将来上千条，
     * 改成一次查出所有 key 的最新版本再在内存里 join。</p>
     */
    public List<Map<String, Object>> listAll(String keyword) {
        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLUMNS)
                .append(" FROM BIZ_SUBPROCESS_DEF_ d WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim().toUpperCase() + "%";
            sql.append(" AND (UPPER(d.PROCESS_KEY_) LIKE ? OR UPPER(d.PROCESS_NAME_) LIKE ?")
                    .append(" OR UPPER(d.DEPT_NAME_) LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY d.DEPT_ID_, d.PROCESS_NAME_, d.VERSION_ DESC");

        List<Map<String, Object>> result = new ArrayList<>();
        for (BizSubProcessDef d : jdbc.query(sql.toString(), MAPPER, args.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("processKey", d.getProcessKey());
            m.put("processName", d.getProcessName());
            m.put("deptId", d.getDeptId());
            m.put("deptName", d.getDeptName());
            m.put("bizType", d.getBizType());
            m.put("version", d.getVersion());
            m.put("status", d.getStatus());
            m.put("description", d.getDescription());
            m.put("createTime", d.getCreateTime());
            m.put("updateTime", d.getUpdateTime());
            m.put("general", d.isGeneral());
            m.put("unbound", DEPT_UNKNOWN.equals(d.getDeptId()));

            ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(d.getProcessKey())
                    .latestVersion()
                    .singleResult();
            m.put("deployed", pd != null);
            m.put("engineVersion", pd == null ? null : pd.getVersion());
            m.put("engineName", pd == null ? null : pd.getName());
            result.add(m);
        }
        return result;
    }

    /** 按主键取一条；不存在返回 null */
    public BizSubProcessDef findById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        List<BizSubProcessDef> list = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM BIZ_SUBPROCESS_DEF_ WHERE ID_ = ?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 菜单保存：id 为空新增，否则更新。
     *
     * <p>更新时刻意不允许改 {@code PROCESS_KEY_}：key 是主流程运行时定位流程的唯一依据，
     * 允许在页面上改 key 等于让已经发起的沟通全部指向一个不存在的流程。</p>
     */
    @Transactional
    public String save(BizSubProcessDef form) {
        if (form == null) {
            throw new BusinessException("提交内容为空");
        }
        String key = trim(form.getProcessKey());
        String name = trim(form.getProcessName());
        String deptId = trim(form.getDeptId());
        if (key.isEmpty()) {
            throw new BusinessException("流程 key 不能为空");
        }
        if (!key.startsWith("dept_comm_") && !LEGACY_KEY.equals(key)) {
            throw new BusinessException("部门沟通子流程 key 必须以 dept_comm_ 开头");
        }
        if (key.length() > 100) {
            throw new BusinessException("流程 key 不能超过 100 个字符");
        }
        if (name.isEmpty()) {
            throw new BusinessException("流程名称不能为空");
        }
        if (name.length() > 200) {
            throw new BusinessException("流程名称不能超过 200 个字符");
        }
        if (deptId.isEmpty()) {
            throw new BusinessException("请选择归属部门");
        }
        if (!BizSubProcessDef.DEPT_ALL.equals(deptId) && !DEPT_UNKNOWN.equals(deptId)
                && !isDepartmentGroup(deptId)) {
            throw new BusinessException("归属部门必须是类型为 DEPARTMENT 的组，或选择全部门通用");
        }
        int version = form.getVersion() == null ? 0 : form.getVersion();
        if (version < 0) {
            throw new BusinessException("版本号不能为负数");
        }
        int status = form.getStatus() == null ? BizSubProcessDef.STATUS_ENABLED : form.getStatus();
        if (status != BizSubProcessDef.STATUS_ENABLED && status != BizSubProcessDef.STATUS_DISABLED) {
            throw new BusinessException("状态只能是启用或停用");
        }
        String description = trim(form.getDescription());
        if (description.length() > 500) {
            throw new BusinessException("说明不能超过 500 个字符");
        }
        String bizType = trim(form.getBizType());
        if (bizType.length() > 50) {
            throw new BusinessException("业务类型不能超过 50 个字符");
        }
        String deptName = resolveDeptName(deptId);
        Timestamp now = new Timestamp(System.currentTimeMillis());

        String id = trim(form.getId());
        if (id.isEmpty()) {
            int actualVersion = version > 0 ? version : detectEngineVersion(key);
            id = buildId(key, actualVersion);
            Integer dup = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM BIZ_SUBPROCESS_DEF_ WHERE PROCESS_KEY_ = ? AND VERSION_ = ?",
                    Integer.class, key, actualVersion);
            if (dup != null && dup > 0) {
                throw new BusinessException("该流程 key 的 v" + actualVersion
                        + " 已登记，请直接编辑已有记录，或修改版本号");
            }
            jdbc.update("INSERT INTO BIZ_SUBPROCESS_DEF_ (ID_, PROCESS_KEY_, PROCESS_NAME_, DEPT_ID_,"
                            + " DEPT_NAME_, BIZ_TYPE_, VERSION_, STATUS_, DESCRIPTION_, CREATE_TIME_, UPDATE_TIME_)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, key, name, deptId, deptName, emptyToNull(bizType), actualVersion, status,
                    emptyToNull(description), now, now);
            log.info("注册表新增：{} {} dept={} v{}", id, key, deptId, actualVersion);
            return id;
        }

        BizSubProcessDef exist = findById(id);
        if (exist == null) {
            throw new BusinessException("登记记录不存在，可能已被其他人删除，请刷新后重试");
        }
        if (!Objects.equals(exist.getProcessKey(), key)) {
            throw new BusinessException("流程 key 不允许修改，请删除后重新登记");
        }
        int actualVersion = version > 0 ? version : exist.getVersion();
        Integer dup = jdbc.queryForObject(
                "SELECT COUNT(*) FROM BIZ_SUBPROCESS_DEF_ WHERE PROCESS_KEY_ = ? AND VERSION_ = ? AND ID_ <> ?",
                Integer.class, key, actualVersion, id);
        if (dup != null && dup > 0) {
            throw new BusinessException("该流程 key 的 v" + actualVersion + " 已被另一条记录占用");
        }
        jdbc.update("UPDATE BIZ_SUBPROCESS_DEF_ SET PROCESS_NAME_ = ?, DEPT_ID_ = ?, DEPT_NAME_ = ?,"
                        + " BIZ_TYPE_ = ?, VERSION_ = ?, STATUS_ = ?, DESCRIPTION_ = ?, UPDATE_TIME_ = ?"
                        + " WHERE ID_ = ?",
                name, deptId, deptName, emptyToNull(bizType), actualVersion, status,
                emptyToNull(description), now, id);
        log.info("注册表更新：{} {} dept={} v{}{}", id, key, deptId, actualVersion,
                status == BizSubProcessDef.STATUS_ENABLED ? "" : "（停用）");
        return id;
    }

    /** 启用 / 停用：只影响后续发起，已在跑的实例继续执行 */
    @Transactional
    public void setStatus(String id, int status) {
        if (status != BizSubProcessDef.STATUS_ENABLED && status != BizSubProcessDef.STATUS_DISABLED) {
            throw new BusinessException("状态只能是启用或停用");
        }
        BizSubProcessDef exist = findById(id);
        if (exist == null) {
            throw new BusinessException("登记记录不存在，可能已被其他人删除，请刷新后重试");
        }
        if (status == BizSubProcessDef.STATUS_ENABLED && DEPT_UNKNOWN.equals(exist.getDeptId())) {
            throw new BusinessException("该流程尚未绑定部门，请先编辑指定归属部门再启用");
        }
        jdbc.update("UPDATE BIZ_SUBPROCESS_DEF_ SET STATUS_ = ?, UPDATE_TIME_ = ? WHERE ID_ = ?",
                status, new Timestamp(System.currentTimeMillis()), id);
    }

    /** 物理删除一条登记（配置数据，保留历史没有意义；删除后可在菜单重新登记） */
    @Transactional
    public void delete(String id) {
        BizSubProcessDef exist = findById(id);
        if (exist == null) {
            throw new BusinessException("登记记录不存在，可能已被其他人删除");
        }
        jdbc.update("DELETE FROM BIZ_SUBPROCESS_DEF_ WHERE ID_ = ?", id);
        log.info("注册表删除：{} {}", id, exist.getProcessKey());
    }

    /**
     * 部署后自动登记。由 {@code DeptSubProcessDeploymentRegistrar} 在部署完成后调用。
     *
     * <p>规则与手工维护共用同一张表和同一套校验语义，但只动"流程侧"的字段：
     * 版本号与流程名称跟随引擎，{@code DEPT_ID_} / {@code STATUS_} / {@code DESCRIPTION_}
     * 这类人工判断的字段一律保留——自动部署不能把管理员停用过的流程又悄悄打开。</p>
     *
     * @param definitions    本次部署产出的流程定义
     * @param deptIdByKey    已从 BPMN 解析出的归属部门；未命中的 key 不在 map 中
     * @return 新增 / 更新 / 跳过 的条数
     */
    @Transactional
    public Map<String, Integer> registerDeployed(List<ProcessDefinition> definitions,
                                                 Map<String, String> deptIdByKey) {
        Map<String, Integer> stat = new LinkedHashMap<>();
        int created = 0;
        int updated = 0;
        int skipped = 0;
        for (ProcessDefinition pd : definitions) {
            String key = pd.getKey();
            if (key == null || !key.startsWith("dept_comm_")) {
                continue;
            }
            String resolvedDept = deptIdByKey == null ? null : deptIdByKey.get(key);
            boolean bound = resolvedDept != null && !resolvedDept.isBlank();
            String deptId = bound ? resolvedDept : DEPT_UNKNOWN;
            String deptName = bound ? resolveDeptName(deptId) : "待指定部门";
            int engineVersion = pd.getVersion();
            String processName = pd.getName() == null || pd.getName().isBlank() ? key : pd.getName();
            Timestamp now = new Timestamp(System.currentTimeMillis());

            BizSubProcessDef latest = findLatestOfKey(key);
            if (latest == null) {
                jdbc.update("INSERT INTO BIZ_SUBPROCESS_DEF_ (ID_, PROCESS_KEY_, PROCESS_NAME_, DEPT_ID_,"
                                + " DEPT_NAME_, BIZ_TYPE_, VERSION_, STATUS_, DESCRIPTION_, CREATE_TIME_,"
                                + " UPDATE_TIME_) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        buildId(key, engineVersion), key, processName, deptId, deptName, null,
                        engineVersion, bound ? BizSubProcessDef.STATUS_ENABLED : BizSubProcessDef.STATUS_DISABLED,
                        bound ? "部署时自动登记" : "部署时自动登记，请补充归属部门",
                        now, now);
                created++;
                log.info("自动登记新流程：{} dept={} v{}", key, deptId, engineVersion);
                continue;
            }

            boolean versionChanged = !Objects.equals(latest.getVersion(), engineVersion);
            boolean nameChanged = !Objects.equals(latest.getProcessName(), processName);
            boolean deptResolved = DEPT_UNKNOWN.equals(latest.getDeptId()) && bound;
            if (!versionChanged && !nameChanged && !deptResolved) {
                skipped++;
                continue;
            }

            // 同一 key 的同一版本只能有一行；历史上若有同版本残留，先清掉再接管，
            // 否则 UPDATE 会撞唯一约束 UK_BIZ_SUBPROCESS_KEY。
            jdbc.update("DELETE FROM BIZ_SUBPROCESS_DEF_ WHERE PROCESS_KEY_ = ? AND VERSION_ = ? AND ID_ <> ?",
                    key, engineVersion, latest.getId());

            String nextDeptId = deptResolved ? deptId : latest.getDeptId();
            String nextDeptName = deptResolved ? deptName : latest.getDeptName();
            // 自动登记只补"未绑定"的部门，且不改变人工设置的启停状态
            int nextStatus = deptResolved ? BizSubProcessDef.STATUS_ENABLED : latest.getStatus();
            jdbc.update("UPDATE BIZ_SUBPROCESS_DEF_ SET PROCESS_NAME_ = ?, VERSION_ = ?, DEPT_ID_ = ?,"
                            + " DEPT_NAME_ = ?, STATUS_ = ?, UPDATE_TIME_ = ? WHERE ID_ = ?",
                    processName, engineVersion, nextDeptId, nextDeptName, nextStatus, now, latest.getId());
            updated++;
            log.info("自动更新流程登记：{} v{} → v{} dept={}", key, latest.getVersion(), engineVersion, nextDeptId);
        }
        stat.put("created", created);
        stat.put("updated", updated);
        stat.put("skipped", skipped);
        return stat;
    }

    /**
     * 单次部署的自动登记：只处理本次部署产出的定义。
     *
     * <p>由 {@code DeptSubProcessDeploymentRegistrar} 在部署命令结束后调用，
     * 因此这里同样只认 {@code dept_comm_} 前缀——同一个 war 里还会部署
     * {@code approval}、{@code dept_communication} 这类非注册表流程。</p>
     */
    @Transactional
    public Map<String, Integer> registerDeployment(String deploymentId) {
        if (deploymentId == null || deploymentId.isBlank()) {
            return Map.of("created", 0, "updated", 0, "skipped", 0);
        }
        List<ProcessDefinition> defs = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .list();
        return registerDeployed(defs, resolveDeptIds(defs));
    }

    /**
     * 全量同步：把引擎里所有 {@code dept_comm_} 流程的最新版本对齐到注册表。
     *
     * <p>两个用途：应用启动完成后的兜底（classpath 自动部署可能早于钩子生效）、
     * 以及管理员在菜单里手动"从引擎同步"。重复执行是安全的：
     * 没有变化时只累加 skipped，不会改人工维护的部门/状态/说明。</p>
     */
    @Transactional
    public Map<String, Integer> syncFromEngine() {
        List<ProcessDefinition> defs = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKeyLike("dept_comm_%")
                .latestVersion()
                .list();
        return registerDeployed(defs, resolveDeptIds(defs));
    }

    /**
     * 从 BPMN 的 {@code camunda:properties} 里读部门归属，key 用 {@code deptId}。
     *
     * <p>写法示例：
     * {@code <camunda:properties><camunda:property name="deptId" value="dept_legal"/></camunda:properties>}。
     * 没写的流程不报错，registration 会落到 {@link #DEPT_UNKNOWN} 等待人工指定——
     * 部门归属是业务判断，不能靠流程 key 猜。</p>
     */
    private Map<String, String> resolveDeptIds(List<ProcessDefinition> defs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (ProcessDefinition pd : defs) {
            String deptId = readDeptProperty(pd);
            if (deptId != null && !deptId.isBlank()) {
                result.put(pd.getKey(), deptId.trim());
            }
        }
        return result;
    }

    /**
     * 直接解析部署产出的 BPMN XML，不依赖 camunda-bpmn-model 的类路径。
     *
     * <p>只为了取一个扩展属性，用 DOM 足够；同时关掉 DOCTYPE，
     * 避免部署的 XML 里带外部实体时被解析器联网读取。</p>
     */
    private String readDeptProperty(ProcessDefinition pd) {
        try (InputStream in = repositoryService.getProcessModel(pd.getId())) {
            if (in == null) {
                return null;
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = factory.newDocumentBuilder().parse(in);
            var nodes = document.getElementsByTagNameNS("*", "property");
            for (int i = 0; i < nodes.getLength(); i++) {
                var element = (org.w3c.dom.Element) nodes.item(i);
                String name = element.getAttribute("name");
                if ("deptId".equalsIgnoreCase(name) || "dept_id".equalsIgnoreCase(name)) {
                    return element.getAttribute("value");
                }
            }
        } catch (Exception e) {
            // 解析失败只影响"能不能自动绑定部门"，不能影响部署本身
            log.warn("读取流程 {} 的部门扩展属性失败，按待指定部门处理", pd.getKey(), e);
        }
        return null;
    }

    /**
     * 读取某个子流程部署产出的 BPMN XML，供控制台查看流程图。
     *
     * <p>默认取引擎里的最新版本；显式传 version 时取指定版本——注册表里的
     * {@code VERSION_} 可能落后于引擎，管理员需要能核对"我登记的那一版长什么样"。
     * 只读引擎部署资源，不解析业务表。</p>
     *
     * @throws BusinessException 流程未部署或引擎中读不到 BPMN 文件
     */
    public Map<String, Object> loadBpmnXml(String processKey, Integer version) {
        if (processKey == null || processKey.isBlank()) {
            throw new BusinessException("流程 key 不能为空");
        }
        ProcessDefinitionQuery query = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processKey.trim());
        ProcessDefinition pd = version == null
                ? query.latestVersion().singleResult()
                : query.processDefinitionVersion(version).singleResult();
        if (pd == null) {
            throw new BusinessException("流程 " + processKey + " 尚未部署到引擎，无法查看流程图");
        }
        try (InputStream in = repositoryService.getProcessModel(pd.getId())) {
            if (in == null) {
                throw new BusinessException("引擎中没有该流程的 BPMN 文件");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("processKey", pd.getKey());
            result.put("processDefinitionId", pd.getId());
            result.put("processName", pd.getName());
            result.put("version", pd.getVersion());
            result.put("deploymentId", pd.getDeploymentId());
            result.put("resourceName", pd.getResourceName());
            result.put("xml", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("读取流程 {} 的 BPMN XML 失败", processKey, e);
            throw new BusinessException("读取流程图失败：" + e.getMessage());
        }
    }

    /** 该 key 的最大版本记录（不看启停），自动登记据此判断是否已存在 */
    public BizSubProcessDef findLatestOfKey(String processKey) {
        List<BizSubProcessDef> list = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM BIZ_SUBPROCESS_DEF_"
                        + " WHERE PROCESS_KEY_ = ? ORDER BY VERSION_ DESC",
                MAPPER, processKey);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 部门候选（维护菜单的下拉）：类型为 DEPARTMENT 的组 + 成员数。
     *
     * <p>成员数为 0 的部门会在页面上标出：登记了流程但部门里没人，
     * 发起后文书节点无人可办，这类"配了等于没配"的情况要先在列表里看见。</p>
     */
    public List<Map<String, Object>> listDepartmentGroups(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        List<Group> groups = identityService.createGroupQuery()
                .groupType("DEPARTMENT")
                .groupNameLike("%" + kw + "%")
                .listPage(0, 200);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Group g : groups) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId());
            m.put("name", g.getName() == null ? g.getId() : g.getName());
            m.put("memberCount", identityService.createUserQuery().memberOfGroup(g.getId()).count());
            result.add(m);
        }
        return result;
    }

    /** 部门组必须存在且类型为 DEPARTMENT；类型字段在 ACT_ID_GROUP_.TYPE_ 上 */
    public boolean isDepartmentGroup(String groupId) {
        return identityService.createGroupQuery()
                .groupId(groupId).groupType("DEPARTMENT").count() > 0;
    }

    private String resolveDeptName(String deptId) {
        if (BizSubProcessDef.DEPT_ALL.equals(deptId)) {
            return "全部门通用";
        }
        if (DEPT_UNKNOWN.equals(deptId)) {
            return "待指定部门";
        }
        org.camunda.bpm.engine.identity.Group group = identityService.createGroupQuery()
                .groupId(deptId).singleResult();
        return group == null || group.getName() == null ? deptId : group.getName();
    }

    /** 新增记录时若未填版本，取引擎里已部署的最新版本；未部署则记 v1 */
    private int detectEngineVersion(String processKey) {
        ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processKey).latestVersion().singleResult();
        return pd == null ? 1 : pd.getVersion();
    }

    /** ID_ 形如 sp_dept_comm_xxx_v3；超长时截断并附短哈希，保证可读且不撞主键 */
    private String buildId(String processKey, int version) {
        String candidate = "sp_" + processKey + "_v" + version;
        if (candidate.length() <= ID_MAX_LENGTH) {
            return candidate;
        }
        String hash = Integer.toHexString(candidate.hashCode());
        int keep = ID_MAX_LENGTH - hash.length() - 1;
        return candidate.substring(0, keep) + "_" + hash;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
