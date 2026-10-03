package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.ResultSet;
import java.sql.Statement;

/** Stores the project commission matrix without changing the legacy rule columns. */
public class V23__Project_commission_config extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        boolean exists;
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns WHERE LOWER(table_name)=? AND LOWER(column_name)=?")) {
            statement.setString(1, "biz_commission_scheme");
            statement.setString(2, "config_json");
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                exists = result.getInt(1) > 0;
            }
        }
        if (!exists) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE biz_commission_scheme ADD COLUMN config_json TEXT");
            }
        }
    }
}
