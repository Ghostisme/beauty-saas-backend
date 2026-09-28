package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds the separate product brand field used by the inventory filters. */
public class V8__Catalog_brand extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE biz_item ADD COLUMN brand VARCHAR(100)");
        }
    }
}
