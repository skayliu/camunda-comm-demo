# 部门沟通冒烟脚本：账号与环境变量清单

> 适用范围：`frontend/scripts/dept-comm-smoke.mjs`（通用）、`dept-comm-legal-smoke.mjs`（法务）、`dept-comm-finance-smoke.mjs`（财务）。
> 账号来源：`src/main/java/com/example/camundademo/security/DemoUserInitializer.java`。

## 一、账号的来源与口令规则

| 项 | 事实 |
|---|---|
| 账号创建者 | `DemoUserInitializer`（`ApplicationRunner`，随应用启动执行） |
| 口令 | 常量 `DEMO_PASSWORD = "demo"`，所有演示账号同一个口令 |
| 已存在时的行为 | `if (createUserQuery().userId(id).count() > 0) continue;` —— **跳过，不重置口令** |
| 组关系 | 每次启动都会 `ensureGroupMemberships()` 补齐，只增不减 |
| 组类型 | 四个部门组（`dept_*`）= `DEPARTMENT`，岗位组 = `WORKFLOW` |

第二条是本清单里最容易踩的坑：**如果库里已经存在同名账号且口令不是 `demo`，脚本会一直 401，而初始化器不会帮你改回来。** 换到已有数据的库时，先手工确认或删掉重跑。

## 二、三条链路各自用到的账号

三份脚本默认都读同一个口令 `demo`，起点都是 `manager` 发起的审批待办。

### 2.1 通用部门沟通（IT 部）

| 步骤 | 账号 | 口令 | 所属组 | 身份说明 |
|---|---|---|---|---|
| 发起 + 最终审批 | `manager` | demo | 无组 | BPMN 里 `camunda:assignee="manager"` 写死，不依赖组 |
| 分发 / 汇总 | `alice` | demo | `dept_it`, `dept_it_clerk` | 部门文书岗，`clerkUserId` 解析到它 |
| 经办 | `bob` | demo | `dept_it` | 脚本运行时在 UI 里选中 |
| （可选第二经办） | `alice` | demo | `dept_it` | 同部门另一成员，用于验证多实例 |

### 2.2 法务部合同审查

| 步骤 | 账号 | 口令 | 所属组 | 身份说明 |
|---|---|---|---|---|
| 发起 + 最终审批 | `manager` | demo | 无组 | 同上 |
| 合规初审 `Task_LegalCompliance` | `grace` | demo | `dept_legal`, `legal_compliance` | 岗位组承接 |
| 业务线专项审查 `Task_LegalBusiness` | `frank` | demo | `dept_legal`, `dept_legal_clerk`, `legal_business`, `legal_manager` | 一人挂多岗 |
| 法务经理复核 `Task_LegalManager` | `frank` | demo | 同上（`legal_manager`） | 脚本中重新登录一次 |
| 出具正式意见 `Task_LegalSummary` | `frank` | demo | 同上（`dept_legal_clerk`） | `${clerkUserId}` 解析到它 |

法务部只有两人，`frank` 承担三个节点，脚本因此对 `frank` 连续登录三次。**这是演示数据的人手限制，不是流程要求。**

### 2.3 财务部预算审核

| 步骤 | 账号 | 口令 | 所属组 | 身份说明 |
|---|---|---|---|---|
| 发起 + 最终审批 | `manager` | demo | 无组 | 同上 |
| 预算专员初审 `Task_FinanceBudget` | `henry` | demo | `dept_finance`, `dept_finance_clerk`, `finance_budget`, `finance_manager` | 一人挂多岗 |
| 资金岗确认 `Task_FinanceTreasury` | `mallory` | demo | `dept_finance`, `finance_treasury` | 岗位组承接 |
| 财务经理审批 `Task_FinanceManager` | `henry` | demo | 同上（`finance_manager`） | 重新登录 |
| 财务文书汇总 `Task_FinanceSummary` | `henry` | demo | 同上（`dept_finance_clerk`） | `${clerkUserId}` 解析到它 |

### 2.4 组 → 人名反查（排查「节点无人认领」时用）

| 组 ID | 组名 | 类型 | 成员 |
|---|---|---|---|
| `dept_it` | IT 部 | DEPARTMENT | alice, bob |
| `dept_admin` | 行政部 | DEPARTMENT | carol, dave |
| `dept_legal` | 法务部 | DEPARTMENT | frank, grace |
| `dept_finance` | 财务部 | DEPARTMENT | henry, mallory |
| `dept_it_clerk` | IT 部文书 | WORKFLOW | alice |
| `dept_admin_clerk` | 行政部文书 | WORKFLOW | carol |
| `dept_legal_clerk` | 法务部文书 | WORKFLOW | frank |
| `dept_finance_clerk` | 财务部文书 | WORKFLOW | henry |
| `legal_compliance` | 法务合规岗 | WORKFLOW | grace |
| `legal_business` | 法务业务线专员 | WORKFLOW | frank |
| `legal_manager` | 法务经理 | WORKFLOW | frank |
| `finance_budget` | 预算岗 | WORKFLOW | henry |
| `finance_treasury` | 资金岗 | WORKFLOW | mallory |
| `finance_manager` | 财务经理 | WORKFLOW | henry |
| `admin` | Application Administrators | WORKFLOW | admin |
| `camunda-admin` | Camunda BPM Administrators | WORKFLOW | admin |

## 三、其余演示账号（脚本未使用，可留作手工验证）

| 账号 | 口令 | 所属组 | 用途 |
|---|---|---|---|
| `admin` | demo | `admin`, `camunda-admin` | 后台管理、代理规则强制停用 |
| `applicant` ~ `applicant6` | demo | 无 | 发起申请，制造 manager 待办 |
| `finance` | demo | 无 | `finance_review` 节点 assignee 写死为它 |
| `carol` / `dave` | demo | `dept_admin`(+`_clerk`) | 行政部通用流程，可手工补测 |

## 四、环境变量对照表

### 4.1 三份脚本共用

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SMOKE_BASE` | `http://localhost:8080/app` | 前端地址 |
| `SMOKE_PASS` | `demo` | **全局唯一口令**，不能按账号区分 |
| `SMOKE_HEADED` | 未设置（无头） | 设 `1` 打开有头模式 |
| `SMOKE_BROWSER` | 未设置（Edge） | 指定浏览器通道 |

### 4.2 通用流程专属

| 变量 | 默认值 |
|---|---|
| `SMOKE_DEPT` | `IT 部` |
| `SMOKE_HANDLER` | `bob` |
| `SMOKE_KEEP_DATA` | 未设置 |

### 4.3 法务专属

| 变量 | 默认值 |
|---|---|
| `SMOKE_LEGAL_DEPT` | `法务部` |
| `SMOKE_LEGAL_SP` | `法务部合同审查流程` |
| `SMOKE_COMPLIANCE_USER` | `grace` |
| `SMOKE_LEGAL_USER` | `frank`（业务线 / 经理 / 汇总三节点共用） |

### 4.4 财务专属

| 变量 | 默认值 |
|---|---|
| `SMOKE_FINANCE_DEPT` | `财务部` |
| `SMOKE_FINANCE_SP` | `财务部预算审核流程` |
| `SMOKE_BUDGET_USER` | `henry` |
| `SMOKE_TREASURY_USER` | `mallory` |
| `SMOKE_FINANCE_MANAGER_USER` | `henry` |
| `SMOKE_FINANCE_CLERK_USER` | `henry` |

## 五、换真实组织时的参数化边界（重要）

可以只改环境变量就完成的：

- 部门名称、子流程名称；
- 法务/财务各岗位的承接人（`SMOKE_COMPLIANCE_USER`、`SMOKE_LEGAL_USER`、`SMOKE_BUDGET_USER`、`SMOKE_TREASURY_USER`、`SMOKE_FINANCE_MANAGER_USER`、`SMOKE_FINANCE_CLERK_USER`）；
- 通用流程的经办人（`SMOKE_HANDLER`）。

**目前不能只靠环境变量完成的（需改脚本）：**

| 硬编码点 | 位置 | 影响 |
|---|---|---|
| `manager` | 三份脚本的 `loginAs('manager')` / `apiLogin('manager')` | 发起人与最终审批人换不了 |
| `alice` | `dept-comm-smoke.mjs` 的分发与汇总 | 通用流程的文书换不了 |
| 单一口令 | `SMOKE_PASS` | 真实组织一人一密时无法跑 |
| 组映射 | `DemoUserInitializer` 常量 | 真实组织需由 LDAP/SSO 提供，不应再靠初始化器 |

三条脚本都隐含「节点由哪个组承接」的假设。若真实组织里某岗位组为空，脚本会在「打开某节点面板」处失败——这是正确失败方式，说明流程会停在那里，不是脚本缺陷。

## 六、上线前置校验（比跑脚本更快）

1. 账号能登录：`POST /app/login` 用目标账号 + 口令，返回 200；
2. 组有人：Camunda Admin 里按上表逐个查 `groupId` 的成员数，任一为 0 先补人；
3. 子流程已部署：`/api/comm/dept-sub/options?deptId=dept_legal` 能返回对应流程；
4. 再把 `SMOKE_BASE` 指向目标环境跑脚本。
