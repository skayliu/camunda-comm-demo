# 部门沟通子流程对照表（通用 / 法务 / 财务）

> 本文对照工程内三条已落地的部门沟通子流程：一条所有部门可用的通用流程，两条部门专属流程。
> 数据来源：`src/main/resources/processes/*.bpmn`、`V5__dept_subprocess_registry.sql`、
> `DeptSubTaskService#nodeType`、`DemoUserInitializer`，以及三个冒烟脚本的实测输出。
> 最后更新：2026-11-08（财务链路首次跑通）。

## 一、三条流程速览

| 维度 | 通用部门沟通子流程 | 法务部合同审查流程 | 财务部预算审核流程 |
|---|---|---|---|
| BPMN process id | `dept_comm_general` | `dept_comm_legal_contract` | `dept_comm_finance_budget` |
| 注册表 ID | `sp_general_v1` | `sp_legal_contract_v1` | `sp_finance_budget_v1` |
| 适用部门 `DEPT_ID_` | `*`（全部门） | `dept_legal` 法务部 | `dept_finance` 财务部 |
| 事项类型 `BIZ_TYPE_` | `GENERAL` | `CONTRACT` | `BUDGET` |
| 节点数 | 3（含并行多实例） | 4 | 4 |
| 节点排布 | 分发 → 经办（并行多实例）→ 汇总 | 串行固定岗位 | 串行固定岗位 |
| 经办人怎么来 | 文书在 UI 里动态选本部门成员 | BPMN 写死 `candidateGroups` | BPMN 写死 `candidateGroups` |
| 能否退回补充 / 重新分发 | 可以（3 种汇总动作） | 否（直接结束） | 否（直接结束） |
| 汇总节点办理人 | `${clerkUserId}` = 部门文书 | 同左 | 同左 |
| 冒烟脚本 | `dept-comm-smoke.mjs` | `dept-comm-legal-smoke.mjs` | `dept-comm-finance-smoke.mjs` |

三条流程共用同一套骨架：开始事件都挂 `deptSubProcessStartListener`（校验入参、解析 `clerkUserId`、锁主待办），
结束事件都挂 `deptSubProcessEndListener`（回写沟通记录、解锁主待办）。差异只在中间节点。

## 二、节点级对照（含面板类型与接口）

`nodeType` 由 `DeptSubTaskService#nodeType` 按 taskDefinitionKey 判定：
`Task_ClerkDispatch` → DISPATCH，`Task_HandlerOpinion` → HANDLER，
key 以 `Summary` 结尾 → SUMMARY，其余 → NODE（专属流程的中间节点）。

| 顺序 | 通用流程 | 法务部 | 财务部 |
|---|---|---|---|
| 1 | `Task_ClerkDispatch` 部门文书收文分发<br>NODE：DISPATCH · 面板「选择经办人」<br>assignee `${clerkUserId}`<br>接口 `/dispatch` | `Task_LegalCompliance` 合规初审<br>NODE · 面板标题=任务名<br>组 `legal_compliance`<br>接口 `/node-opinion` | `Task_FinanceBudget` 预算专员初审<br>NODE · 面板标题=任务名<br>组 `finance_budget`<br>接口 `/node-opinion` |
| 2 | `Task_HandlerOpinion` 经办人填写意见<br>HANDLER · 面板「经办意见」<br>并行多实例 `${handlerUserIds}`<br>接口 `/opinion` | `Task_LegalBusiness` 业务线专项审查<br>NODE<br>组 `legal_business`<br>`/node-opinion` | `Task_FinanceTreasury` 资金岗确认<br>NODE<br>组 `finance_treasury`<br>`/node-opinion` |
| 3 | `Task_ClerkSummary` 部门文书汇总反馈<br>SUMMARY · 面板「经办意见汇总」<br>assignee `${clerkUserId}`<br>接口 `/summary` | `Task_LegalManager` 法务经理复核<br>NODE<br>组 `legal_manager`<br>`/node-opinion` | `Task_FinanceManager` 财务经理审批<br>NODE<br>组 `finance_manager`<br>`/node-opinion` |
| 4 | — | `Task_LegalSummary` 出具正式法律意见<br>SUMMARY · 面板「经办意见汇总」<br>assignee `${clerkUserId}`<br>`/summary` | `Task_FinanceSummary` 财务文书汇总出意见<br>SUMMARY<br>assignee `${clerkUserId}`<br>`/summary` |

要点：

- **SUMMARY 的判定只看 key 后缀**，不看节点名。所以 `Task_FinanceSummary` 无论叫「财务文书汇总出意见」
  还是别的名字，都会渲染成汇总面板；新增专属流程时汇总节点 key 必须以 `Summary` 结尾。
- **NODE 节点的面板标题取 `taskName`**，所以 BPMN 里的节点名就是用户看到的标题，改名即改界面。
- 专属流程的汇总面板会把三个 NODE 的 `opinionMap` 条目按节点列出，脚本会逐条断言意见文本是否存在——
  这是「意见写了但没进汇总」最常见的失败点。

## 三、演示账号与岗位（`DemoUserInitializer`）

| 流程 | 节点 | 候选组 | 演示账号 | 说明 |
|---|---|---|---|---|
| 通用 | 文书分发 / 文书汇总 | `dept_<dept>_clerk` 或部门首成员 | 各部门文书 | 解析见 `SubProcessDefService#resolveClerk` |
| 通用 | 经办人意见 | UI 动态选择 | 部门成员 | 不走 BPMN 组 |
| 法务 | 合规初审 | `legal_compliance` | grace | — |
| 法务 | 业务线专项审查 | `legal_business` | frank | — |
| 法务 | 法务经理复核 | `legal_manager` | frank | 与上同账号，脚本需重新登录 |
| 法务 | 出具正式法律意见 | `dept_legal_clerk` | frank | 一人兼三岗 |
| 财务 | 预算专员初审 | `finance_budget` | henry | — |
| 财务 | 资金岗确认 | `finance_treasury` | mallory | 财务部唯一非 henry 的岗位 |
| 财务 | 财务经理审批 | `finance_manager` | henry | 同账号，脚本重新登录 |
| 财务 | 财务文书汇总 | `dept_finance_clerk` | henry | 一人兼三岗 |

账号-组明细：

```
henry   → dept_finance, dept_finance_clerk, finance_budget, finance_manager
mallory → dept_finance, finance_treasury
frank   → dept_legal, dept_legal_clerk, legal_business, legal_manager
grace   → dept_legal, legal_compliance
```

## 四、三个冒烟脚本

| 脚本 | 覆盖分支 | 步骤 | 本轮状态 | 截图目录 |
|---|---|---|---|---|
| `dept-comm-smoke.mjs` | DISPATCH / HANDLER / SUMMARY（通用） | 12 | 12/12 | `e2e-shots/dept-comm/` |
| `dept-comm-legal-smoke.mjs` | NODE ×3 + SUMMARY（法务） | 14 | 14/14 | `e2e-shots/dept-legal/` |
| `dept-comm-finance-smoke.mjs` | NODE ×3 + SUMMARY（财务） | 14 | **14/14** | `e2e-shots/dept-finance/` |

运行（需应用已在 8080 启动）：

```bash
cd frontend
node scripts/dept-comm-smoke.mjs
node scripts/dept-comm-legal-smoke.mjs
node scripts/dept-comm-finance-smoke.mjs
SMOKE_HEADED=1 node scripts/dept-comm-finance-smoke.mjs   # 有头模式
```

三个脚本刻意共用同一套外壳（API 造单、独立 browser context、标记短 ID 定位本次实例、报文日志判定、
关浏览器后再做 API 交叉验证），只换流程常量与账号，便于横向对照。

财务脚本相较法务脚本的两处调整：

1. `openDrawerWhere` 的遍历上限由 8 提到 12——henry 挂在 `dept_finance_clerk` / `finance_budget` /
   `finance_manager` 三个组上，历史待办比 frank 多；
2. 节点清单收成 `NODES` 数组循环办理，三个 NODE 的标题/组/账号/意见集中在一处，
   换真实组织时只改这个数组和四个环境变量。

## 五、换成真实组织要改什么

脚本侧全部走环境变量，不改代码：

| 变量 | 默认 | 用途 |
|---|---|---|
| `SMOKE_FINANCE_DEPT` | 财务部 | 弹窗里选的部门 |
| `SMOKE_FINANCE_SP` | 财务部预算审核流程 | 弹窗里选的子流程 |
| `SMOKE_BUDGET_USER` | henry | 预算专员初审 |
| `SMOKE_TREASURY_USER` | mallory | 资金岗确认 |
| `SMOKE_FINANCE_MANAGER_USER` | henry | 财务经理审批 |
| `SMOKE_FINANCE_CLERK_USER` | henry | 财务文书汇总 |
| `SMOKE_BASE` / `SMOKE_PASS` / `SMOKE_BROWSER` | — | 地址 / 密码 / 浏览器路径 |

业务侧必须同时满足（否则脚本会在「打开某节点面板」这一步失败，这是正确的失败方式）：

1. 组 `finance_budget`、`finance_treasury`、`finance_manager`、`dept_finance_clerk` 都有人；
2. `dept_finance_clerk` 的第一个成员就是期望的财务文书（`resolveClerk` 取列表首位，无排序保证）；
3. 注册表 `BIZ_SUBPROCESS_DEF_` 中 `sp_finance_budget_v1` 的 `STATUS_=1`，且 BPMN 已部署。

## 六、新增第四条专属流程的最小改动面

1. 新增 `src/main/resources/processes/dept_comm_<x>.bpmn`：开始/结束事件挂统一监听器，
   中间节点写 `candidateGroups`，汇总节点 key 以 `Summary` 结尾且 `assignee="${clerkUserId}"`；
2. 七个方言的 `V5__dept_subprocess_registry.sql` 各加一条注册记录（`DEPT_ID_` 填目标部门）；
3. 首次部署后需保证该部门有 `<deptId>_clerk` 组，否则文书会回退到部门第一个成员；
4. 脚本：复制 `dept-comm-finance-smoke.mjs`，改 `NODES`、四个账号常量与最终意见关键词。
   **主流程代码、`DeptSubTaskService`、前端组件均不需要改动。**

## 七、本轮实测（2026-11-08）

```
[0] 造单              实例 47b13da7… / 标记 47b13da7
[1] manager 发起      财务部（2 人）/ 财务部预算审核流程 → 部门沟通已发起：财务部
    主待办锁定        通过审批已禁用
[2] henry 预算初审    POST /node-opinion → 200
[3] mallory 资金岗    POST /node-opinion → 200
[4] henry 经理审批    POST /node-opinion → 200
[5] henry 文书汇总    三个节点意见均在面板内；POST /summary → 200
[6] manager 解锁      通过审批可点，财务部最终意见已回填
[7] 交叉验证          bizStatus=NORMAL
合计 14 步：通过 14，失败 0
```

已知边界：只验证了 H2 + 演示账号；脚本会真实写入数据（一条申请流程 + 一条部门沟通子流程），
不要对着生产库跑。
