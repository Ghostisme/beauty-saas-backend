package com.beauty.saas;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

class LegacyMigrationTest {
    @Test void migrationRetainsExistingAdminAndIsNotReapplied() throws Exception {
        String url="jdbc:h2:mem:legacy_beauty_saas;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (var connection=DriverManager.getConnection(url,"sa",""); var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE sys_user(id BIGINT AUTO_INCREMENT PRIMARY KEY,username VARCHAR(50) NOT NULL,password VARCHAR(100),nickname VARCHAR(50),phone VARCHAR(20),email VARCHAR(100),avatar VARCHAR(255),status TINYINT DEFAULT 1,deleted TINYINT DEFAULT 0,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE UNIQUE INDEX username ON sys_user(username)");
            statement.execute("INSERT INTO sys_user(username,password,nickname) VALUES('admin','0192023a7bbd73250516f069df18b500','管理员')");
        }
        Flyway flyway=Flyway.configure().dataSource(url,"sa","").baselineOnMigrate(true).baselineVersion("0").target("1").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection=DriverManager.getConnection(url,"sa",""); var statement=connection.createStatement()) {
            try (var rows=statement.executeQuery("SELECT u.username,u.tenant_id,u.password,t.code FROM sys_user u JOIN sys_tenant t ON t.id=u.tenant_id")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString("username")).isEqualTo("admin");
                assertThat(rows.getLong("tenant_id")).isEqualTo(1); assertThat(rows.getString("code")).isEqualTo("yulequan");
                assertThat(rows.getString("password")).isEqualTo("0192023a7bbd73250516f069df18b500"); assertThat(rows.next()).isFalse();
            }
            try (var rows=statement.executeQuery("SELECT COUNT(*) FROM sys_role WHERE tenant_id=1")) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(7); }
        }
    }
}
