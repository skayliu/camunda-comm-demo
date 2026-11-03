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
- Camunda 7.20 Spring Boot
- Spring Boot 2.7.18
- H2 内存数据库（零依赖）
- Camunda 自带 MyBatis（自定义实体 + SessionFactory 机制）

## 启动方式
```bash
cd camunda-comm-demo
mvn spring-boot:run
```

启动后访问：
- Camunda Cockpit: http://localhost:8080/camunda （demo/demo）
- H2 控制台: http://localhost:8080/h2 （JDBC URL: jdbc:h2:mem:camundadb，用户名sa，密码空）

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

## 架构说明

### 扩展点
1. **ProcessEnginePlugin**: `CommProcessEnginePlugin` - 注册Mapper、SessionFactory、BPMN解析监听器、命令拦截器
2. **GlobalUserTaskParseListener**: 给所有UserTask自动注入创建监听器，零BPMN配置
3. **TaskCreateInitListener**: 任务创建时自动初始化BizTaskExt记录
4. **CommunicationCommandInterceptor**: 引擎内核层拦截CompleteTaskCmd，沟通中禁止审批
5. **自定义实体**: BizTaskExt/BizCommSession/BizCommTask，实现DbEntity接口
6. **自定义Manager(Session)**: 继承AbstractManager，通过DbSqlSession访问MyBatis
7. **Mapper XML**: 遵循Camunda原生风格，statement id格式为insertEntity/updateEntity/deleteEntity/selectEntity

### 表结构
- `BIZ_TASK_EXT_`: 主流程待办业务扩展表（存沟通状态）
- `BIZ_COMM_SESSION_`: 沟通会话表
- `BIZ_COMM_TASK_`: 沟通任务明细表（含三层关联字段，`SOURCE_TASK_DEF_KEY_` 直接记录发起节点 key）

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
