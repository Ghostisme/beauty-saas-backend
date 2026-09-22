package com.beauty.saas;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

class SmsMigrationTest {
    @Test void v4PreservesIdentityAndOrdersAddsNoForeignKeysAndOnlyGrantsAdmins() throws Exception {
        String url="jdbc:h2:mem:sms_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("3").load().migrate();
        try (var c=DriverManager.getConnection(url,"sa",""); var s=c.createStatement()) {
            s.execute("INSERT INTO sys_tenant(id,code,name) VALUES(1,'original','原企业')");
            s.execute("INSERT INTO sys_user(id,tenant_id,username,password,nickname) VALUES(1,0,'admin','unchanged','超级管理员')");
            s.execute("INSERT INTO sys_platform_admin(user_id) VALUES(1)");
            s.execute("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(1,1,'ADMIN','管理员'),(2,1,'EMPLOYEE','员工')");
            s.execute("INSERT INTO biz_order(tenant_id,department_id,order_no,status,consumption_type,customer_name,order_time,total_amount,paid_amount) VALUES(1,1,'keep-me','CONFIRMED','SERVICE','原顾客','2026-09-22 10:00:00',100.30,60.10)");
        }
        var flyway=Flyway.configure().dataSource(url,"sa","").target("4").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1); assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var c=DriverManager.getConnection(url,"sa",""); var s=c.createStatement()) {
            try (var r=s.executeQuery("SELECT tenant_id,password FROM sys_user WHERE id=1")) { r.next(); assertThat(r.getLong(1)).isZero(); assertThat(r.getString(2)).isEqualTo("unchanged"); }
            try (var r=s.executeQuery("SELECT order_no,total_amount,paid_amount FROM biz_order")) { r.next(); assertThat(r.getString(1)).isEqualTo("keep-me"); assertThat(r.getBigDecimal(2)).isEqualByComparingTo("100.30"); assertThat(r.getBigDecimal(3)).isEqualByComparingTo("60.10"); }
            try (var r=s.executeQuery("SELECT COUNT(*) FROM sys_role_permission WHERE role_id=1")) { r.next(); assertThat(r.getInt(1)).isEqualTo(6); }
            try (var r=s.executeQuery("SELECT COUNT(*) FROM sys_role_permission WHERE role_id=2")) { r.next(); assertThat(r.getInt(1)).isZero(); }
            try (var r=s.executeQuery("SELECT COUNT(*) FROM sys_permission")) { r.next(); assertThat(r.getInt(1)).isEqualTo(21); }
            try (var r=s.executeQuery("SELECT COUNT(*) FROM biz_sms_package")) { r.next(); assertThat(r.getInt(1)).isEqualTo(5); }
            for (String table:new String[]{"biz_sms_setting","biz_sms_record","biz_sms_account","biz_sms_recharge","biz_sms_credit_ledger","biz_sms_audit"}) {
                try (var r=s.executeQuery("SELECT COUNT(*) FROM "+table)) { r.next(); assertThat(r.getInt(1)).isZero(); }
            }
            try (var tables=c.getMetaData().getTables(null,null,"%",new String[]{"TABLE"})) {
                while(tables.next()) { String name=tables.getString("TABLE_NAME"); if (name.startsWith("biz_") || name.startsWith("sys_")) try (var keys=c.getMetaData().getImportedKeys(null,null,name)) { assertThat(keys.next()).as(name+" foreign keys").isFalse(); } }
            }
        }
    }
}
