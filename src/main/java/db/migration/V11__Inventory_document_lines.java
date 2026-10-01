package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;

/** Adds product lines to inventory movement documents and quantity to batches. */
public class V11__Inventory_document_lines extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        ScriptUtils.executeSqlScript(context.getConnection(), new EncodedResource(
            new ClassPathResource("sql/inventory-document-lines-schema.sql"), StandardCharsets.UTF_8));
    }
}
