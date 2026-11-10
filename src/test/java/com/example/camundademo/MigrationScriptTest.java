package com.example.camundademo;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移脚本可执行性校验。
 *
 * <p>业务表脚本按 7 种数据库方言维护，真实库里跑一遍成本很高，但至少要在 CI 里保证：
 * 脚本语法能被 Flyway 解析、表与索引数量与设计一致。这里用 H2 方言做代表，
 * 其它方言的“列名 / 索引名逐一对齐”由 db/migration/README.md 的自检命令约束。
 */
class MigrationScriptTest {

    private static final String URL = "jdbc:h2:mem:migration_check;DB_CLOSE_DELAY=-1";

    @Test
    void h2ScriptsApplyCleanly() throws Exception {
        MigrateResult result = Flyway.configure()
                .dataSource(URL, "sa", "")
                .locations("classpath:db/migration/h2")
                .load()
                .migrate();

        assertThat(result.migrationsExecuted).isEqualTo(8);

        try (Connection conn = DriverManager.getConnection(URL, "sa", "");
             Statement st = conn.createStatement()) {

            assertThat(count(st, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                    + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME LIKE 'BIZ!_%' ESCAPE '!'"))
                    .as("10 张业务表（V5 子流程注册、V6 传阅与阅办、V8 加签记录）").isEqualTo(10);

            assertThat(count(st, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                    + "WHERE INDEX_SCHEMA = 'PUBLIC' AND INDEX_NAME LIKE 'IDX!_%' ESCAPE '!'"))
                    .as("V2 的 16 个 + V3 的 4 个 + V5 的 1 个 + V6 的 6 个 + V8 的 5 个索引").isEqualTo(32);

            // 组合索引的列序不可调换：findByTargetAndStatus 依赖 (TARGET_ID_, STATUS_)
            assertThat(count(st, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                    + "WHERE INDEX_NAME = 'IDX_BIZ_COMM_TASK_TGT_STATUS'"))
                    .as("按人筛沟通的组合索引存在").isEqualTo(1);

            // 代理规则按 (OWNER_USER_ID_, IS_ENABLED_) 查生效规则，列序反了就走不到索引
            assertThat(count(st, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                    + "WHERE INDEX_NAME = 'IDX_BIZ_AGENT_OWNER_ENABLED'"))
                    .as("代理规则查询索引存在").isEqualTo(1);
        }
    }

    @Test
    void migrateIsIdempotent() {
        Flyway flyway = Flyway.configure()
                .dataSource("jdbc:h2:mem:migration_check_idem;DB_CLOSE_DELAY=-1", "sa", "")
                .locations("classpath:db/migration/h2")
                .load();
        flyway.migrate();
        // 第二次不应再执行任何版本，验证 Flyway 版本表生效（脚本本身不做幂等）
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    private int count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
