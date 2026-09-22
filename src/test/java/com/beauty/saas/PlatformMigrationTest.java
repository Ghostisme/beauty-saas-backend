package com.beauty.saas;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

class PlatformMigrationTest {
    @Test void v2MovesOnlyOriginalAdminAndPreservesPasswordBusinessDataAndOtherEnterpriseAdmins() throws Exception {
        String url="jdbc:h2:mem:platform_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("1").load().migrate();
        try (var c=DriverManager.getConnection(url,"sa",""); var s=c.createStatement()) {
            s.execute("INSERT INTO sys_tenant(id,code,name) VALUES(1,'yulequan','历史企业'),(2,'other','第二家企业')");
            s.execute("INSERT INTO sys_user(id,tenant_id,username,password,nickname) VALUES(1,1,'admin','0192023a7bbd73250516f069df18b500','管理员'),(2,1,'employee','unchanged','原员工'),(3,2,'admin','other-password','企业管理员')");
            s.execute("INSERT INTO sys_tenant_admin VALUES(1,1),(2,3)");
            s.execute("INSERT INTO sys_department(tenant_id,code,name,type) VALUES(1,'original','历史门店','STORE')");
            s.execute("INSERT INTO sys_user_department VALUES(1,1,1),(1,2,1)");
            s.execute("INSERT INTO sys_user_role VALUES(1,1,10,0),(1,2,11,1),(2,3,20,0)");
        }
        var flyway=Flyway.configure().dataSource(url,"sa","").target("2").load(); assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1); assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var c=DriverManager.getConnection(url,"sa",""); var s=c.createStatement()) {
            try (var r=s.executeQuery("SELECT u.tenant_id,u.password,u.auth_version FROM sys_user u JOIN sys_platform_admin a ON a.user_id=u.id WHERE u.username='admin'")) {
                assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isZero(); assertThat(r.getString(2)).isEqualTo("0192023a7bbd73250516f069df18b500"); assertThat(r.getLong(3)).isEqualTo(2); assertThat(r.next()).isFalse();
            }
            for (String sql:new String[]{"SELECT COUNT(*) FROM sys_tenant_admin WHERE tenant_id=2 AND user_id=3","SELECT COUNT(*) FROM sys_user_department WHERE user_id=2","SELECT COUNT(*) FROM sys_user_role WHERE user_id=3","SELECT COUNT(*) FROM sys_department WHERE tenant_id=1"}) try (var r=s.executeQuery(sql)) { r.next(); assertThat(r.getInt(1)).isEqualTo(1); }
            try (var r=s.executeQuery("SELECT COUNT(*) FROM sys_tenant_admin WHERE tenant_id=1")) { r.next(); assertThat(r.getInt(1)).isZero(); }
            try (var r=s.executeQuery("SELECT COUNT(*) FROM sys_tenant")) { r.next(); assertThat(r.getInt(1)).isEqualTo(2); }
        }
    }
}
