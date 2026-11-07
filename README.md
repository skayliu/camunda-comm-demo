# Camunda 7 旁路沟通 Demo（纯引擎原生MyBatis实现）

## 项目说明
这是一个基于官方 `camunda-get-started-spring-boot` 架构的可运行Demo，演示零BPMN侵入、零核心表修改的旁路沟通功能，完全使用Camunda自带MyBatis做持久化，不依赖MyBatis-Plus等额外ORM框架。

## 核心特性
- ✅ 零BPMN侵入：流程定义中无任何沟通节点/网关/消息事件
- ✅ 零核心表修改：不直接修改ACT_RU_TASK等引擎表，通过自定义扩展表存储状态
- ✅ 引擎层拦截：通过CommandInterceptor在引擎内核层面校验审批权限，无法绕过
- ✅ 任务类型：复用引擎原生 `taskDefinitionKey` 区分普通待办 / 沟通待办，未来可扩展待阅任务
- ✅ 三层关联：sourceTaskId(直接发起源) / rootTaskId(首发根源) / parentCommTaskId(父沟通)
- ✅ 状态机：全量收回/全部反馈→可审批；部分收回→继续暂停；多轮沟通
- ✅ 沟通任务完全旁路：无executionId，不影响主流程流转、网关汇聚、多实例完成

## 技术栈
- Camunda **7.24.0**（`camunda-bpm-spring-boot-starter-webapp` + `-rest`）
- Spring Boot **3.5.16** / **Java 17**
- H2 内存数据库（零依赖）；引擎表结构与业务扩展表统一由 **Flyway** 管理
- Camunda 自带 MyBatis（自定义实体 + SessionFactory 机制）
- 前端：**Vue 3.5 + ant-design-vue 4 + Vite 5**，构建产物输出到 `src/main/resources/static/app`，访问路径 `/app/`

> 版本以 `pom.xml` 为准。Camunda 7.24 已移除 `customMybatisXMLMappers`，
> 自定义 Mapper 需在 `postInit` 阶段显式装载，插件里已按此实现。

## 启动方式
```bash
cd camunda-comm-demo
mvn spring-boot:run
```

启动后访问：
- 待办中心（前端）: http://localhost:8080/app/
- Camunda Cockpit: http://localhost:8080/camunda （demo/demo）
- H2 控制台: http://localhost:8080/h2 （JDBC URL: jdbc:h2:mem:camundadb，用户名sa，密码空）

## 快速访问

前端构建产物位于 `src/main/resources/static/app`，由 Spring Boot 作为 classpath 静态资源映射到 `/`，因此 `static/app/index.html` 对应 `/app/index.html`。Vite 的 `base` 同为 `/app/`，资源引用与访问路径一致，无需额外配置 Nginx。

### 入口地址

| 用途 | 地址 |
|---|---|
| **前端入口（推荐）** | `http://localhost:8080/app/` |
| 显式指定首页 | `http://localhost:8080/app/index.html` |
| 根路径 | `http://localhost:8080/` → 302 到 `/app/` |
| 别名路径 | `http://localhost:8080/workbench` → 302 到 `/app/` |
| 静态资源 | `http://localhost:8080/app/assets/index-*.js` / `.css` |
| Camunda Cockpit | `http://localhost:8080/camunda`（`demo/demo`） |
| H2 控制台 | `http://localhost:8080/h2` |

`/app` 不带斜杠时会先重定向到 `/app/`，两者都能打开。根路径与 `/workbench` 的跳转在 `WebMvcConfig` 中配置。

### 页面路由（hash 模式）

路由使用 `createWebHashHistory()`，页面路径在 `#` 之后，刷新不会 404：

| 页面 | 地址 |
|---|---|
| 待办中心 | `http://localhost:8080/app/#/todo` |
| 我的已办 | `http://localhost:8080/app/#/done` |
| 我的发起 | `http://localhost:8080/app/#/started` |
| 代理设置 | `http://localhost:8080/app/#/agent` |
| 委托记录（我的委托 / 我的受托） | `http://localhost:8080/app/#/delegation` |
| 登录页 | `http://localhost:8080/app/#/login` |

### 认证边界

`/app/**` 是静态资源，`SecurityConfig` 中为 `permitAll()`，不登录也能打开页面；但页面调用的 `/api/**` 要求认证，未登录返回 401。前端路由守卫会等 `/api/auth/me` 的结果再决定是否放行，所以打开后会被导到 `#/login`。

登录走 `POST /api/auth/login`，校验通过后服务端建立会话（`JSESSIONID`，HttpOnly、SameSite=Lax、30 分钟无操作失效）；退出走 `POST /api/auth/logout`，服务端执行 `session.invalidate()` 并让浏览器丢弃 Cookie，而不是只清前端的一个标记。默认种子用户为 `demo / demo`。

`/api/**` 的写请求（POST / PUT / PATCH / DELETE）另需通过 **CSRF 校验**：采用双提交 Cookie，令牌存在 `XSRF-TOKEN` cookie 里（非 HttpOnly，前端可读），请求头 `X-XSRF-TOKEN` 必须与它完全一致。令牌用 `GET /api/auth/csrf` 获取（免认证，登录请求本身也要带）；缺失或不匹配返回 **403 `CSRF_INVALID`**。GET 请求不需要令牌，浏览器会自动带上 Cookie，所以页面里无需手工处理。

同一账号同一时间只保留 **一个** 有效会话：在另一台设备或另一个浏览器用同一账号登录成功后，旧会话会被标记过期，旧页面的下一个请求返回 401（`该账号已在其它设备登录，当前会话已失效`）并跳回登录页。因此演示脚本不要为同一账号并发保留两份 Cookie；也不要指望“一个浏览器登录 manager、另一个再登录 manager 后两边都能用”。不同账号之间互不影响。

### 三个容易踩的点

1. **别把 5173 当生产入口**：`5173` 是 Vite dev server，仅在 `npm run dev` 时存在，访问需带前缀 `http://localhost:5173/app/`，它只把 `/api` 代理到 8080。
2. **改了前端要重新构建**：8080 提供的是 `static/app` 里的构建产物，源码改动不会自动生效，需执行 `npm run build`（`outDir` 已指向该目录）。
3. **不要直接改 `target/classes/static/app`**：那是构建输出的同步副本，下次构建会被覆盖；改源码后重新构建即可。

## 测试

```bash
mvn test                          # 全量
mvn test -Dtest=CommunicationFlowTest   # 只跑沟通主链路
```

测试断言的是**引擎事实**而不只是接口 200：历史任务是否存在、删除原因是什么、
`taskDefinitionKey` 是否带 `#COMM`、`ACT_HI_ACTINST` 是否为空等。

## 演示场景（curl脚本）

> **身份取会话，不取请求体。** `/api/**` 使用服务端会话（`JSESSIONID`），登录后的认证用户即操作人，请求体里的 `initiator` / `userId` / `operator` 已不再被读取；`/api/comm/demo/tasks` 还要求 `assignee` 必须等于当前用户，查他人待办会直接 403。演示账号（`applicant` / `manager` / `alice` / `bob` 等）密码统一为 `demo`，先按下面第 0 步为每个操作人各换一份 Cookie，再在对应段落用 `-b .cookie-<该段操作人>.txt`——换了操作人就要换 Cookie 文件。所有 **POST 写请求还必须带 `-H "X-XSRF-TOKEN: $CSRF_<该段操作人>"`**，漏掉会返回 403 `CSRF_INVALID`；GET 查询不需要。

> Windows 下请用 `curl.exe`（PowerShell 里 `curl` 是 `Invoke-WebRequest` 的别名，不认 `-c` / `-b`）。下面的 `$(...)`、`sed` 与多行 `-d '{...}'` 都是 **Git Bash / WSL** 语法；在 CMD 里执行，需把 `CSRF_*` 换成实际令牌值。

### 0. 先取令牌、再登录（每个操作人各一次）

```bash
# 0.1 取 CSRF 令牌：-c 把 XSRF-TOKEN 写进 cookie 文件，再从响应体里取出 token 值
CSRF_APPLICANT=$(curl.exe -s -c .cookie-applicant.txt http://localhost:8080/api/auth/csrf | sed -E 's/.*"token":"([^"]+)".*/\1/')
CSRF_MANAGER=$(curl.exe -s -c .cookie-manager.txt   http://localhost:8080/api/auth/csrf | sed -E 's/.*"token":"([^"]+)".*/\1/')
CSRF_ALICE=$(curl.exe -s -c .cookie-alice.txt       http://localhost:8080/api/auth/csrf | sed -E 's/.*"token":"([^"]+)".*/\1/')

# 0.2 带令牌登录：-b 读回 XSRF-TOKEN，-c 在同一文件里追加 JSESSIONID
curl.exe -b .cookie-applicant.txt -c .cookie-applicant.txt -H "X-XSRF-TOKEN: $CSRF_APPLICANT" -H "Content-Type: application/json" -d '{"userId":"applicant","password":"demo"}' -X POST http://localhost:8080/api/auth/login
curl.exe -b .cookie-manager.txt -c .cookie-manager.txt -H "X-XSRF-TOKEN: $CSRF_MANAGER" -H "Content-Type: application/json" -d '{"userId":"manager","password":"demo"}' -X POST http://localhost:8080/api/auth/login
curl.exe -b .cookie-alice.txt -c .cookie-alice.txt -H "X-XSRF-TOKEN: $CSRF_ALICE" -H "Content-Type: application/json" -d '{"userId":"alice","password":"demo"}' -X POST http://localhost:8080/api/auth/login

# 0.3 确认会话：能返回 userId 即登录成功
curl.exe -b .cookie-applicant.txt http://localhost:8080/api/auth/me
```

> 令牌**不绑会话**：服务端只比对「Cookie 里的 `XSRF-TOKEN`」与「请求头的 `X-XSRF-TOKEN`」，两者一致即放行。本脚本仍按操作人各取一份，好处是 cookie 文件与变量一一对应，不会串。
> 会话 30 分钟无操作失效，超时后重跑 0.2 对应那一行；若返回 `403 CSRF_INVALID`，重跑 0.1 再重跑对应的 0.2（0.1 会覆盖同名 cookie 文件，所以必须重新登录一次）。
> 退出登录也是写请求，同样要带令牌：`curl.exe -b .cookie-manager.txt -H "X-XSRF-TOKEN: $CSRF_MANAGER" -X POST http://localhost:8080/api/auth/logout`

### 1. 启动流程（申请人：applicant）
```bash
curl.exe -b .cookie-applicant.txt -H "X-XSRF-TOKEN: $CSRF_APPLICANT" -X POST "http://localhost:8080/api/comm/demo/start"
# 返回: {"processInstanceId":"xxx"}
```
记录返回的 `processInstanceId`（记为PID）。发起人即认证用户 `applicant`，`?initiator=` 参数已废弃。

### 2. 申请人完成"填写申请"，流程流转到经理审批
```bash
# 查applicant的待办
curl.exe -b .cookie-applicant.txt "http://localhost:8080/api/comm/demo/tasks?assignee=applicant"
# 获取apply节点的taskId（记为APPLY_TASK_ID）

# 完成apply
curl.exe -b .cookie-applicant.txt -H "X-XSRF-TOKEN: $CSRF_APPLICANT" -X POST "http://localhost:8080/api/comm/demo/complete/$APPLY_TASK_ID"
```

### 3. 经理发起沟通给两个人：alice 和 bob
```bash
# 查manager的待办
curl.exe -b .cookie-manager.txt "http://localhost:8080/api/comm/demo/tasks?assignee=manager"
# 获取manager_review节点的taskId（记为MGR_TASK_ID）

# 查看待办状态（NORMAL=可审批）
curl.exe -b .cookie-manager.txt "http://localhost:8080/api/comm/status/$MGR_TASK_ID"

# 发起沟通（发起人=认证用户 manager）
curl.exe -b .cookie-manager.txt -H "X-XSRF-TOKEN: $CSRF_MANAGER" -X POST http://localhost:8080/api/comm/start -H "Content-Type: application/json" -d '{
  "taskId": "'$MGR_TASK_ID'",
  "content": "请核实合同金额是否正确",
  "targets": [
    {"type": "USER", "id": "alice", "name": "张三"},
    {"type": "USER", "id": "bob", "name": "李四"}
  ]
}'
# 返回sessionId（记为SID）
```

### 4. 验证：此时经理审批会被引擎拦截
```bash
curl.exe -b .cookie-manager.txt -H "X-XSRF-TOKEN: $CSRF_MANAGER" -X POST "http://localhost:8080/api/comm/demo/complete/$MGR_TASK_ID"
# 应返回: {"error":true,"message":"当前待办正在沟通中..."}
```

### 5. alice反馈意见，bob暂不回复（部分反馈→仍不能审批）
```bash
# alice查看自己的待办列表（会看到一个沟通待办）
curl.exe -b .cookie-alice.txt "http://localhost:8080/api/comm/demo/tasks?assignee=alice"
# 通过/status接口查看沟通任务对应的commTaskRecordId（manager视角）
curl.exe -b .cookie-manager.txt "http://localhost:8080/api/comm/status/$MGR_TASK_ID"
# commTasks数组里找targetId=alice的记录，id为COMM_ALICE_ID

# alice反馈（反馈人=认证用户 alice）
curl.exe -b .cookie-alice.txt -H "X-XSRF-TOKEN: $CSRF_ALICE" -X POST http://localhost:8080/api/comm/reply -H "Content-Type: application/json" -d '{
  "commTaskRecordId": "'$COMM_ALICE_ID'",
  "content": "金额已核实无误"
}'

# 再查状态，bizStatus仍为COMMUNICATING（因为bob还未反馈）
curl.exe -b .cookie-manager.txt "http://localhost:8080/api/comm/status/$MGR_TASK_ID"
```

### 6. 经理部分收回给bob的沟通（部分收回→仍不可审批，继续沟通）
```bash
# commTasks里找到targetId=bob的记录，id为COMM_BOB_ID
# 部分收回（操作人=认证用户 manager）
curl.exe -b .cookie-manager.txt -H "X-XSRF-TOKEN: $CSRF_MANAGER" -X POST http://localhost:8080/api/comm/withdraw/$SID -H "Content-Type: application/json" -d '{
  "commTaskIds": ["'$COMM_BOB_ID'"]
}'
# 查看状态：bizStatus仍然是COMMUNICATING（因为alice已反馈，但还可以继续沟通）
# 注意：全部反馈后会恢复NORMAL，部分收回+其他已反馈=可审批
```

### 7. 全量收回→待办恢复，可审批
```bash
# 不传commTaskIds或传空数组=全部收回
curl.exe -b .cookie-manager.txt -H "X-XSRF-TOKEN: $CSRF_MANAGER" -X POST http://localhost:8080/api/comm/withdraw/$SID -H "Content-Type: application/json" -d '{
  "commTaskIds": []
}'
# 查看状态：bizStatus变为NORMAL，canApprove=true
curl.exe -b .cookie-manager.txt "http://localhost:8080/api/comm/status/$MGR_TASK_ID"

# 现在经理可以正常审批了
curl.exe -b .cookie-manager.txt -H "X-XSRF-TOKEN: $CSRF_MANAGER" -X POST "http://localhost:8080/api/comm/demo/complete/$MGR_TASK_ID"
```

> 每一段必须带上该段操作人的 Cookie 文件，这是这套脚本能跑通的关键：服务端只认会话里的用户，不再从请求体里相信“我是谁”。同理，每个 POST 都要带该操作人的令牌请求头，两者是成对的——换了 cookie 文件却没换 `CSRF_*` 变量，会被 CSRF 拦成 403。

## 术语口径

界面与本文档统一使用下表说法。「委托 / 受托 / 转办 / 再发起沟通」是四件不同的事，不再混用「代办」。

| 术语 | 含义 | 旧说法（不再使用） |
|---|---|---|
| 委托 | 委托人把待办交给他人处理，**责任仍归委托人**；受托人交回后由委托人确认办结 | 代办 |
| 我的委托 | 我是委托人，列出我交出去的记录（含已交回 / 已收回） | 我委托出去的 |
| 我的受托 | 我是受托人，列出别人交由我处理的记录（含已交回 / 被收回） | 我受托的 |
| 受托意见 | 受托人提交的处理结果与建议，单独落表、不覆盖最终审批意见 | 代办意见、代办建议 |
| 再发起沟通 | 非首发的沟通里，当前沟通对象再向其他人发起一条沟通（`/api/comm/forward`），父记录为发起它的那条沟通；界面统一叫「发起沟通」 | 转交、代办 |
| 后续沟通 | 一次沟通会话里由父记录派生的下游记录（同一对象继续被问，或再向其他人发起）；界面统一标「沟通」，层级只看缩进 | 追问 |
| 转办 | **永久移交**，原办理人不再参与，保留完整转办链（`BIZ_TASK_TRANSFER_`） | 与「委托」混用 |
| 代理规则 | 按规则自动把新任务派给代理人，委托记录的来源标为「代理规则」 | 与手工委托混用 |

一句话区分：**委托看「责任还在谁身上」，转办看「责任交给谁」，再发起沟通看「这条沟通继续找谁」**。委托可收回、可交回；转办不可逆；再发起沟通只发生在沟通任务之间。

## 功能清单

| 模块 | 能力 | 关键实现 |
|---|---|---|
| 旁路沟通 | 发起 / 补充对象 / 后续沟通 / 再发起沟通 / 部分或全部收回 / 反馈 | `TaskService.newTask()` 建无 `executionId` 的旁观任务，`taskDefinitionKey` = `源节点key#COMM` |
| 审批锁定 | 沟通中禁止完成任务，所有渠道一致拦截 | 引擎命令拦截器（主）+ 任务 `complete` 监听器（兜底） |
| 待办转办 | 永久移交，保留完整转办链 | `setAssignee` 改派 + `BIZ_TASK_TRANSFER_` 落链，同一引擎命令内完成 |
| 流程跟踪 | BPMN 图渲染、节点/连线三态、沟通角标、转办角标 | 不引 bpmn-js：DI 取几何、历史表取状态、两端时序推断连线 |
| 待办中心 | 我的待办 / 已办 / 我的发起，沟通与再发起沟通筛选 | 全部走认证上下文取当前用户，请求体不接受 `userId` |
| 委托中心 | 我的委托 / 我的受托，按状态、关键字筛选，展示委托链与受托意见 | 委托只换办理人不转移责任，记录落 `BIZ_TASK_DELEGATE_`；手工委托与代理规则委托在列表里用来源标签区分 |

## 架构说明

### 扩展点
1. **ProcessEnginePlugin**: `CommProcessEnginePlugin` - 注册Mapper、SessionFactory、BPMN解析监听器、命令拦截器
2. **GlobalUserTaskParseListener**: 给所有UserTask自动注入创建监听器，零BPMN配置
3. **TaskCreateInitListener**: 任务创建时自动初始化BizTaskExt记录
4. **CommunicationCommandInterceptor**: 引擎内核层拦截CompleteTaskCmd/SubmitTaskFormCmd，沟通中禁止审批
5. **CommunicationCompleteGuardListener**: 任务 `complete` 事件兜底校验（不依赖命令内部字段），拦截器读不到 taskId 时仍能挡住
6. **自定义实体**: BizTaskExt/BizCommSession/BizCommTask/BizTaskTransfer，实现DbEntity接口
7. **自定义Manager(Session)**: 继承AbstractManager，通过DbSqlSession访问MyBatis
8. **Mapper XML**: 遵循Camunda原生风格，statement id格式为insertEntity/updateEntity/deleteEntity/selectEntity

### 表结构
- `BIZ_TASK_EXT_`: 主流程待办业务扩展表（存沟通状态）
- `BIZ_COMM_SESSION_`: 沟通会话表
- `BIZ_COMM_TASK_`: 沟通任务明细表（含三层关联字段，`SOURCE_TASK_DEF_KEY_` 直接记录发起节点 key）
- `BIZ_TASK_TRANSFER_`: 待办转办记录表（引擎只保留当前办理人，转办链只能落在业务表）

表结构与索引由 `src/main/resources/db/migration` 下的 Flyway 脚本管理，按版本递增，不再依赖手写 `schema.sql`。

#### 按数据库方言维护

Camunda 7.24 官方支持的 7 类库各有一个子目录，Flyway 通过 `locations=classpath:db/migration/{vendor}` 按实际连接自动选中，配置里不出现库名：

| 目录 | 目标库 | 关键类型差异 |
|---|---|---|
| `h2/` | H2 2.2（演示与单测） | `CLOB` / `TIMESTAMP` |
| `mysql/` `mariadb/` | MySQL 8.0 / MariaDB 10.6+ | `LONGTEXT` / `DATETIME(3)`（避开 2038 与时区漂移） |
| `postgresql/` | PostgreSQL 12–16 | `TEXT`，表名不引号、由 PG 折叠小写 |
| `oracle/` | Oracle 19c | `VARCHAR2(n CHAR)`、无 `IF NOT EXISTS` |
| `sqlserver/` | SQL Server 2017/2019/2022 | `NVARCHAR(MAX)` / **`DATETIME2(3)`**（`TIMESTAMP` 是 rowversion） |
| `db2/` | DB2 11.5 LUW | `VARCHAR(n OCTETS)` / `CODEUNITS32` |

- `V1__baseline.sql` 只建 4 张表与主键/唯一约束；`V2__composite_indexes.sql` 建全部 16 个索引（9 单列 + 7 组合），7 个方言的列数与索引名必须逐一对齐；
- 默认 profile（H2）关闭 Flyway，由 `sql.init` 顺序执行 `db/migration/h2/` 两个脚本——同一份 SQL 服务演示与迁移，避免两套结构漂移；
- 正式库用 `--spring.profiles.active=prod`，连接信息走 `DB_URL` / `DB_USER` / `DB_PASSWORD` / `DB_DRIVER`，Flyway 自动迁移并关闭 `sql.init`；
- 引擎表 `ACT_*` 不进入本目录：由 Camunda 自身 `schema-update` 或官方 DDL 管理，业务表不加外键。


### 关键设计原则
- 沟通任务用`taskService.newTask()`创建，taskDefinitionKey=`源节点key#COMM`（如 `manager_review#COMM`），无executionId，完全旁路；据此可直接看出沟通由哪个流程节点发起
- 同时把来源节点单独落到 `BIZ_COMM_TASK_.SOURCE_TASK_DEF_KEY_`（如 `manager_review`），报表可按节点直接 GROUP BY，不必再截取 `#COMM` 前缀；历史记录该列为空时，查询接口会从旁路任务 key 或根待办 key 兜底还原
- 沟通任务的 `name` 沿用源待办名称（如“经理审批”），与待办列表中的源节点对齐；源节点未配置 name 时回退为节点 key，再兜底“沟通”，避免出现空标题
- 后续沟通任务的 `name` 同样沿用源待办名称，改用 `description` 区分层级（形如 `继续沟通（第 2 轮）：…`，该字样由后端生成、仅内部使用，界面同样显示为「沟通」）；判断层级请读 description 或 `BIZ_COMM_TASK_` 的父子关系，不要依赖名称前缀
- 待办 / 已办 / 我的发起列表以及 `/api/comm/status` 的沟通条目均返回 `isFollowUp`（依据 `PARENT_COMM_TASK_ID_` 是否为空），前端据此把沟通标签标为「首轮沟通」或「沟通」，不再单独显示「追问」
- 待办列表支持 `type=all|approval|comm|followup` 四类筛选：`comm` 为全部沟通（含后续沟通），`followup` 只返回再发起沟通任务（参数名沿用 `followup`）；`stats` 同时给出 `comm` 与 `followup` 计数
- 所有写操作包装为引擎Command，融入引擎事务和乐观锁
- 不依赖Spring事务管理，完全在引擎CommandContext内执行数据库操作
- 前端按钮禁用仅为体验优化，后端拦截器做权限兜底
- 审批锁定是两道防线：命令拦截器给出明确业务异常；任务 `complete` 监听器兜底，不依赖命令内部字段名，Camunda 升级后即使反射失效也不会被绕过
- 转办不能只 `setAssignee`：引擎只保留当前办理人，`ASSIGNEE_` 会被覆盖，转办链必须落 `BIZ_TASK_TRANSFER_`
