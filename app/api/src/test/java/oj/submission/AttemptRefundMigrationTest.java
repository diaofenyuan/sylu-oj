package oj.submission;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class AttemptRefundMigrationTest {
    @Test
    void upgrade_preserves_refunds_for_current_and_historical_system_errors() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:refundMigration;MODE=MySQL", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE submission (id BIGINT PRIMARY KEY, judge_status VARCHAR(4))");
            statement.execute("CREATE TABLE audit_event (target_type VARCHAR(32), target_id VARCHAR(64), action VARCHAR(64), after_value TEXT)");
            statement.execute("INSERT INTO submission VALUES (1, 'SE'), (2, 'AC'), (3, 'PD'), (4, 'AC')");
            statement.execute("INSERT INTO audit_event VALUES ('SUBMISSION', '2', 'JUDGE_RESULT_RECORDED', '{\"resultCode\": \"SE\"}'), ('SUBMISSION', '4', 'JUDGE_RESULT_RECORDED', '{\"resultCode\":\"AC\"}')");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V008__submission_attempt_refund.sql"));
            try (var rows = statement.executeQuery("SELECT attempt_refunded FROM submission ORDER BY id")) {
                for (boolean refunded : new boolean[]{true, true, false, false}) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean(1)).isEqualTo(refunded);
                }
                assertThat(rows.next()).isFalse();
            }
        }
    }
}
