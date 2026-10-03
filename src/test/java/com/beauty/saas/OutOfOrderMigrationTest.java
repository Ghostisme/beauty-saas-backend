package com.beauty.saas;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutOfOrderMigrationTest {
    @Test
    void appliesMissingV10AfterV11BeforeContinuingWithInventoryMigrations() throws Exception {
        String url = "jdbc:h2:mem:out_of_order_" + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("11").load().migrate();

        // Reproduce the deployed history: V11 had run before V10 was shipped.
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            assertThat(statement.executeUpdate("DELETE FROM flyway_schema_history WHERE version = '10'"))
                .isEqualTo(1);
        }

        var withoutFix = Flyway.configure().dataSource(url, "sa", "").load();
        assertThatThrownBy(withoutFix::migrate)
            .isInstanceOf(FlywayValidateException.class)
            .hasMessageContaining("not applied to database: 10");

        var withFix = Flyway.configure().dataSource(url, "sa", "").outOfOrder(true).load();
        int pending = withFix.info().pending().length;
        assertThat(pending).isGreaterThanOrEqualTo(6); // V10 and all later migrations
        assertThat(withFix.migrate().migrationsExecuted).isEqualTo(pending);
        assertThat(withFix.migrate().migrationsExecuted).isZero();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var result = statement.executeQuery(
                 "SELECT version, installed_rank, success FROM flyway_schema_history WHERE version IN ('10','11','12','13','14') ORDER BY installed_rank")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("version")).isEqualTo("11");
            for (String version : new String[] {"10", "12", "13", "14"}) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("version")).isEqualTo(version);
                assertThat(result.getBoolean("success")).isTrue();
            }
            assertThat(result.next()).isFalse();
        }
    }
}
