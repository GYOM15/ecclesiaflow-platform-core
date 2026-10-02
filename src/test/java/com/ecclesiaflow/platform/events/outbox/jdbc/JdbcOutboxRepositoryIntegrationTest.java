package com.ecclesiaflow.platform.events.outbox.jdbc;

import com.ecclesiaflow.platform.events.outbox.OutboxMessage;
import com.ecclesiaflow.platform.events.outbox.amqp.AmqpOutboxMessageMapper;
import com.ecclesiaflow.platform.events.outbox.relay.ClaimedOutboxMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JdbcOutboxRepositoryIntegrationTest {

    // Earlier than any real run, as the test claims at wall-clock time after the documented replay.
    private static final Instant STAGED_AT = Instant.parse("2026-09-01T10:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final String MEMBER_7 = "chat.group:42:member:7";
    private static final String MEMBER_8 = "chat.group:42:member:8";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = OutboxPostgres.container();

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcOutboxPublisher publisher;
    private static TransactionTemplate business;

    private JdbcOutboxRepository repository;

    @BeforeAll
    static void createTable() {
        dataSource = OutboxPostgres.dataSource(POSTGRES);
        OutboxPostgres.createTable(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        publisher = new JdbcOutboxPublisher(new JdbcTemplate(dataSource),
                new AmqpOutboxMessageMapper(new SimpleMessageConverter()), Clock.fixed(STAGED_AT, ZoneOffset.UTC));
        business = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void emptyTable() {
        jdbc.execute("TRUNCATE outbox_event RESTART IDENTITY");
        repository = new JdbcOutboxRepository(jdbc, OutboxPostgres.requiresNew(dataSource));
    }

    /** Stages one message per routing key, in order, under {@code key} ({@code null} for none). */
    private static void stage(String key, String... routingKeys) {
        business.executeWithoutResult(status -> {
            for (String routingKey : routingKeys) {
                publisher.append(new OutboxMessage("ecclesiaflow.domain-events", routingKey,
                        routingKey.getBytes(StandardCharsets.UTF_8), "application/x-protobuf", null, null, Map.of()),
                        key);
            }
        });
    }

    private List<String> claim(Instant now, int limit) {
        return routingKeys(repository.claimDue(now, now.plus(LEASE), limit));
    }

    private static List<String> routingKeys(List<ClaimedOutboxMessage> claimed) {
        return claimed.stream().map(row -> row.message().routingKey()).toList();
    }

    private static long idOf(String routingKey) {
        return jdbc.queryForObject("SELECT id FROM outbox_event WHERE routing_key = ?", Long.class, routingKey);
    }

    private static Map<String, Object> rowOf(String routingKey) {
        return jdbc.queryForMap(
                "SELECT status, attempts, last_error, parked_at FROM outbox_event WHERE routing_key = ?", routingKey);
    }

    /** Keeps the claim's transaction, and so its row locks, open until released: a relay caught mid-claim. */
    private static final class HeldTransactions implements TransactionOperations {
        private final TransactionOperations delegate;
        private final CountDownLatch claimed = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        HeldTransactions(TransactionOperations delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return delegate.execute(status -> {
                T result = action.doInTransaction(status);
                claimed.countDown();
                try {
                    if (!release.await(30, SECONDS)) {
                        throw new IllegalStateException("the held claim was never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return result;
            });
        }
    }

    @Nested
    @DisplayName("claimDue")
    class ClaimDue {

        @Test
        @DisplayName("Claims due rows oldest first, batch by batch, and leases them")
        void claimsInBatches() {
            stage(null, "m1", "m2", "m3", "m4", "m5");

            assertThat(claim(STAGED_AT.minusSeconds(1), 10)).as("not due yet").isEmpty();
            assertThat(claim(STAGED_AT, 2)).containsExactly("m1", "m2");
            assertThat(claim(STAGED_AT, 2)).containsExactly("m3", "m4");
            assertThat(claim(STAGED_AT, 2)).containsExactly("m5");
            assertThat(claim(STAGED_AT, 2)).as("every row is leased").isEmpty();
            assertThat(claim(STAGED_AT.plus(LEASE), 10)).as("the leases ran out")
                    .containsExactly("m1", "m2", "m3", "m4", "m5");
        }

        @Test
        @DisplayName("Lets a second relay skip the rows the first one is claiming, without waiting for it")
        void skipsLockedRows() throws Exception {
            stage(null, "m1", "m2", "m3", "m4", "m5", "m6");
            HeldTransactions held = new HeldTransactions(OutboxPostgres.requiresNew(dataSource));
            JdbcOutboxRepository firstRelay = new JdbcOutboxRepository(jdbc, held);
            ExecutorService relays = Executors.newFixedThreadPool(2);
            try {
                Future<List<ClaimedOutboxMessage>> first =
                        relays.submit(() -> firstRelay.claimDue(STAGED_AT, STAGED_AT.plus(LEASE), 3));
                assertThat(held.claimed.await(10, SECONDS)).isTrue();

                Future<List<ClaimedOutboxMessage>> second =
                        relays.submit(() -> repository.claimDue(STAGED_AT, STAGED_AT.plus(LEASE), 10));

                assertThat(routingKeys(second.get(10, SECONDS))).containsExactly("m4", "m5", "m6");
                held.release.countDown();
                assertThat(routingKeys(first.get(10, SECONDS))).containsExactly("m1", "m2", "m3");
            } finally {
                held.release.countDown();
                relays.shutdownNow();
            }
        }

        @Test
        @DisplayName("Lets no relay take the next message of a key another relay is claiming")
        void holdsKeyAcrossRelays() throws Exception {
            stage(MEMBER_7, "member-admitted", "member-removed");
            stage(null, "n1", "n2");
            HeldTransactions held = new HeldTransactions(OutboxPostgres.requiresNew(dataSource));
            JdbcOutboxRepository firstRelay = new JdbcOutboxRepository(jdbc, held);
            ExecutorService relays = Executors.newFixedThreadPool(2);
            try {
                Future<List<ClaimedOutboxMessage>> first =
                        relays.submit(() -> firstRelay.claimDue(STAGED_AT, STAGED_AT.plus(LEASE), 1));
                assertThat(held.claimed.await(10, SECONDS)).isTrue();

                Future<List<ClaimedOutboxMessage>> second =
                        relays.submit(() -> repository.claimDue(STAGED_AT, STAGED_AT.plus(LEASE), 10));

                assertThat(routingKeys(second.get(10, SECONDS))).containsExactly("n1", "n2");
                held.release.countDown();
                assertThat(routingKeys(first.get(10, SECONDS))).containsExactly("member-admitted");
            } finally {
                held.release.countDown();
                relays.shutdownNow();
            }
        }

        @Test
        @DisplayName("Has concurrent relays claim every row exactly once, and each key in staging order")
        void concurrentRelaysClaimOnceAndInOrder() throws Exception {
            int keys = 8;
            int perKey = 15;
            int unkeyed = 40;
            int total = keys * perKey + unkeyed;
            business.executeWithoutResult(status -> {
                for (int i = 0; i < perKey; i++) {
                    for (int k = 0; k < keys; k++) {
                        stage("key-" + k, "key-" + k + "/" + i);
                    }
                }
                for (int i = 0; i < unkeyed; i++) {
                    stage(null, "free/" + i);
                }
            });
            List<ClaimedOutboxMessage> claimOrder = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger sent = new AtomicInteger();
            long deadline = System.nanoTime() + SECONDS.toNanos(60);
            Callable<Void> relay = () -> {
                while (sent.get() < total && System.nanoTime() < deadline) {
                    List<ClaimedOutboxMessage> batch = repository.claimDue(STAGED_AT, STAGED_AT.plus(LEASE), 5);
                    if (batch.isEmpty()) {
                        LockSupport.parkNanos(MILLISECONDS.toNanos(5));
                        continue;
                    }
                    // Recorded before the rows are marked sent: the next row of a key cannot be claimed earlier.
                    claimOrder.addAll(batch);
                    repository.markSent(batch.stream().map(ClaimedOutboxMessage::id).toList(), STAGED_AT);
                    sent.addAndGet(batch.size());
                }
                return null;
            };
            ExecutorService relays = Executors.newFixedThreadPool(4);
            try {
                for (Future<Void> done : relays.invokeAll(List.of(relay, relay, relay, relay))) {
                    done.get();
                }
            } finally {
                relays.shutdownNow();
            }

            List<String> claimed = routingKeys(claimOrder);
            assertThat(claimed).hasSize(total).doesNotHaveDuplicates();
            for (int k = 0; k < keys; k++) {
                String prefix = "key-" + k + "/";
                assertThat(claimed.stream().filter(routingKey -> routingKey.startsWith(prefix)).toList())
                        .as("order of " + prefix)
                        .containsExactly(IntStream.range(0, perKey).mapToObj(i -> prefix + i).toArray(String[]::new));
            }
            assertThat(repository.countPending()).isZero();
        }
    }

    @Nested
    @DisplayName("ordering by key")
    class OrderingByKey {

        @Test
        @DisplayName("Claims at most one message of a key per batch")
        void oneMessagePerKey() {
            stage(MEMBER_7, "k1", "k2", "k3");

            assertThat(claim(STAGED_AT, 10)).containsExactly("k1");
        }

        @Test
        @DisplayName("Holds a removal back until the admission before it is relayed, whatever its retries")
        void retryHoldsBackItsKeyOnly() {
            stage(MEMBER_7, "member-admitted");
            stage(MEMBER_7, "member-removed");
            stage(MEMBER_8, "other-admitted");
            stage(null, "unkeyed");

            assertThat(claim(STAGED_AT, 10)).containsExactly("member-admitted", "other-admitted", "unkeyed");
            // The broker refuses the admission, which is retried 5 s later; the others went through.
            repository.reschedule(idOf("member-admitted"), STAGED_AT.plus(LEASE), 1, STAGED_AT.plusSeconds(5),
                    "broker nack");
            repository.markSent(List.of(idOf("other-admitted"), idOf("unkeyed")), STAGED_AT);
            stage(MEMBER_8, "other-removed");

            assertThat(claim(STAGED_AT.plusSeconds(1), 10)).as("another key goes on")
                    .containsExactly("other-removed");
            assertThat(claim(STAGED_AT.plusSeconds(6), 10)).as("the retry is due, the removal still waits for it")
                    .containsExactly("member-admitted");
            repository.markSent(List.of(idOf("member-admitted")), STAGED_AT.plusSeconds(6));
            assertThat(claim(STAGED_AT.plusSeconds(7), 10)).containsExactly("member-removed");
        }

        @Test
        @DisplayName("Holds a key behind a message left unconfirmed until its lease runs out")
        void unconfirmedMessageHoldsItsKey() {
            stage(MEMBER_7, "k1", "k2");

            assertThat(claim(STAGED_AT, 10)).containsExactly("k1");
            assertThat(claim(STAGED_AT.plusSeconds(60), 10)).as("the relay stopped before recording an outcome")
                    .isEmpty();
            assertThat(claim(STAGED_AT.plus(LEASE), 10)).as("relayed again, still ahead of k2")
                    .containsExactly("k1");

            repository.reschedule(idOf("k1"), STAGED_AT.plus(LEASE), 1, STAGED_AT, "late outcome of the stopped relay");
            assertThat(rowOf("k1")).as("fenced off by the new lease").containsEntry("attempts", 0);
        }
    }

    @Nested
    @DisplayName("parking")
    class Parking {

        @Test
        @DisplayName("Parks a message for good and holds its key until the documented replay")
        void parkedMessageHoldsItsKeyUntilReplayed() {
            stage(MEMBER_7, "k1", "k2");
            stage(null, "free");
            assertThat(claim(STAGED_AT, 10)).containsExactly("k1", "free");
            repository.markSent(List.of(idOf("free")), STAGED_AT);

            repository.park(idOf("k1"), STAGED_AT.plus(LEASE), 10, STAGED_AT, "unroutable, no queue bound");

            assertThat(rowOf("k1"))
                    .containsEntry("status", "PARKED")
                    .containsEntry("attempts", 10)
                    .containsEntry("last_error", "unroutable, no queue bound");
            assertThat(rowOf("k1").get("parked_at")).isNotNull();
            assertThat(repository.countParked()).isEqualTo(1);
            assertThat(claim(STAGED_AT.plus(Duration.ofDays(30)), 10)).as("never retried, and k2 waits").isEmpty();

            assertThat(jdbc.update(OutboxDdl.documentedStatement("UPDATE outbox_event"), idOf("k1"))).isEqualTo(1);

            Instant afterReplay = Instant.now().plusSeconds(60);
            assertThat(claim(afterReplay, 10)).containsExactly("k1");
            repository.markSent(List.of(idOf("k1")), afterReplay);
            assertThat(claim(afterReplay, 10)).containsExactly("k2");
        }

        @Test
        @DisplayName("Lets the key move on once the parked message is dropped as documented")
        void droppedParkedMessageReleasesItsKey() {
            stage(MEMBER_7, "k1", "k2");
            assertThat(claim(STAGED_AT, 10)).containsExactly("k1");
            repository.park(idOf("k1"), STAGED_AT.plus(LEASE), 10, STAGED_AT, "unroutable, no queue bound");

            assertThat(jdbc.update(OutboxDdl.documentedStatement("DELETE FROM outbox_event"), idOf("k1"))).isEqualTo(1);

            assertThat(claim(STAGED_AT.plus(LEASE), 10)).containsExactly("k2");
        }
    }
}
