package com.example.camundademo;

import com.example.camundademo.security.DemoUserInitializer;
import com.example.camundademo.support.TestSession;
import org.camunda.bpm.engine.RepositoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台「部门沟通流程登记」菜单的接口与自动登记用例。
 *
 * <p>这一组用例锁住四件事，任何一件破了都会直接体现在界面上：</p>
 * <ol>
 *   <li><b>权限</b>：未登录 401；登录但不是 camunda-admin 一律 403，读也不行；</li>
 *   <li><b>维护</b>：管理员能新增、改状态、删除，且流程 key 不允许改；</li>
 *   <li><b>自动登记</b>：以 {@code dept_comm_} 开头的流程部署后自动出现在表里，
 *       同 key 再部署新版本时更新原记录而不是插新行；</li>
 *   <li><b>不越权改写</b>：重新部署不会把管理员手工停用的记录重新启用，
 *       非 {@code dept_comm_} 前缀的流程不会被登记。</li>
 * </ol>
 *
 * <p>单独一个内存库（{@code deptdefadmin}）：自动登记会往表里写演示数据，
 * 与业务链路的用例共库会互相影响。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:deptdefadmin;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class DeptSubProcessDefAdminTest {

    private static final String AUTO_KEY = "dept_comm_auto_admin_test";
    private static final String IGNORED_KEY = "approval_not_dept_comm";

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DemoUserInitializer demoUserInitializer;

    @BeforeEach
    void ensureDemoIdentity() {
        demoUserInitializer.run(null);
    }

    // ==================== 1. 权限 ====================

    @Test
    void anonymousIsRejected() {
        ResponseEntity<Map<String, Object>> res = exchange("/api/admin/subprocess-defs", HttpMethod.GET, null, null);
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode(), "未登录不应看到注册表");
    }

    @Test
    void nonAdminCanWedNeitherReadNorWrite() {
        ResponseEntity<Map<String, Object>> list =
                exchange("/api/admin/subprocess-defs", HttpMethod.GET, null, "alice");
        assertEquals(HttpStatus.FORBIDDEN, list.getStatusCode(), "普通用户不应读到注册表");
        assertNotNull(list.getBody());
        assertTrue(String.valueOf(list.getBody().get("message")).contains("camunda-admin"),
                "403 要说明需要哪个组: " + list.getBody());

        Map<String, Object> form = new LinkedHashMap<>();
        form.put("processKey", "dept_comm_hacked");
        form.put("processName", "越权登记");
        form.put("deptId", "dept_it");
        ResponseEntity<Map<String, Object>> save =
                exchange("/api/admin/subprocess-defs", HttpMethod.POST, form, "alice");
        assertEquals(HttpStatus.FORBIDDEN, save.getStatusCode(), "普通用户不应能登记流程");
        assertEquals(0, countByKey("dept_comm_hacked"), "403 后不应留下任何数据");
    }

    @Test
    void adminSeesRegistryWithEngineState() {
        List<Map<String, Object>> rows = listRows("/api/admin/subprocess-defs", "admin");
        assertTrue(rows.size() >= 3, "V5 种子数据应至少三条: " + rows);

        Map<String, Object> general = rows.stream()
                .filter(row -> "dept_comm_general".equals(row.get("processKey")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少通用流程登记: " + rows));
        assertEquals(Boolean.TRUE, general.get("deployed"), "通用流程应已部署到引擎");
        assertNotNull(general.get("engineVersion"), "列表要带引擎版本，便于发现版本对不齐");
        assertEquals(Boolean.TRUE, general.get("general"), "通用流程应标记 general");
    }

    // ==================== 2. 维护 ====================

    @Test
    void adminCanCreateToggleAndDelete() {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("processKey", "dept_comm_admin_manual");
        form.put("processName", "手工登记测试流程");
        form.put("deptId", "dept_it");
        form.put("bizType", "GENERAL");
        form.put("version", 0);
        form.put("status", 1);
        form.put("description", "由集成测试创建");

        ResponseEntity<Map<String, Object>> created =
                exchange("/api/admin/subprocess-defs", HttpMethod.POST, form, "admin");
        assertEquals(HttpStatus.OK, created.getStatusCode(), "新增失败: " + created.getBody());
        String id = String.valueOf(created.getBody().get("id"));
        assertEquals(1, countByKey("dept_comm_admin_manual"), "新增后表里应有一条");

        // 未部署到引擎时也会登记，但列表要标出 deployed=false，而不是让用户到发起时才看到报错
        Map<String, Object> row = rowOf(id);
        assertEquals(Boolean.FALSE, row.get("deployed"), "未部署流程应显示 deployed=false");

        ResponseEntity<Map<String, Object>> disabled = exchange(
                "/api/admin/subprocess-defs/" + id + "/status", HttpMethod.POST, Map.of("status", 0), "admin");
        assertEquals(HttpStatus.OK, disabled.getStatusCode(), "停用失败: " + disabled.getBody());
        assertEquals(0, ((Number) rowOf(id).get("status")).intValue(), "停用后状态应为 0");

        ResponseEntity<Map<String, Object>> removed = exchange(
                "/api/admin/subprocess-defs/" + id, HttpMethod.DELETE, null, "admin");
        assertEquals(HttpStatus.OK, removed.getStatusCode(), "删除失败: " + removed.getBody());
        assertEquals(0, countByKey("dept_comm_admin_manual"), "删除后不应残留");
    }

    @Test
    void processKeyCannotBeChangedOnUpdate() {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("processKey", "dept_comm_key_fixed");
        form.put("processName", "key 不可变测试");
        form.put("deptId", "dept_it");
        form.put("version", 0);
        String id = String.valueOf(exchange("/api/admin/subprocess-defs", HttpMethod.POST, form, "admin")
                .getBody().get("id"));

        Map<String, Object> edit = new LinkedHashMap<>(form);
        edit.put("id", id);
        edit.put("processKey", "dept_comm_key_changed");
        ResponseEntity<Map<String, Object>> res =
                exchange("/api/admin/subprocess-defs", HttpMethod.POST, edit, "admin");
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(Boolean.TRUE, res.getBody().get("error"), "改 key 必须被拒绝: " + res.getBody());
        assertTrue(String.valueOf(res.getBody().get("message")).contains("不允许修改"));
    }

    @Test
    void departmentCandidatesOnlyContainDepartmentGroups() {
        List<Map<String, Object>> rows =
                listRows("/api/admin/subprocess-defs/departments", "admin");
        List<String> ids = rows.stream().map(row -> String.valueOf(row.get("id"))).toList();
        assertTrue(ids.contains("dept_it"), "应包含 IT 部: " + ids);
        assertFalse(ids.contains("legal_compliance"), "岗位组不能出现在部门候选里: " + ids);
        assertFalse(ids.contains("dept_it_clerk"), "文书组是 WORKFLOW，不能当部门: " + ids);
    }

    // ==================== 3. 部署自动登记 ====================

    @Test
    void deploymentOfDeptCommKeyIsRegisteredAutomatically() {
        assertEquals(0, countByKey(AUTO_KEY), "前置条件：该 key 尚未登记");

        deploy(AUTO_KEY, "自动登记流程", "dept_it", 1);
        Map<String, Object> v1 = awaitVersion(AUTO_KEY, 1);
        assertEquals("dept_it", v1.get("DEPT_ID_"), "应从 BPMN 的 deptId 属性解析出部门");
        assertEquals("IT 部", v1.get("DEPT_NAME_"), "部门名应回填");
        assertEquals("自动登记流程", v1.get("PROCESS_NAME_"), "流程名应取部署时的名称");
        String idV1 = String.valueOf(v1.get("ID_"));

        // 同 key 再部署：引擎版本升到 2，注册表应更新同一行，而不是插入新行
        deploy(AUTO_KEY, "自动登记流程 v2", "dept_it", 2);
        Map<String, Object> v2 = awaitVersion(AUTO_KEY, 2);
        assertEquals(idV1, String.valueOf(v2.get("ID_")), "新版本应沿用原记录主键，避免登记主键漂移");
        assertEquals(1, countByKey(AUTO_KEY), "同一 key 不应出现两条记录: " + countByKey(AUTO_KEY));
        assertEquals("自动登记流程 v2", v2.get("PROCESS_NAME_"), "流程名应跟随引擎更新");
    }

    @Test
    void redeployKeepsManualDisabledState() {
        deploy(AUTO_KEY + "_disabled", "停用保持测试", "dept_it", 1);
        Map<String, Object> v1 = awaitVersion(AUTO_KEY + "_disabled", 1);
        String id = String.valueOf(v1.get("ID_"));

        // 模拟管理员在菜单里停用：自动登记不能把它悄悄打开
        jdbc.update("UPDATE BIZ_SUBPROCESS_DEF_ SET STATUS_ = 0 WHERE ID_ = ?", id);

        deploy(AUTO_KEY + "_disabled", "停用保持测试 v2", "dept_it", 2);
        Map<String, Object> v2 = awaitVersion(AUTO_KEY + "_disabled", 2);
        assertEquals(0, ((Number) v2.get("STATUS_")).intValue(),
                "重新部署后仍应保持停用，否则管理员停用的流程会在下次发版后复活");
    }

    @Test
    void nonDeptCommKeyIsNotRegistered() {
        deploy(IGNORED_KEY, "非部门沟通流程", "dept_it", 1);
        // 自动登记是异步的，给它一个宽限期再断言"没有"
        sleep(3000);
        assertEquals(0, countByKey(IGNORED_KEY), "非 dept_comm_ 前缀的流程不应进入注册表");
    }

    // ==================== 4. 流程图 ====================

    @Test
    void adminCanReadDeployedBpmnXml() {
        ResponseEntity<Map<String, Object>> res = exchange(
                "/api/admin/subprocess-defs/dept_comm_general/bpmn-xml", HttpMethod.GET, null, "admin");
        assertEquals(HttpStatus.OK, res.getStatusCode(), "管理员应能读到流程图: " + res.getBody());
        Map<String, Object> body = res.getBody();
        assertNotNull(body, "流程图响应为空");
        String xml = String.valueOf(body.get("xml"));
        assertTrue(xml.contains("dept_comm_general"), "XML 应包含流程 key");
        assertTrue(xml.contains("<bpmn:process") || xml.contains("<process"), "应返回 BPMN 2.0 定义");
        assertEquals("dept_comm_general", body.get("processKey"));
        assertNotNull(body.get("processDefinitionId"), "应返回流程定义 ID，便于核对具体版本");
        assertTrue(((Number) body.get("version")).intValue() >= 1, "应返回引擎版本号");
    }

    @Test
    void bpmnXmlIsAdminOnly() {
        ResponseEntity<Map<String, Object>> res = exchange(
                "/api/admin/subprocess-defs/dept_comm_general/bpmn-xml", HttpMethod.GET, null, "alice");
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode(), "普通用户不应能读流程图: " + res.getBody());
        assertNotNull(res.getBody());
        assertTrue(String.valueOf(res.getBody().get("message")).contains("camunda-admin"));
    }

    @Test
    void bpmnXmlExplainsMissingDeployment() {
        ResponseEntity<Map<String, Object>> res = exchange(
                "/api/admin/subprocess-defs/dept_comm_never_deployed/bpmn-xml", HttpMethod.GET, null, "admin");
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(Boolean.TRUE, res.getBody().get("error"), "未部署不应返回 500: " + res.getBody());
        assertTrue(String.valueOf(res.getBody().get("message")).contains("尚未部署"),
                "提示要说明是部署缺失，而不是泛化的读取失败: " + res.getBody());
    }

    // ==================== 辅助方法 ====================

    /**
     * 部署一个最小可执行流程。
     *
     * <p>刻意每次都新建部署（而不是改已有定义），让引擎自然把版本号 +1，
     * 从而验证注册表的版本跟随逻辑。</p>
     */
    private void deploy(String key, String name, String deptId, int expectVersion) {
        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                             targetNamespace="http://example.com/deptdef">
                  <process id="%s" name="%s" isExecutable="true" camunda:historyTimeToLive="180">
                    <extensionElements>
                      <camunda:properties>
                        <camunda:property name="deptId" value="%s"/>
                      </camunda:properties>
                    </extensionElements>
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, name, deptId);

        var deployment = repositoryService.createDeployment()
                .name(name)
                .addString(key + ".bpmn", bpmn)
                .deploy();
        assertEquals(expectVersion, repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult().getVersion(),
                "引擎版本号与用例预期不一致，后续断言会失去意义");
    }

    /** 轮询等待自动登记落库（部署后由后台线程 2 秒一轮消费） */
    private Map<String, Object> awaitVersion(String key, int expectVersion) {
        long deadline = System.currentTimeMillis() + 20_000;
        Map<String, Object> last = null;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM BIZ_SUBPROCESS_DEF_ WHERE PROCESS_KEY_ = ?", key);
            if (!rows.isEmpty()) {
                last = rows.get(0);
                Object version = last.get("VERSION_");
                if (version instanceof Number number && number.intValue() == expectVersion) {
                    return last;
                }
            }
            sleep(300);
        }
        throw new IllegalStateException("等待自动登记 " + key + " v" + expectVersion
                + " 超时，最后一条记录: " + last);
    }

    private int countByKey(String key) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM BIZ_SUBPROCESS_DEF_ WHERE PROCESS_KEY_ = ?", Integer.class, key);
        return n == null ? 0 : n;
    }

    private Map<String, Object> rowOf(String id) {
        return listRows("/api/admin/subprocess-defs", "admin").stream()
                .filter(row -> id.equals(row.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("列表中找不到登记 " + id));
    }

    private List<Map<String, Object>> listRows(String path, String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, TestSession.cookie(port, user));
        headers.set("X-XSRF-TOKEN", TestSession.csrfToken(port, user));
        ResponseEntity<List<Map<String, Object>>> res = rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<>() {
                });
        assertEquals(HttpStatus.OK, res.getStatusCode(), "查询失败: " + res.getBody());
        assertNotNull(res.getBody(), "查询结果为空");
        return res.getBody();
    }

    private ResponseEntity<Map<String, Object>> exchange(String path, HttpMethod method,
                                                         Object body, String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (user != null) {
            headers.set(HttpHeaders.COOKIE, TestSession.cookie(port, user));
            headers.set("X-XSRF-TOKEN", TestSession.csrfToken(port, user));
        }
        ResponseEntity<Map<String, Object>> res = rest.exchange(path, method,
                new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() {
                });
        return res;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
