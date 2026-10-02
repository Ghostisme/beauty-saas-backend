package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.charset.StandardCharsets;

/** Store-level appointment configuration and richer booking snapshots. */
public class V15__Appointment_settings extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        ScriptUtils.executeSqlScript(context.getConnection(), new EncodedResource(
            new ClassPathResource("sql/appointment-settings-schema.sql"), StandardCharsets.UTF_8));
    }
}
