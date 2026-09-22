package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.charset.StandardCharsets;

/** No SMS is sent, no account credited and no sample customer data inserted by this migration. */
public class V4__Sms_management extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();
        ScriptUtils.executeSqlScript(connection,new EncodedResource(new ClassPathResource("sql/sms-schema.sql"),StandardCharsets.UTF_8));
        String[][] permissions={
            {"sms-settings:read","查看短信设置","短信设置"}, {"sms-settings:write","维护短信设置","短信设置"},
            {"sms-records:read","查看短信发送记录","短信发送记录"}, {"sms-records:write","导出短信报表","短信发送记录"},
            {"sms-billing:read","查看短信余额与充值记录","短信余额充值"}, {"sms-billing:write","创建与取消短信充值订单","短信余额充值"}
        };
        try (var insert=connection.prepareStatement("INSERT INTO sys_permission(code,name,module) VALUES(?,?,?)")) {
            for (var permission:permissions) {
                for (int i=0;i<permission.length;i++) insert.setString(i+1,permission[i]);
                insert.executeUpdate();
            }
        }
        try (var statement=connection.createStatement()) {
            statement.executeUpdate("INSERT INTO sys_role_permission(tenant_id,role_id,permission_id) SELECT r.tenant_id,r.id,p.id FROM sys_role r CROSS JOIN sys_permission p WHERE r.code='ADMIN' AND p.code IN ('sms-settings:read','sms-settings:write','sms-records:read','sms-records:write','sms-billing:read','sms-billing:write') AND NOT EXISTS(SELECT 1 FROM sys_role_permission rp WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND rp.permission_id=p.id)");
        }
    }
}
