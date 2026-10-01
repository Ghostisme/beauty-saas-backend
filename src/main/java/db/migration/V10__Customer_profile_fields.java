package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds the profile fields used by the full customer dossier form. */
public class V10__Customer_profile_fields extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        // V9 creates these fields for fresh databases.  Older deployed
        // databases may already have V9 applied without the fields, so inspect
        // information_schema rather than relying on vendor-specific
        // `ADD COLUMN IF NOT EXISTS` syntax.
        addColumnIfMissing(context, "gender", "VARCHAR(10)");
        addColumnIfMissing(context, "birthday_type", "VARCHAR(10) DEFAULT '阳历'");
        addColumnIfMissing(context, "join_date", "DATE");
        addColumnIfMissing(context, "avatar_url", "MEDIUMTEXT");
        addColumnIfMissing(context, "referrer", "VARCHAR(80)");
        addColumnIfMissing(context, "initial_spent", "DECIMAL(14,2) NOT NULL DEFAULT 0");
        addColumnIfMissing(context, "referral_date", "DATE");
    }

    private static void addColumnIfMissing(Context context, String name, String definition) throws Exception {
        var connection = context.getConnection();
        try (var check = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.columns WHERE LOWER(table_name)=LOWER(?) AND LOWER(column_name)=LOWER(?)")) {
            check.setString(1, "biz_customer");
            check.setString(2, name);
            try (var result = check.executeQuery()) {
                result.next();
                if (result.getInt(1) > 0) return;
            }
        }
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE biz_customer ADD COLUMN " + name + " " + definition);
        }
    }
}
