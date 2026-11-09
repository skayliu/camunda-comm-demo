package com.example.camundademo.controller;

import com.example.camundademo.entity.BizSubProcessDef;
import com.example.camundademo.security.AdminAccess;
import com.example.camundademo.service.BusinessException;
import com.example.camundademo.service.SubProcessDefService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制台 · 部门子流程注册表维护接口（BIZ_SUBPROCESS_DEF_）。
 *
 * <p>这是"配置面"，不是业务面：所有方法都要求 {@code camunda-admin} 成员身份，
 * 且不允许通过本接口发起任何流程。业务侧读取注册表仍走
 * {@code /api/comm/subprocesses}（登录即可，只返回启用中的记录）。</p>
 */
@RestController
@RequestMapping("/api/admin/subprocess-defs")
public class SubProcessDefAdminController {

    private final SubProcessDefService subProcessDefService;
    private final AdminAccess adminAccess;

    public SubProcessDefAdminController(SubProcessDefService subProcessDefService,
                                        AdminAccess adminAccess) {
        this.subProcessDefService = subProcessDefService;
        this.adminAccess = adminAccess;
    }

    /** 全部登记记录（含停用），附带引擎侧最新版本与是否已部署 */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                          Authentication authentication) {
        adminAccess.require(authentication);
        return subProcessDefService.listAll(keyword);
    }

    /**
     * 归属部门候选：只返回类型为 DEPARTMENT 的组。
     *
     * <p>不直接复用 {@code /api/comm/departments}：那个接口按关键字模糊查前 50 条，
     * 面向"发起沟通时选一个部门"；这里是配置场景，需要有成员数、便于判断选错没有。</p>
     */
    @GetMapping("/departments")
    public List<Map<String, Object>> departments(@RequestParam(required = false) String keyword,
                                                 Authentication authentication) {
        adminAccess.require(authentication);
        return subProcessDefService.listDepartmentGroups(keyword);
    }

    /**
     * 查看流程图：返回该 key 在引擎中部署的 BPMN XML。
     *
     * <p>不传 version 取最新版本；前端登记版本与引擎版本不一致时，
     * 可以显式带上 {@code version} 核对历史版本。BPMN 里可能包含
     * 内部岗位组、表单 key 等配置，所以和其余管理接口一样只对
     * {@code camunda-admin} 开放。</p>
     */
    @GetMapping("/{processKey}/bpmn-xml")
    public Map<String, Object> bpmnXml(@PathVariable String processKey,
                                       @RequestParam(required = false) Integer version,
                                       Authentication authentication) {
        adminAccess.require(authentication);
        return subProcessDefService.loadBpmnXml(processKey, version);
    }

    /**
     * 新增或修改一条登记。
     *
     * <p>{@code id} 为空是新增，否则更新；更新时 {@code processKey} 不允许变，
     * 校验在 service 里统一完成，菜单和自动登记共用同一套规则。</p>
     */
    @PostMapping
    public Map<String, Object> save(@RequestBody BizSubProcessDef form, Authentication authentication) {
        String operator = adminAccess.require(authentication);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", subProcessDefService.save(form));
        result.put("operator", operator);
        return result;
    }

    /** 启用 / 停用：只影响后续发起，已在跑的实例不受影响 */
    @PostMapping("/{id}/status")
    public Map<String, Object> setStatus(@PathVariable String id,
                                         @RequestBody Map<String, Object> body,
                                         Authentication authentication) {
        adminAccess.require(authentication);
        Object raw = body == null ? null : body.get("status");
        int status;
        if (raw instanceof Number number) {
            status = number.intValue();
        } else if (raw != null && "true".equalsIgnoreCase(String.valueOf(raw).trim())) {
            status = BizSubProcessDef.STATUS_ENABLED;
        } else if (raw != null && "false".equalsIgnoreCase(String.valueOf(raw).trim())) {
            status = BizSubProcessDef.STATUS_DISABLED;
        } else {
            try {
                status = Integer.parseInt(String.valueOf(raw).trim());
            } catch (RuntimeException e) {
                throw new BusinessException("状态参数不正确");
            }
        }
        subProcessDefService.setStatus(id, status);
        return Map.of("success", true, "status", status);
    }

    /** 删除登记：配置数据，物理删除；流程定义本身仍在引擎里 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id, Authentication authentication) {
        adminAccess.require(authentication);
        subProcessDefService.delete(id);
        return Map.of("success", true);
    }

    /**
     * 从引擎重新同步：扫描所有 {@code dept_comm_} 开头的已部署流程，按 key 做一次 upsert。
     *
     * <p>用于两种情况：部署钩子漏掉（例如引擎外直接操作数据库），
     * 或管理员想知道"引擎里到底有哪些流程没登记"。</p>
     */
    @PostMapping("/sync")
    public Map<String, Object> sync(Authentication authentication) {
        adminAccess.require(authentication);
        Map<String, Integer> stat = subProcessDefService.syncFromEngine();
        Map<String, Object> result = new LinkedHashMap<>(stat);
        result.put("success", true);
        return result;
    }

    @ExceptionHandler(BusinessException.class)
    public Map<String, Object> handleBusiness(BusinessException e) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("error", true);
        r.put("message", e.getMessage());
        return r;
    }

    /**
     * 权限不足要给出可读原因。
     *
     * <p>Spring Boot 默认不把 {@code ResponseStatusException} 的 reason 写进响应体，
     * 前端只能显示"没有权限执行该操作"；这里补上具体组名，管理员才知道该找谁授权。</p>
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", true);
        body.put("message", e.getReason() == null ? "没有权限执行该操作" : e.getReason());
        return ResponseEntity.status(e.getStatusCode()).body(body);
    }
}
