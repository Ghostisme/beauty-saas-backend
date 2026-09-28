package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.charset.StandardCharsets;

/** Adds tenant-scoped customer records without demo data. */
public class V9__Customer_management extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        ScriptUtils.executeSqlScript(connection, new EncodedResource(
            new ClassPathResource("sql/customer-schema.sql"), StandardCharsets.UTF_8));
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO sys_permission(code,name,module) SELECT 'customers:read','查看顾客','顾客经营' WHERE NOT EXISTS(SELECT 1 FROM sys_permission WHERE code='customers:read')");
            statement.executeUpdate("INSERT INTO sys_permission(code,name,module) SELECT 'customers:write','维护顾客','顾客经营' WHERE NOT EXISTS(SELECT 1 FROM sys_permission WHERE code='customers:write')");
            statement.executeUpdate("INSERT INTO sys_role_permission(tenant_id,role_id,permission_id) SELECT r.tenant_id,r.id,p.id FROM sys_role r CROSS JOIN sys_permission p WHERE p.code='customers:read' AND (r.code='ADMIN' OR EXISTS(SELECT 1 FROM sys_role_permission rp JOIN sys_permission hp ON hp.id=rp.permission_id WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND hp.code='home:read')) AND NOT EXISTS(SELECT 1 FROM sys_role_permission existing WHERE existing.tenant_id=r.tenant_id AND existing.role_id=r.id AND existing.permission_id=p.id)");
            statement.executeUpdate("INSERT INTO sys_role_permission(tenant_id,role_id,permission_id) SELECT r.tenant_id,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.code='customers:write' WHERE r.code='ADMIN' AND NOT EXISTS(SELECT 1 FROM sys_role_permission existing WHERE existing.tenant_id=r.tenant_id AND existing.role_id=r.id AND existing.permission_id=p.id)");
        }
    }
}
