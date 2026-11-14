package com.ecclesiaflow.platform.security.reauth.code.jdbc;

import com.ecclesiaflow.platform.security.reauth.code.EmailCodeOccurrence;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReauthCodeDdlTest {

    @Test
    @DisplayName("ships every column the adapter's statements use, and those only")
    void columns() {
        assertThat(ReauthCodeDdl.columns("reauth_email_code")).containsExactly("id", "subject", "action", "gesture",
                "binding_digest", "keyed", "code_digest", "code_failures", "code_issued_at", "code_expires_at",
                "resend_available_at", "status", "created_at", "expires_at", "closed_at");
        assertThat(ReauthCodeDdl.columns("reauth_email_code_occurrence"))
                .containsExactly("id", "subject", "kind", "challenge_id", "occurred_at");
        List<String> challengeStatements = List.of(JdbcEmailCodeStore.FIND_OPEN_SQL, JdbcEmailCodeStore.FIND_SQL,
                JdbcEmailCodeStore.INSERT_SQL, JdbcEmailCodeStore.UPDATE_SQL, JdbcEmailCodeStore.CONSUME_SQL,
                JdbcEmailCodeStore.PURGE_CHALLENGES_SQL);
        List<String> occurrenceStatements = List.of(JdbcEmailCodeStore.RECORD_SQL, JdbcEmailCodeStore.FORGET_SQL,
                JdbcEmailCodeStore.WINDOW_SQL, JdbcEmailCodeStore.PURGE_OCCURRENCES_SQL);

        challengeStatements.forEach(sql -> assertThat(ReauthCodeDdl.columns("reauth_email_code")).as(sql)
                .containsAll(ReauthCodeDdl.columnsIn(sql)));
        occurrenceStatements.forEach(sql -> assertThat(ReauthCodeDdl.columns("reauth_email_code_occurrence")).as(sql)
                .containsAll(ReauthCodeDdl.columnsIn(sql)));
        assertThat(ReauthCodeDdl.columnsIn(JdbcEmailCodeStore.LOCK_SQL)).isEmpty();
    }

    @Test
    @DisplayName("constrains the status and the occurrence kind to the values the code writes")
    void closedValues() {
        assertThat(ReauthCodeDdl.text()).contains("CHECK (status IN ("
                + String.join(", ", Arrays.stream(EmailCodeStatus.values()).map(s -> "'" + s + "'").toList()) + "))");
        assertThat(ReauthCodeDdl.text()).contains("CHECK (kind IN ("
                + String.join(", ", Arrays.stream(EmailCodeOccurrence.values()).map(s -> "'" + s + "'").toList())
                + "))");
    }

    @Test
    @DisplayName("holds one pending challenge per account under the very predicate the lookup filters on")
    void onePendingPerAccount() {
        assertThat(ReauthCodeDdl.text())
                .contains("CREATE UNIQUE INDEX ux_reauth_email_code_pending ON reauth_email_code (subject) "
                        + "WHERE status = 'PENDING'");
        assertThat(JdbcEmailCodeStore.FIND_OPEN_SQL).contains("WHERE subject = ? AND status = 'PENDING'");
    }

    @Test
    @DisplayName("erases a voided code and dates every closing")
    void stateConstraints() {
        assertThat(ReauthCodeDdl.text())
                .contains("CHECK (status <> 'VOID' OR code_digest IS NULL)")
                .contains("CHECK ((status = 'PENDING') = (closed_at IS NULL))");
    }

    @Test
    @DisplayName("indexes the caps' window and both purges")
    void indexes() {
        assertThat(ReauthCodeDdl.text())
                .contains("ON reauth_email_code_occurrence (subject, kind, occurred_at)")
                .contains("ON reauth_email_code_occurrence (occurred_at)")
                .contains("ON reauth_email_code (created_at)");
    }

    @Test
    @DisplayName("is not a Flyway migration of the library itself, and stores neither code nor address")
    void notAMigration() {
        assertThat(ReauthCodeDdl.RESOURCE).doesNotStartWith("/db/migration");
        assertThat(ReauthCodeDdl.columns("reauth_email_code")).noneMatch(column -> column.equals("code")
                || column.contains("email") || column.contains("address"));
    }
}
