package com.ecclesiaflow.platform.events.outbox.jdbc;

import com.ecclesiaflow.platform.events.outbox.OutboxMessage;
import com.ecclesiaflow.platform.events.outbox.amqp.AmqpOutboxMessageMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JdbcOutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:15:30.123Z");
    private static final OutboxMessage MESSAGE = new OutboxMessage("ecclesiaflow.domain-events",
            "auth.setup-token.issued.v1", new byte[]{1, 2, 3}, "application/x-protobuf", null, null,
            Map.of("__TypeId__", "com.ecclesiaflow.grpc.events.auth.SetupTokenIssuedEvent"));

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private DataSource dataSource;
    @Mock
    private AmqpOutboxMessageMapper mapper;

    private JdbcOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new JdbcOutboxPublisher(jdbcTemplate, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    /** What a transaction manager bound to this DataSource leaves on the thread. */
    private void openTransactionOn(DataSource boundTo, boolean readOnly) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
        TransactionSynchronizationManager.bindResource(boundTo, new ConnectionHolder(mock(Connection.class)));
    }

    @Nested
    @DisplayName("outside the business transaction")
    class OutsideTransaction {

        @Test
        @DisplayName("Refuses to write when no transaction is active")
        void refusesWithoutTransaction() {
            assertThatThrownBy(() -> publisher.append(MESSAGE))
                    .isInstanceOf(IllegalTransactionStateException.class)
                    .hasMessageContaining("no transaction");
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("Refuses to write in a read-only transaction")
        void refusesReadOnlyTransaction() {
            openTransactionOn(dataSource, true);

            assertThatThrownBy(() -> publisher.append(MESSAGE))
                    .isInstanceOf(IllegalTransactionStateException.class)
                    .hasMessageContaining("read-only");
        }

        @Test
        @DisplayName("Refuses to write when the transaction runs on another DataSource")
        void refusesTransactionOnAnotherDataSource() {
            when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
            DataSource financeDataSource = mock(DataSource.class);
            openTransactionOn(financeDataSource, false);
            try {
                assertThatThrownBy(() -> publisher.append(MESSAGE))
                        .isInstanceOf(IllegalTransactionStateException.class)
                        .hasMessageContaining("commit on its own");
            } finally {
                TransactionSynchronizationManager.unbindResource(financeDataSource);
            }
        }

        @Test
        @DisplayName("Checks the transaction before converting the event")
        void checksTransactionBeforeConverting() {
            assertThatThrownBy(() -> publisher.append("ex", "rk", "event"))
                    .isInstanceOf(IllegalTransactionStateException.class);
            verifyNoInteractions(mapper, jdbcTemplate);
        }
    }

    @Nested
    @DisplayName("inside the business transaction")
    class InsideTransaction {

        @BeforeEach
        void transaction() {
            when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
            openTransactionOn(dataSource, false);
        }

        @Test
        @DisplayName("Inserts a pending row due now, on the transaction's connection")
        void insertsPendingRow() {
            publisher.append(MESSAGE);

            Timestamp now = Timestamp.from(NOW);
            verify(jdbcTemplate).update(JdbcOutboxPublisher.INSERT_SQL,
                    "ecclesiaflow.domain-events", "auth.setup-token.issued.v1", new byte[]{1, 2, 3},
                    "application/x-protobuf", null, null,
                    "{\"__TypeId__\":\"com.ecclesiaflow.grpc.events.auth.SetupTokenIssuedEvent\"}",
                    now, now);
        }

        @Test
        @DisplayName("Stages an event in the converter's wire shape")
        void stagesConvertedEvent() {
            when(mapper.toOutboxMessage("ecclesiaflow.domain-events", "auth.setup-token.issued.v1", "event"))
                    .thenReturn(MESSAGE);

            publisher.append("ecclesiaflow.domain-events", "auth.setup-token.issued.v1", "event");

            verify(jdbcTemplate).update(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("Lets a database failure roll the business transaction back")
        void databaseFailureSurfaces() {
            when(jdbcTemplate.update(anyString(), any(Object[].class)))
                    .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));

            assertThatThrownBy(() -> publisher.append(MESSAGE))
                    .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        }
    }
}
