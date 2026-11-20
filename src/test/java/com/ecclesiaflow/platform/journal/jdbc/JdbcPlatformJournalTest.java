package com.ecclesiaflow.platform.journal.jdbc;

import com.ecclesiaflow.platform.journal.JournalActor;
import com.ecclesiaflow.platform.journal.JournalEntry;
import com.ecclesiaflow.platform.journal.JournalLine;
import com.ecclesiaflow.platform.journal.JournalOutcome;
import com.ecclesiaflow.platform.journal.events.JournalEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.ecclesiaflow.platform.journal.JournalTestData.SUBJECT;
import static com.ecclesiaflow.platform.journal.JournalTestData.TARGET;
import static com.ecclesiaflow.platform.journal.JournalTestData.TRACE;
import static com.ecclesiaflow.platform.journal.JournalTestData.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JdbcPlatformJournalTest {

    // Nanoseconds the column cannot hold: the line returned must be the one stored.
    private static final Instant NOW = Instant.parse("2026-11-20T15:04:05.123456789Z");
    private static final Instant STORED = Instant.parse("2026-11-20T15:04:05.123456Z");

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private DataSource dataSource;
    @Mock
    private ApplicationEventPublisher events;

    private JdbcPlatformJournal journal;

    @BeforeEach
    void setUp() {
        journal = new JdbcPlatformJournal(jdbcTemplate, new ObjectMapper(), events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private void openTransactionOn(DataSource boundTo, boolean readOnly) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
        TransactionSynchronizationManager.bindResource(boundTo, new ConnectionHolder(mock(Connection.class)));
    }

    @Nested
    @DisplayName("outside the gesture's transaction")
    class OutsideTransaction {

        @Test
        @DisplayName("refuses to write without a transaction")
        void noTransaction() {
            assertThatThrownBy(() -> journal.append(entry()))
                    .isInstanceOf(IllegalTransactionStateException.class)
                    .hasMessageContaining("no transaction is active");
            verifyNoInteractions(jdbcTemplate, events);
        }

        @Test
        @DisplayName("refuses a read-only transaction: a reading is journaled in a writable one")
        void readOnly() {
            openTransactionOn(dataSource, true);

            assertThatThrownBy(() -> journal.append(entry()))
                    .isInstanceOf(IllegalTransactionStateException.class)
                    .hasMessageContaining("read-only");
        }

        @Test
        @DisplayName("refuses a transaction on another DataSource, where the line would commit on its own")
        void otherDataSource() {
            when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
            openTransactionOn(mock(DataSource.class), false);

            assertThatThrownBy(() -> journal.append(entry()))
                    .isInstanceOf(IllegalTransactionStateException.class)
                    .hasMessageContaining("no connection of the journal's DataSource");
        }
    }

    @Nested
    @DisplayName("inside the gesture's transaction")
    class InsideTransaction {

        @Test
        @DisplayName("writes one line with every field bound, and returns it with the position and hashes set")
        @SuppressWarnings("unchecked")
        void writes() throws Exception {
            when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
            openTransactionOn(dataSource, false);
            ResultSet returned = mock(ResultSet.class);
            when(returned.getLong("seq")).thenReturn(8L);
            when(returned.getString("prev_hash")).thenReturn("77".repeat(32));
            when(returned.getString("row_hash")).thenReturn("88".repeat(32));
            when(jdbcTemplate.queryForObject(eq(JdbcPlatformJournal.INSERT_SQL), any(RowMapper.class), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenAnswer(call -> ((RowMapper<JournalLine>) call.getArgument(1)).mapRow(returned, 0));
            JournalEntry entry = entry().withCase("rpt-5").withReason("ABUSE").withDetail("channel", "KNOWN_PHONE")
                    .refused("SELF_TARGET");

            JournalLine line = journal.append(entry);

            verify(jdbcTemplate).queryForObject(eq(JdbcPlatformJournal.INSERT_SQL), any(RowMapper.class),
                    eq(Timestamp.from(STORED)), eq(SUBJECT), eq("{SUPPORT}"), eq(entry.actor().sessionDigest()),
                    eq(TRACE), eq("ACCOUNT_VIEWED"), eq("REFUSED"), eq("SELF_TARGET"), eq("ACCOUNT"), eq(TARGET),
                    eq("rpt-5"), eq("ABUSE"), eq("{\"channel\":\"KNOWN_PHONE\"}"));
            assertThat(line.seq()).isEqualTo(8);
            assertThat(line.occurredAt()).isEqualTo(STORED);
            assertThat(line.outcome()).isEqualTo("REFUSED");
            assertThat(line.actorRoles()).containsExactly("SUPPORT");
            assertThat(line.details()).containsEntry("channel", "KNOWN_PHONE");
            assertThat(line.prevHashHex()).isEqualTo("77".repeat(32));
            assertThat(line.rowHashHex()).isEqualTo("88".repeat(32));
            ArgumentCaptor<JournalEvents.LineAppended> event =
                    ArgumentCaptor.forClass(JournalEvents.LineAppended.class);
            verify(events).publishEvent(event.capture());
            assertThat(event.getValue()).isEqualTo(new JournalEvents.LineAppended("ACCOUNT_VIEWED",
                    JournalOutcome.REFUSED));
        }

        @Test
        @DisplayName("an actor without roles writes an empty array, a line without details an empty object")
        @SuppressWarnings("unchecked")
        void emptyCollections() {
            when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
            openTransactionOn(dataSource, false);
            JournalEntry entry = JournalEntry.done(JournalActor.of(SUBJECT, List.of(), null), null, "JOURNAL_READ",
                    "JOURNAL", null);

            journal.append(entry);

            verify(jdbcTemplate).queryForObject(eq(JdbcPlatformJournal.INSERT_SQL), any(RowMapper.class), any(),
                    anyString(), eq("{}"), isNull(), isNull(), anyString(), anyString(), isNull(), anyString(),
                    isNull(), isNull(), isNull(), eq("{}"));
        }
    }
}
