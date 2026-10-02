package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.SQLException;
import java.util.ArrayList;

/** Adds batch metadata and catalog snapshots to customer-storage records. */
public class V16__Customer_storage_workflow extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        addColumnIfMissing(context, "batch_id", "VARCHAR(64)");
        addColumnIfMissing(context, "item_id", "BIGINT");
        addColumnIfMissing(context, "item_code", "VARCHAR(64)");
        addColumnIfMissing(context, "item_category", "VARCHAR(100)");
        addColumnIfMissing(context, "operator_id", "BIGINT");
        addColumnIfMissing(context, "operator_name", "VARCHAR(80)");
        addColumnIfMissing(context, "operation_type", "VARCHAR(20) DEFAULT 'CREATE'");
        addColumnIfMissing(context, "revoked", "TINYINT NOT NULL DEFAULT 0");
        addColumnIfMissing(context, "revoke_time", "TIMESTAMP");
        addColumnIfMissing(context, "revoked_by", "BIGINT");
        relaxPositiveQuantityCheck(context);

        // Existing rows were created before batch support.  Give each row a
        // stable legacy batch so the old API remains revocable/detailable.
        var connection = context.getConnection();
        var ids = new ArrayList<Long>();
        try (var statement = connection.prepareStatement("SELECT id FROM biz_customer_storage WHERE batch_id IS NULL")) {
            try (var result = statement.executeQuery()) {
                while (result.next()) ids.add(result.getLong(1));
            }
        }
        try (var update = connection.prepareStatement("UPDATE biz_customer_storage SET batch_id=?,operation_type=COALESCE(operation_type,'CREATE'),revoked=COALESCE(revoked,0) WHERE id=?")) {
            for (long id : ids) {
                update.setString(1, "legacy-" + id);
                update.setLong(2, id);
                update.addBatch();
            }
            if (!ids.isEmpty()) update.executeBatch();
        }
        try (var statement = connection.createStatement()) {
            try {
                statement.executeUpdate("CREATE INDEX idx_customer_storage_batch ON biz_customer_storage(tenant_id,batch_id,revoked)");
            } catch (SQLException ignored) {
                // The index may already exist on a partially migrated database.
            }
        }
    }

    private static void addColumnIfMissing(Context context, String name, String definition) throws Exception {
        var connection = context.getConnection();
        try (var check = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.columns WHERE LOWER(table_name)=LOWER(?) AND LOWER(column_name)=LOWER(?)")) {
            check.setString(1, "biz_customer_storage");
            check.setString(2, name);
            try (var result = check.executeQuery()) {
                result.next();
                if (result.getInt(1) > 0) return;
            }
        }
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE biz_customer_storage ADD COLUMN " + name + " " + definition);
        }
    }

    private static void relaxPositiveQuantityCheck(Context context) throws Exception {
        var connection = context.getConnection();
        var names = new ArrayList<String>();
        try (var check = connection.prepareStatement("SELECT tc.constraint_name,cc.check_clause FROM information_schema.table_constraints tc LEFT JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name WHERE LOWER(tc.table_name)=LOWER(?) AND LOWER(tc.constraint_type)='check'")) {
            check.setString(1, "biz_customer_storage");
            try (var result = check.executeQuery()) {
                while (result.next()) {
                    var clause = result.getString(2);
                    // H2 renders numeric checks as `"quantity" > CAST(0 AS
                    // NUMERIC(...))`, while MySQL usually keeps `quantity > 0`.
                    // We only need to distinguish the legacy strict-positive
                    // check from the new zero-allowed form.
                    var normalized = clause == null ? "" : clause.toLowerCase();
                    if (normalized.contains("quantity") && normalized.contains(">") && !normalized.contains(">=")) names.add(result.getString(1));
                }
            }
        } catch (SQLException ignored) {
            // Older MySQL-compatible information_schema variants may not expose
            // check clauses; the new schema already uses quantity >= 0.
        }
        for (var name : names) {
            if (!name.matches("[A-Za-z0-9_$]+")) continue;
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE biz_customer_storage DROP CONSTRAINT " + name);
            } catch (SQLException first) {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("ALTER TABLE biz_customer_storage DROP CHECK " + name);
                } catch (SQLException ignored) {
                    // Keep migration idempotent for databases that already removed it.
                }
            }
        }
    }
}
