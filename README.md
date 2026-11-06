# Camunda 7 旁路沟通 Demo（纯引擎原生MyBatis实现）

## 项目说明
这是一个基于官方 `camunda-get-started-spring-boot` 架构的可运行Demo，演示零BPMN侵入、零核心表修改的旁路沟通功能，完全使用Camunda自带MyBatis做持久化，不依赖MyBatis-Plus等额外ORM框架。

## 核心特性
- ✅ 零BPMN侵入：流程定义中无任何沟通节点/网关/消息事件
- ✅ 零核心表修改：不直接修改ACT_RU_TASK等引擎表，通过自定义扩展表存储状态
- ✅ 引擎层拦截：通过CommandInterceptor在引擎内核层面校验审批权限，无法绕过
- ✅ 任务类型：复用引擎原生 `taskDefinitionKey` 区分普通待办 / 沟通待办，未来可扩展待阅任务
- ✅ 三层关联：sourceTaskId(直接发起源) / rootTaskId(首发根源) / parentCommTaskId(父沟通)
- ✅ 状态机：全量收回/全部反馈→可审批；部分收回→继续暂停；多轮追问
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

## 测试

```bash
mvn test                          # 全量
mvn test -Dtest=CommunicationFlowTest   # 只跑沟通主链路
```

测试断言的是**引擎事实**而不只是接口 200：历史任务是否存在、删除原因是什么、
`taskDefinitionKey` 是否带 `#COMM`、`ACT_HI_ACTINST` 是否为空等。

## 演示场景（curl脚本）

### 1. 启动流程（申请人：applicant）
```bash
curl -X POST "http://localhost:8080/api/comm/demo/start?initiator=applicant"
# 返回: {"processInstanceId":"xxx"}
```
记录返回的 `processInstanceId`（记为PID）。

### 2. 申请人完成"填写申请"，流程流转到经理审批
```bash
# 查applicant的待办
curl "http://localhost:8080/api/comm/demo/tasks?assignee=applicant"
# 获取apply节点的taskId（记为APPLY_TASK_ID）

# 完成apply
curl -X POST "http://localhost:8080/api/comm/demo/complete/$APPLY_TASK_ID"
```

### 3. 经理发起沟通给两个人：alice 和 bob
```bash
# 查manager的待办
curl "http://localhost:8080/api/comm/demo/tasks?assignee=manager"
# 获取manager_review节点的taskId（记为MGR_TASK_ID）

# 查看待办状态（NORMAL=可审批）
curl "http://localhost:8080/api/comm/status/$MGR_TASK_ID?userId=manager"

# 发起沟通
curl -X POST http://localhost:8080/api/comm/start -H "Content-Type: application/json" -d '{
  "taskId": "'$MGR_TASK_ID'",
  "initiator": "manager",
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
curl -X POST "http://localhost:8080/api/comm/demo/complete/$MGR_TASK_ID"
# 应返回: {"error":true,"message":"当前待办正在沟通中..."}
```

### 5. alice反馈意见，bob暂不回复（部分反馈→仍不能审批）
```bash
# alice查看自己的待办列表（会看到一个沟通待办）
curl "http://localhost:8080/api/comm/demo/tasks?assignee=alice"
# 通过/status接口查看沟通任务对应的commTaskRecordId
curl "http://localhost:8080/api/comm/status/$MGR_TASK_ID?userId=manager"
# commTasks数组里找targetId=alice的记录，id为COMM_ALICE_ID

# alice反馈
curl -X POST http://localhost:8080/api/comm/reply -H "Content-Type: application/json" -d '{
  "commTaskRecordId": "'$COMM_ALICE_ID'",
  "userId": "alice",
  "content": "金额已核实无误"
}'

# 再查状态，bizStatus仍为COMMUNICATING（因为bob还未反馈）
curl "http://localhost:8080/api/comm/status/$MGR_TASK_ID?userId=manager"
```

### 6. 经理部分收回给bob的沟通（部分收回→仍不可审批，继续沟通）
```bash
# commTasks里找到targetId=bob的记录，id为COMM_BOB_ID
# 部分收回
curl -X POST http://localhost:8080/api/comm/withdraw/$SID -H "Content-Type: application/json" -d '{
  "operator": "manager",
  "commTaskIds": ["'$COMM_BOB_ID'"]
}'
# 查看状态：bizStatus仍然是COMMUNICATING（因为alice已反馈，但还可以继续沟通）
# 注意：全部反馈后会恢复NORMAL，部分收回+其他已反馈=可审批
```

### 7. 全量收回→待办恢复，可审批
```bash
# 不传commTaskIds或传空数组=全部收回
curl -X POST http://localhost:8080/api/comm/withdraw/$SID -H "Content-Type: application/json" -d '{
  "operator": "manager",
  "commTaskIds": []
}'
# 查看状态：bizStatus变为NORMAL，canApprove=true
curl "http://localhost:8080/api/comm/status/$MGR_TASK_ID?userId=manager"

# 现在经理可以正常审批了
curl -X POST "http://localhost:8080/api/comm/demo/complete/$MGR_TASK_ID"
```

## 功能清单

| 模块 | 能力 | 关键实现 |
|---|---|---|
| 旁路沟通 | 发起 / 补充对象 / 追问 / 转交 / 部分或全部收回 / 反馈 | `TaskService.newTask()` 建无 `executionId` 的旁观任务，`taskDefinitionKey` = `源节点key#COMM` |
| 审批锁定 | 沟通中禁止完成任务，所有渠道一致拦截 | 引擎命令拦截器（主）+ 任务 `complete` 监听器（兜底） |
| 待办转办 | 永久移交，保留完整转办链 | `setAssignee` 改派 + `BIZ_TASK_TRANSFER_` 落链，同一引擎命令内完成 |
| 流程跟踪 | BPMN 图渲染、节点/连线三态、沟通角标、转办角标 | 不引 bpmn-js：DI 取几何、历史表取状态、两端时序推断连线 |
| 待办中心 | 我的待办 / 已办 / 我的发起，沟通与追问筛选 | 全部走认证上下文取当前用户，请求体不接受 `userId` |

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
- 追问任务的 `name` 同样沿用源待办名称，改用 `description` 区分追问（形如 `沟通追问（第 2 轮）：…`）；判断“是否追问”请读 description 或 `BIZ_COMM_TASK_` 的父子关系，不要依赖名称前缀
- 待办 / 已办 / 我的发起列表以及 `/api/comm/status` 的沟通条目均返回 `isFollowUp`（依据 `PARENT_COMM_TASK_ID_` 是否为空），前端据此把沟通标签区分为「首轮沟通」与「追问」，无需解析节点 key 或名称
- 待办列表支持 `type=all|approval|comm|followup` 四类筛选：`comm` 为全部沟通（含追问），`followup` 只返回追问任务；`stats` 同时给出 `comm` 与 `followup` 计数
- 所有写操作包装为引擎Command，融入引擎事务和乐观锁
- 不依赖Spring事务管理，完全在引擎CommandContext内执行数据库操作
- 前端按钮禁用仅为体验优化，后端拦截器做权限兜底
- 审批锁定是两道防线：命令拦截器给出明确业务异常；任务 `complete` 监听器兜底，不依赖命令内部字段名，Camunda 升级后即使反射失效也不会被绕过
- 转办不能只 `setAssignee`：引擎只保留当前办理人，`ASSIGNEE_` 会被覆盖，转办链必须落 `BIZ_TASK_TRANSFER_`
