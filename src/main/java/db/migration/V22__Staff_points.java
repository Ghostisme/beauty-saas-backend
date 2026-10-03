package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.charset.StandardCharsets;

public class V22__Staff_points extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        ScriptUtils.executeSqlScript(context.getConnection(), new EncodedResource(
            new ClassPathResource("sql/staff-points-schema.sql"), StandardCharsets.UTF_8));
    }
}
