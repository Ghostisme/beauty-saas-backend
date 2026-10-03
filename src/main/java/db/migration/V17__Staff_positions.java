package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.charset.StandardCharsets;
import java.sql.*;

public class V17__Staff_positions extends BaseJavaMigration {
    private static final String[][] DEFAULTS = {
        {"STORE_MANAGER", "店长"}, {"HOST", "主理人"}, {"FRONT_DESK", "前台"},
        {"BEAUTICIAN", "美容师"}, {"EMPLOYEE", "员工"}
    };

    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        ScriptUtils.executeSqlScript(connection, new EncodedResource(
            new ClassPathResource("sql/staff-position-schema.sql"), StandardCharsets.UTF_8));
        if (!hasPositionColumn(connection)) {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE sys_user ADD COLUMN position_id BIGINT");
                statement.executeUpdate("CREATE INDEX idx_user_position ON sys_user(tenant_id,position_id)");
            }
        }
        try (var tenants = connection.prepareStatement("SELECT id FROM sys_tenant");
             var rows = tenants.executeQuery();
             var insert = connection.prepareStatement("INSERT INTO biz_staff_position(tenant_id,code,name) VALUES(?,?,?)")) {
            while (rows.next()) {
                long tenantId = rows.getLong(1);
                for (var position : DEFAULTS) {
                    insert.setLong(1, tenantId);
                    insert.setString(2, position[0]);
                    insert.setString(3, position[1]);
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        }
    }

    private boolean hasPositionColumn(Connection connection) throws SQLException {
        try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "sys_user", "position_id")) {
            return columns.next();
        }
    }
}
