package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.charset.StandardCharsets;

/** Adds only order tables and permission definitions; never inserts sample business orders. */
public class V3__Order_management extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();
        ScriptUtils.executeSqlScript(connection,new EncodedResource(new ClassPathResource("sql/order-schema.sql"),StandardCharsets.UTF_8));
        String[][] permissions={
            {"orders:read","查看订单","订单管理"}, {"orders:write","核对订单","订单管理"},
            {"order-settings:read","查看订单核对设置","订单核对设置"}, {"order-settings:write","维护订单核对设置","订单核对设置"}
        };
        try (var insert=connection.prepareStatement("INSERT INTO sys_permission(code,name,module) VALUES(?,?,?)")) {
            for (String[] permission:permissions) {
                for (int i=0;i<permission.length;i++) insert.setString(i+1,permission[i]);
                insert.executeUpdate();
            }
        }
        try (var statement=connection.createStatement()) {
            statement.executeUpdate("INSERT INTO sys_role_permission(tenant_id,role_id,permission_id) SELECT r.tenant_id,r.id,p.id FROM sys_role r CROSS JOIN sys_permission p WHERE r.code='ADMIN' AND p.code IN ('orders:read','orders:write','order-settings:read','order-settings:write') AND NOT EXISTS(SELECT 1 FROM sys_role_permission rp WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND rp.permission_id=p.id)");
        }
    }
}
