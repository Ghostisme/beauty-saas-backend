package com.beauty.saas;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

class OrderMigrationTest {
    @Test void v3BackfillsOnlyAdminPermissionsAndPreservesExistingIdentityAndData() throws Exception {
        String url="jdbc:h2:mem:order_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("2").load().migrate();
        try (var c=DriverManager.getConnection(url,"sa",""); var s=c.createStatement()) {
            s.execute("INSERT INTO sys_tenant(id,code,name) VALUES(1,'enterprise','原企业')");
            s.execute("INSERT INTO sys_user(id,tenant_id,username,password,nickname) VALUES(1,0,'admin','unchanged-password','超级管理员')");
            s.execute("INSERT INTO sys_platform_admin(user_id) VALUES(1)");
            s.execute("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(1,1,'ADMIN','管理员'),(2,1,'EMPLOYEE','员工')");
        }
        var flyway=Flyway.configure().dataSource(url,"sa","").target("3").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1); assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var c=DriverManager.getConnection(url,"sa",""); var s=c.createStatement()) {
            try (var r=s.executeQuery("SELECT u.tenant_id,u.password FROM sys_user u JOIN sys_platform_admin p ON p.user_id=u.id")) { assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isZero(); assertThat(r.getString(2)).isEqualTo("unchanged-password"); }
            try (var r=s.executeQuery("SELECT COUNT(*) FROM sys_role_permission WHERE role_id=1")) { r.next(); assertThat(r.getInt(1)).isEqualTo(4); }
            try (var r=s.executeQuery("SELECT COUNT(*) FROM sys_role_permission WHERE role_id=2")) { r.next(); assertThat(r.getInt(1)).isZero(); }
            try (var r=s.executeQuery("SELECT COUNT(*) FROM sys_permission")) { r.next(); assertThat(r.getInt(1)).isEqualTo(15); }
            try (var r=s.executeQuery("SELECT COUNT(*) FROM biz_order")) { r.next(); assertThat(r.getInt(1)).isZero(); }
            try (var r=s.executeQuery("SELECT COUNT(*) FROM sys_tenant")) { r.next(); assertThat(r.getInt(1)).isEqualTo(1); }
        }
    }
}
