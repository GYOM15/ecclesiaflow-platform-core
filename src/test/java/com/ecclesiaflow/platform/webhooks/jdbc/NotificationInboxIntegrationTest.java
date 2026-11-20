package com.ecclesiaflow.platform.webhooks.jdbc;

import com.ecclesiaflow.platform.webhooks.InboundNotification;
import com.ecclesiaflow.platform.webhooks.NotificationReceipt;
import com.ecclesiaflow.platform.webhooks.NotificationVerifier;
import com.ecclesiaflow.platform.webhooks.RecordedNotification;
import com.ecclesiaflow.platform.webhooks.WebhookTestData;
import com.ecclesiaflow.platform.webhooks.processing.InboxProcessor;
import com.ecclesiaflow.platform.webhooks.processing.InboxRetryPolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The inbox's guarantees rest on PostgreSQL's unique key, row locks and the shipped trigger: a real server. */
@Testcontainers
class NotificationInboxIntegrationTest {

    private static final Instant START = Instant.parse("2026-11-20T10:00:00Z");
    private static final ApplicationEventPublisher NO_EVENTS = event -> { };

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private final MovableClock clock = new MovableClock(START);
    private final Verifier verifier = new Verifier();
    private JdbcNotificationInbox inbox;
    private JdbcInboxRepository repository;

    /** A clock the test moves, so a retry's backoff passes without waiting for it. */
    static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now;

        MovableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration by) {
            now.updateAndGet(instant -> instant.plus(by));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    /** Hands back the notification it is set to, as a provider's adapter would once the signature holds. */
    static final class Verifier implements NotificationVerifier {
        volatile InboundNotification next;

        @Override
        public String provider() {
            return "acme";
        }

        @Override
        public String endpoint() {
            return "accounts";
        }

        @Override
        public InboundNotification verify(byte[] body, Map<String, String> headers) {
            return next;
        }
    }

    @BeforeAll
    static void createTables() throws Exception {
        // A connection per call, so concurrent receivers really hold separate transactions.
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        // One statement for the whole file: the driver keeps the function's dollar-quoted body whole.
        jdbc.execute(InboxDdlTest.ddl());
    }

    @BeforeEach
    void emptyTables() {
        jdbc.execute("TRUNCATE inbound_notification_work, inbound_notification RESTART IDENTITY");
        TransactionTemplate requiresNew = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        inbox = new JdbcNotificationInbox(jdbc, requiresNew, (provider, account) -> !"acct_unknown".equals(account),
                false, NO_EVENTS, clock);
        repository = new JdbcInboxRepository(jdbc, requiresNew);
    }

    private NotificationReceipt receive(InboundNotification notification) {
        verifier.next = notification;
        return inbox.receive(verifier, WebhookTestData.BODY, Map.of());
    }

    private InboxProcessor processor(List<String> outcomes) {
        return new InboxProcessor(repository, notification -> {
            String outcome = outcomes.removeFirst();
            if (outcome.startsWith("fail")) {
                throw new IllegalStateException(outcome);
            }
            return outcome;
        }, new InboxRetryPolicy(10, Duration.ofSeconds(30), 2.0, Duration.ofHours(1)),
                new InboxProcessor.Settings(10, Duration.ofMinutes(5)), NO_EVENTS, clock);
    }

    private Map<String, Object> work(long id) {
        return jdbc.queryForMap("SELECT status, attempts, outcome, last_error, done_at, parked_at "
                + "FROM inbound_notification_work WHERE notification_id = ?", id);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Nested
    @DisplayName("receiving")
    class Receiving {

        @Test
        @DisplayName("records a notification once: its redelivery is a duplicate with no second row")
        void redelivery() {
            assertThat(receive(WebhookTestData.notification("evt_1"))).isEqualTo(NotificationReceipt.RECORDED);
            assertThat(receive(WebhookTestData.notification("evt_1"))).isEqualTo(NotificationReceipt.DUPLICATE);

            assertThat(count("inbound_notification")).isEqualTo(1);
            assertThat(count("inbound_notification_work")).isEqualTo(1);
            assertThat(work(1)).containsEntry("status", "PENDING").containsEntry("attempts", 0);
        }

        @Test
        @DisplayName("records one of the same notification delivered at once to several receivers")
        void concurrentDeliveries() throws Exception {
            int receivers = 8;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(receivers);
            try {
                List<Future<NotificationReceipt>> answers = new ArrayList<>();
                for (int i = 0; i < receivers; i++) {
                    Callable<NotificationReceipt> receiveOnce = () -> {
                        start.await();
                        Verifier own = new Verifier();
                        own.next = WebhookTestData.notification("evt_race");
                        return inbox.receive(own, WebhookTestData.BODY, Map.of());
                    };
                    answers.add(pool.submit(receiveOnce));
                }
                start.countDown();
                List<NotificationReceipt> receipts = new ArrayList<>();
                for (Future<NotificationReceipt> answer : answers) {
                    receipts.add(answer.get(30, TimeUnit.SECONDS));
                }

                assertThat(receipts).containsOnlyOnce(NotificationReceipt.RECORDED)
                        .filteredOn(receipt -> receipt != NotificationReceipt.RECORDED)
                        .containsOnly(NotificationReceipt.DUPLICATE);
                assertThat(count("inbound_notification")).isEqualTo(1);
                assertThat(count("inbound_notification_work")).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("keys a notification by its account too: one event id under two accounts is two notifications")
        void keyedByAccount() {
            receive(WebhookTestData.notification("evt_1", "acct_1", false));

            assertThat(receive(WebhookTestData.notification("evt_1", "acct_2", false)))
                    .isEqualTo(NotificationReceipt.RECORDED);
            assertThat(count("inbound_notification")).isEqualTo(2);
        }

        @Test
        @DisplayName("records nothing of a notification of the other mode")
        void otherMode() {
            assertThat(receive(WebhookTestData.notification("evt_1", "acct_1", true)))
                    .isEqualTo(NotificationReceipt.REFUSED_MODE);

            assertThat(count("inbound_notification")).isZero();
        }

        @Test
        @DisplayName("keeps the body's digest and identifiers, and refuses any change to them")
        void appendOnly() {
            receive(WebhookTestData.notification("evt_1"));

            assertThat(jdbc.queryForObject("SELECT payload_sha256 FROM inbound_notification", String.class))
                    .isEqualTo(InboundNotification.digestOf(WebhookTestData.BODY));
            assertThatThrownBy(() -> jdbc.update("UPDATE inbound_notification SET event_type = 'forged'"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> jdbc.update("DELETE FROM inbound_notification"))
                    .hasMessageContaining("append-only");
        }
    }

    @Nested
    @DisplayName("handling")
    class Handling {

        @Test
        @DisplayName("closes a handled notification with the handler's label")
        void handled() {
            receive(WebhookTestData.notification("evt_1"));

            processor(new ArrayList<>(List.of("APPLIED"))).processDue();

            assertThat(work(1)).containsEntry("status", "DONE").containsEntry("outcome", "APPLIED");
            assertThat(repository.countPending()).isZero();
        }

        @Test
        @DisplayName("parks a notification after ten failures in a row, then an operator's replay handles it")
        void parkedAfterTenFailuresThenReplayed() {
            receive(WebhookTestData.notification("evt_1"));
            List<String> outcomes = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                outcomes.add("fail " + i);
            }
            InboxProcessor failing = processor(outcomes);

            for (int attempt = 1; attempt <= 10; attempt++) {
                assertThat(failing.processDue().claimed()).as("attempt " + attempt).isEqualTo(1);
                assertThat(failing.processDue().claimed()).as("before its backoff").isZero();
                clock.advance(Duration.ofHours(1));
            }

            assertThat(work(1)).containsEntry("status", "PARKED").containsEntry("attempts", 10)
                    .containsEntry("last_error", "IllegalStateException");
            assertThat(failing.processDue().claimed()).isZero();
            assertThat(repository.countParked()).isEqualTo(1);

            assertThat(inbox.replayParked()).isEqualTo(1);
            assertThat(work(1)).containsEntry("status", "PENDING").containsEntry("attempts", 0)
                    .containsEntry("parked_at", null);
            processor(new ArrayList<>(List.of("APPLIED"))).processDue();
            assertThat(work(1)).containsEntry("status", "DONE");
        }

        @Test
        @DisplayName("never hands over a notification of an unknown account until an operator replays it")
        void unmatchedUntilReplayed() {
            assertThat(receive(WebhookTestData.notification("evt_1", "acct_unknown", false)))
                    .isEqualTo(NotificationReceipt.UNMATCHED);
            AtomicInteger handled = new AtomicInteger();
            InboxProcessor counting = new InboxProcessor(repository, notification -> {
                handled.incrementAndGet();
                return "APPLIED";
            }, new InboxRetryPolicy(10, Duration.ofSeconds(30), 2.0, Duration.ofHours(1)),
                    new InboxProcessor.Settings(10, Duration.ofMinutes(5)), NO_EVENTS, clock);

            counting.processDue();
            assertThat(handled).hasValue(0);
            assertThat(repository.countUnmatched()).isEqualTo(1);

            assertThat(inbox.replay(1)).isTrue();
            counting.processDue();
            assertThat(handled).hasValue(1);
            assertThat(inbox.replay(1)).as("done, so neither parked nor unmatched").isFalse();
        }

        @Test
        @DisplayName("gives two processors claiming at once different notifications")
        void concurrentClaims() throws Exception {
            for (int i = 0; i < 20; i++) {
                receive(WebhookTestData.notification("evt_" + i));
            }
            Instant now = clock.instant();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                Callable<List<RecordedNotification>> claim = () -> {
                    start.await();
                    return repository.claimDue(now, now.plus(Duration.ofMinutes(5)), 15);
                };
                Future<List<RecordedNotification>> first = pool.submit(claim);
                Future<List<RecordedNotification>> second = pool.submit(claim);
                start.countDown();

                List<Long> ids = new ArrayList<>();
                first.get(30, TimeUnit.SECONDS).forEach(notification -> ids.add(notification.id()));
                second.get(30, TimeUnit.SECONDS).forEach(notification -> ids.add(notification.id()));

                assertThat(ids).doesNotHaveDuplicates().hasSize(20);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("ignores the outcome of a processor whose lease ran out")
        void fencedOnTheLease() {
            receive(WebhookTestData.notification("evt_1"));
            Instant now = clock.instant();
            Instant firstLease = now.plus(Duration.ofMinutes(5));
            repository.claimDue(now, firstLease, 10);
            Instant later = firstLease.plusSeconds(1);
            repository.claimDue(later, later.plus(Duration.ofMinutes(5)), 10);

            repository.markDone(1, firstLease, "LATE", later);

            assertThat(work(1)).containsEntry("status", "PENDING");
        }
    }
}
