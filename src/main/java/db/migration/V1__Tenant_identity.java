package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Additive migration: retains legacy accounts; never drops business tables. */
public class V1__Tenant_identity extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        ScriptUtils.executeSqlScript(c, new EncodedResource(new ClassPathResource("sql/tenant-schema.sql"), StandardCharsets.UTF_8));
        if (!hasColumn(c, "tenant_id")) execute(c, "ALTER TABLE sys_user ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1");
        if (!hasColumn(c, "auth_version")) execute(c, "ALTER TABLE sys_user ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 1");
        Map<String,List<String>> indexes = new HashMap<>();
        try (ResultSet rs = c.getMetaData().getIndexInfo(c.getCatalog(), null, tableName(c), true, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME"), column = rs.getString("COLUMN_NAME");
                if (name != null && column != null) indexes.computeIfAbsent(name, ignored -> new ArrayList<>()).add(column.toLowerCase(Locale.ROOT));
            }
        }
        for (var entry : indexes.entrySet()) {
            if (entry.getValue().equals(List.of("username"))) {
                String index = entry.getKey().replace("`", "``");
                if (c.getMetaData().getDatabaseProductName().contains("MySQL")) execute(c, "ALTER TABLE sys_user DROP INDEX `" + index + "`");
                else execute(c, "DROP INDEX " + index);
            }
        }
        if (indexes.values().stream().noneMatch(cols -> cols.equals(List.of("tenant_id", "username"))))
            execute(c, "CREATE UNIQUE INDEX uk_user_tenant_username ON sys_user(tenant_id, username)");
        boolean tenantStatusIndex=false;
        try (ResultSet rs=c.getMetaData().getIndexInfo(c.getCatalog(),null,tableName(c),false,false)) {
            while (rs.next()) if ("idx_user_tenant_status".equalsIgnoreCase(rs.getString("INDEX_NAME"))) tenantStatusIndex=true;
        }
        if (!tenantStatusIndex) execute(c,"CREATE INDEX idx_user_tenant_status ON sys_user(tenant_id,deleted,status)");
        String[][] permissions = {
            {"home:read", "查看首页", "首页"}, {"tenant:read", "查看企业信息", "企业"}, {"tenant:write", "维护企业信息", "企业"},
            {"users:read", "查看用户", "用户"}, {"users:write", "维护用户与授权", "用户"},
            {"departments:read", "查看部门门店", "部门门店"}, {"departments:write", "维护部门门店", "部门门店"},
            {"rooms:read", "查看房间", "房间"}, {"rooms:write", "维护房间", "房间"},
            {"roles:read", "查看角色权限", "角色权限"}, {"roles:write", "维护角色权限", "角色权限"}
        };
        for (String[] p : permissions) update(c, "INSERT INTO sys_permission(code,name,module) VALUES(?,?,?)", (Object[]) p);
        if (scalar(c, "SELECT COUNT(*) FROM sys_user") > 0) {
            long admin = scalar(c, "SELECT COALESCE(MIN(id),0) FROM sys_user WHERE username='admin' AND deleted=0 AND status=1");
            if (admin == 0) throw new SQLException("Existing accounts require an active admin before tenant migration");
            update(c, "INSERT INTO sys_tenant(id,code,name) VALUES(1,?,?)", "yulequan", "余乐圈美业管理中心");
            update(c, "INSERT INTO sys_tenant_admin(tenant_id,user_id) VALUES(1,?)", admin);
            String[][] roles = {{"ADMIN","管理员"}, {"STORE_MANAGER","店长"}, {"HOST","主理人"}, {"FRONT_DESK","前台"}, {"BEAUTICIAN","美容师"}, {"EMPLOYEE","员工"}, {"CUSTOMER","顾客"}};
            for (String[] r : roles) update(c, "INSERT INTO sys_role(tenant_id,code,name,builtin) VALUES(1,?,?,1)", r[0], r[1]);
            execute(c, "INSERT INTO sys_role_permission SELECT 1,r.id,p.id FROM sys_role r CROSS JOIN sys_permission p WHERE r.tenant_id=1 AND (r.code='ADMIN' OR p.code='home:read')");
            update(c, "INSERT INTO sys_user_role(tenant_id,user_id,role_id,department_id) SELECT 1,?,id,0 FROM sys_role WHERE tenant_id=1 AND code='ADMIN'", admin);
            update(c, "INSERT INTO sys_user_role(tenant_id,user_id,role_id,department_id) SELECT 1,u.id,r.id,0 FROM sys_user u JOIN sys_role r ON r.tenant_id=1 AND r.code='EMPLOYEE' WHERE u.id<>? AND u.deleted=0", admin);
        }
    }
    private String tableName(Connection c) throws SQLException { return c.getMetaData().storesUpperCaseIdentifiers() ? "SYS_USER" : "sys_user"; }
    private boolean hasColumn(Connection c, String column) throws SQLException {
        try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, tableName(c), null)) {
            while (rs.next()) if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return true;
            return false;
        }
    }
    private void execute(Connection c, String sql) throws SQLException { try (Statement s=c.createStatement()) { s.execute(sql); } }
    private long scalar(Connection c, String sql) throws SQLException { try (Statement s=c.createStatement(); ResultSet r=s.executeQuery(sql)) { r.next(); return r.getLong(1); } }
    private void update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s=c.prepareStatement(sql)) { for (int i=0; i<args.length; i++) s.setObject(i+1,args[i]); s.executeUpdate(); }
    }
}
