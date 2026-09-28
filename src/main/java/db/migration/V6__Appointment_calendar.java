package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.charset.StandardCharsets;

/** Adds tenant-scoped appointment calendar records and permissions. */
public class V6__Appointment_calendar extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        ScriptUtils.executeSqlScript(connection, new EncodedResource(
            new ClassPathResource("sql/appointment-schema.sql"), StandardCharsets.UTF_8));
        String[][] permissions = {
            {"appointments:read", "查看预约", "预约管理"},
            {"appointments:write", "维护预约", "预约管理"}
        };
        try (var find = connection.prepareStatement("SELECT id FROM sys_permission WHERE code=?");
             var insert = connection.prepareStatement("INSERT INTO sys_permission(code,name,module) VALUES(?,?,?)")) {
            for (var permission : permissions) {
                find.setString(1, permission[0]);
                try (var result = find.executeQuery()) {
                    if (result.next()) continue;
                }
                for (int i = 0; i < permission.length; i++) insert.setString(i + 1, permission[i]);
                insert.executeUpdate();
            }
        }
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO sys_role_permission(tenant_id,role_id,permission_id) " +
                "SELECT r.tenant_id,r.id,p.id FROM sys_role r CROSS JOIN sys_permission p " +
                "WHERE r.code='ADMIN' AND p.code IN ('appointments:read','appointments:write') " +
                "AND NOT EXISTS(SELECT 1 FROM sys_role_permission rp WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND rp.permission_id=p.id)");
        }
    }
}
