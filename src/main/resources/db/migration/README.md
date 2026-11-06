# 业务表迁移脚本（按数据库方言）

Camunda 7.24 官方支持的数据库共 7 种，本目录一一对应。Flyway 通过
`locations=classpath:db/migration/{vendor}` 按实际连接类型自动选中子目录，**不需要在配置里写库名**。

```text
db/migration/
├── h2/          H2 2.2        演示 + 单测
├── mysql/       MySQL 8.0     生产
├── mariadb/     MariaDB 10.6+ 生产
├── postgresql/  PostgreSQL 12–16
├── oracle/      Oracle 19c
├── sqlserver/   SQL Server 2017 / 2019 / 2022
└── db2/         DB2 11.5 LUW
```

## 版本约定

| 版本 | 内容 | 说明 |
|---|---|---|
| V1 | 4 张业务表 + 主键 + 唯一约束 | 不含索引 |
| V2 | 16 个索引（9 单列 + 7 组合） | 列序与查询谓词严格对应 |
| V3 | 委托表 `BIZ_TASK_DELEGATE_`、代理规则表 `BIZ_AGENT_RULE_` + 4 个索引 | 建表与索引同版本，两张表强相关 |

索引集中在 V2 而不是随建表写，是为了让 7 个方言能用同一份清单逐项比对；已执行的版本不要修改，
Flyway 会按 checksum 校验，改动历史脚本会让服务启动直接失败。

## 方言差异（真正会踩到的四处）

| 差异点 | H2 / PG / MySQL | Oracle | SQL Server | DB2 |
|---|---|---|---|---|
| 大文本 | CLOB / TEXT / LONGTEXT | CLOB | NVARCHAR(MAX) | CLOB |
| 时间列 | TIMESTAMP / DATETIME(3) | TIMESTAMP | **DATETIME2(3)** | TIMESTAMP |
| `IF NOT EXISTS` | 支持（MySQL 不支持索引） | 不支持 | 不支持 | 不支持 |
| 字符串长度 | 按字符 | `VARCHAR2(n CHAR)` | `NVARCHAR(n)` 按字符 | `VARCHAR(n OCTETS)` / `CODEUNITS32` |

两个隐性坑：

1. **SQL Server 的 `TIMESTAMP` 是 rowversion**，不是时间。写错不会报错，只会得到一个和日期毫无关系的二进制列。
2. **MySQL 的 `TIMESTAMP` 到 2038 年溢出**，且随会话时区变化。业务时间统一 `DATETIME(3)`。

## 一致性自检

新增或修改任一版本后，用下面这段命令确认 7 个方言的表、列、索引完全对齐（输出应全部为 1）：

```bash
# 列集合必须一致（去掉类型差异，只比列名）
for v in h2 mysql mariadb postgresql oracle sqlserver db2; do
  printf "%s " "$v"
  grep -oE '^\s{4}[A-Z0-9_]+\s' src/main/resources/db/migration/$v/V1__baseline.sql | tr -d ' ' | sort | md5sum
done

# 索引清单必须逐行一致
for v in h2 mysql mariadb postgresql oracle sqlserver db2; do
  printf "%s " "$v"
  grep -oE 'IDX_[A-Z_]+' src/main/resources/db/migration/$v/V2__composite_indexes.sql | sort | md5sum
done

# V3（委托 / 代理）同样逐行一致
for v in h2 mysql mariadb postgresql oracle sqlserver db2; do
  printf "%s " "$v"
  grep -oE '^\s{4}[A-Z0-9_]+\s' src/main/resources/db/migration/$v/V3__delegate_and_agent.sql | tr -d ' ' | sort | md5sum
  printf "%s " "$v"
  grep -oE 'IDX_[A-Z_]+' src/main/resources/db/migration/$v/V3__delegate_and_agent.sql | sort | md5sum
done
```

## 执行顺序

1. 引擎表：Camunda 自带 DDL（`database/*.sql`）或 `camunda.bpm.database.schema-update=true`；
2. 业务表：Flyway 自动执行本目录脚本（prod profile 已开启）；
3. 两者互不引用、互不加外键——业务表与引擎表同事务写入，外键会在引擎清理历史时阻塞。
