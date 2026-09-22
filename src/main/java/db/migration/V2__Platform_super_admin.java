package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.*;

/** Correct the legacy admin identity without dropping enterprises or business data. No foreign keys. */
public class V2__Platform_super_admin extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        Connection c=context.getConnection();
        try (Statement s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS sys_platform_admin (user_id BIGINT PRIMARY KEY, create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS sys_platform_audit (id BIGINT AUTO_INCREMENT PRIMARY KEY, platform_user_id BIGINT NOT NULL, tenant_id BIGINT NOT NULL DEFAULT 0, action VARCHAR(60) NOT NULL, detail VARCHAR(500) NOT NULL, create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, INDEX idx_platform_audit_tenant(tenant_id,create_time))");
        }
        // V1 placed the original admin in yulequan. Only lift that owner, never other enterprises' admins.
        long user=0, tenant=0;
        try (Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT u.id,u.tenant_id FROM sys_user u JOIN sys_tenant t ON t.id=u.tenant_id JOIN sys_tenant_admin a ON a.tenant_id=t.id AND a.user_id=u.id WHERE t.id=1 AND t.code='yulequan' AND u.username='admin' AND u.deleted=0")) {
            if (r.next()) { user=r.getLong(1); tenant=r.getLong(2); }
        }
        if (user==0) return; // Clean installations bootstrap explicitly through an environment secret.
        update(c,"INSERT INTO sys_platform_admin(user_id) VALUES(?)",user);
        update(c,"DELETE FROM sys_tenant_admin WHERE tenant_id=? AND user_id=?",tenant,user);
        update(c,"DELETE FROM sys_user_role WHERE tenant_id=? AND user_id=?",tenant,user);
        update(c,"DELETE FROM sys_user_department WHERE tenant_id=? AND user_id=?",tenant,user);
        // Tenant 0 is reserved for platform identities, not a row in sys_tenant.
        update(c,"UPDATE sys_user SET tenant_id=0,auth_version=auth_version+1,nickname=CASE WHEN nickname IN ('管理员','负责人') THEN '超级管理员' ELSE nickname END,update_time=CURRENT_TIMESTAMP WHERE id=?",user);
        update(c,"INSERT INTO sys_platform_audit(platform_user_id,tenant_id,action,detail) VALUES(?,?,'MIGRATE_PLATFORM_ADMIN','原 admin 调整为平台超管；企业及业务数据保留，原企业管理员需单独开通')",user,tenant);
    }
    private void update(Connection c,String sql,Object... args) throws SQLException {
        try (PreparedStatement s=c.prepareStatement(sql)) { for (int i=0;i<args.length;i++) s.setObject(i+1,args[i]); s.executeUpdate(); }
    }
}
