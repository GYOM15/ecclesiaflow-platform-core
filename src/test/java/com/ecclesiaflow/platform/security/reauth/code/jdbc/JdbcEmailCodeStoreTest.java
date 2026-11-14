package com.ecclesiaflow.platform.security.reauth.code.jdbc;

import com.ecclesiaflow.platform.security.reauth.code.EmailCodeChallenge;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeIssue;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeOccurrence;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeStatus;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The adapter's wiring of each statement; the statements themselves run on PostgreSQL in its integration test. */
@ExtendWith(MockitoExtension.class)
class JdbcEmailCodeStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private static final UUID ID = UUID.fromString("0f8d6a3e-5b1c-4d7e-9a2b-3c4d5e6f7a8b");
    private static final String SUB = "3f2c1d4e-1111-4222-8333-944455556666";

    @Mock
    private JdbcTemplate jdbc;

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private JdbcEmailCodeStore store() {
        return new JdbcEmailCodeStore(jdbc);
    }

    private static EmailCodeChallenge challenge() {
        return EmailCodeChallenge.open(ID, new EmailCodeIssue(SUB, "Subscription.Cancel", "billing.cancel",
                "ab".repeat(32), true, "c@x.org", null), "cd".repeat(32), NOW);
    }

    @Test
    @DisplayName("takes the account's lock in its own namespace, inside a transaction only")
    void lock() {
        assertThatThrownBy(() -> store().lockAccount(SUB)).isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(jdbc);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        store().lockAccount(SUB);

        verify(jdbc).queryForObject(JdbcEmailCodeStore.LOCK_SQL, Long.class, JdbcEmailCodeStore.LOCK_NAMESPACE,
                SUB.hashCode());
    }

    @Test
    @DisplayName("writes every field of a new challenge, its status by name")
    void insert() {
        EmailCodeChallenge challenge = challenge();

        store().insert(challenge);

        verify(jdbc).update(JdbcEmailCodeStore.INSERT_SQL, ID, SUB, "Subscription.Cancel", "billing.cancel",
                "ab".repeat(32), true, "cd".repeat(32), 0, Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(600)),
                Timestamp.from(NOW.plusSeconds(60)), "PENDING", Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(1800)), null);
    }

    @Test
    @DisplayName("updates the code, its counts and its state, and fails on a challenge it does not hold")
    void update() {
        EmailCodeChallenge voided = challenge().voided(NOW);
        when(jdbc.update(eq(JdbcEmailCodeStore.UPDATE_SQL), any(Object[].class))).thenReturn(1, 0);

        store().update(voided);

        assertThatThrownBy(() -> store().update(voided)).isInstanceOf(IllegalStateException.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(eq(JdbcEmailCodeStore.UPDATE_SQL), arguments.capture());
        assertThat(arguments.getAllValues().get(0)).containsExactly(null, 0, Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(600)), Timestamp.from(NOW.plusSeconds(60)), "VOID",
                Timestamp.from(NOW), ID);
    }

    @Test
    @DisplayName("consumes only a pending challenge, and says whether it did")
    void consume() {
        when(jdbc.update(JdbcEmailCodeStore.CONSUME_SQL, Timestamp.from(NOW), ID)).thenReturn(1, 0);

        assertThat(store().consume(ID, NOW)).isTrue();
        assertThat(store().consume(ID, NOW)).isFalse();
    }

    @Test
    @DisplayName("records and takes back one occurrence by its subject, kind, challenge and instant")
    void occurrences() {
        store().record(SUB, EmailCodeOccurrence.SENT, ID, NOW);
        store().forget(SUB, EmailCodeOccurrence.SENT, ID, NOW);

        verify(jdbc).update(JdbcEmailCodeStore.RECORD_SQL, SUB, "SENT", ID, Timestamp.from(NOW));
        verify(jdbc).update(JdbcEmailCodeStore.FORGET_SQL, SUB, "SENT", ID, Timestamp.from(NOW));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("reads a window's count and oldest instant, none when empty")
    void window() throws SQLException {
        when(jdbc.queryForObject(eq(JdbcEmailCodeStore.WINDOW_SQL), any(RowMapper.class), eq(SUB), eq("FAILED"),
                eq(Timestamp.from(NOW)))).thenAnswer(invocation -> {
                    RowMapper<EmailCodeStore.CounterWindow> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getInt("occurrences")).thenReturn(0);
                    when(rs.getTimestamp("oldest")).thenReturn(null);
                    return mapper.mapRow(rs, 0);
                });

        assertThat(store().window(SUB, EmailCodeOccurrence.FAILED, NOW))
                .isEqualTo(new EmailCodeStore.CounterWindow(0, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("maps a stored row back to its challenge, or finds none")
    void find() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(ID);
        when(rs.getString("subject")).thenReturn(SUB);
        when(rs.getString("action")).thenReturn("Subscription.Cancel");
        when(rs.getString("gesture")).thenReturn("billing.cancel");
        when(rs.getString("binding_digest")).thenReturn("ab".repeat(32));
        when(rs.getBoolean("keyed")).thenReturn(true);
        when(rs.getString("code_digest")).thenReturn("cd".repeat(32));
        when(rs.getInt("code_failures")).thenReturn(2);
        when(rs.getTimestamp("code_issued_at")).thenReturn(Timestamp.from(NOW));
        when(rs.getTimestamp("code_expires_at")).thenReturn(Timestamp.from(NOW.plusSeconds(600)));
        when(rs.getTimestamp("resend_available_at")).thenReturn(Timestamp.from(NOW.plusSeconds(60)));
        when(rs.getString("status")).thenReturn("PENDING");
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
        when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(NOW.plusSeconds(1800)));
        when(rs.getTimestamp("closed_at")).thenReturn(null);
        when(jdbc.query(eq(JdbcEmailCodeStore.FIND_SQL), any(RowMapper.class), eq(ID))).thenAnswer(invocation ->
                List.of(((RowMapper<EmailCodeChallenge>) invocation.getArgument(1)).mapRow(rs, 0)));
        when(jdbc.query(eq(JdbcEmailCodeStore.FIND_OPEN_SQL), any(RowMapper.class), eq(SUB))).thenReturn(List.of());

        assertThat(store().findForUpdate(ID)).get().satisfies(found -> {
            assertThat(found.status()).isEqualTo(EmailCodeStatus.PENDING);
            assertThat(found.codeFailures()).isEqualTo(2);
            assertThat(found.keyed()).isTrue();
            assertThat(found.closedAt()).isNull();
            assertThat(found.expiresAt()).isEqualTo(NOW.plusSeconds(1800));
        });
        assertThat(store().findOpen(SUB)).isEmpty();
    }

    @Test
    @DisplayName("purges challenges and occurrences before the cutoff, and counts both")
    void purge() {
        when(jdbc.update(JdbcEmailCodeStore.PURGE_CHALLENGES_SQL, Timestamp.from(NOW))).thenReturn(2);
        when(jdbc.update(JdbcEmailCodeStore.PURGE_OCCURRENCES_SQL, Timestamp.from(NOW))).thenReturn(5);

        assertThat(store().purgeBefore(NOW)).isEqualTo(7);
    }
}
