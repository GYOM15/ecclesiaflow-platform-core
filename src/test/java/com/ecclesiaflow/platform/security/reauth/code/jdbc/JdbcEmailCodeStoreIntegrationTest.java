package com.ecclesiaflow.platform.security.reauth.code.jdbc;

import com.ecclesiaflow.platform.security.reauth.PendingConfirmation;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeChallenge;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeDelivery;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeIssue;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeMailer;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeOccurrence;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeSecrets;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeService;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeStatus;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeStore;
import com.ecclesiaflow.platform.security.reauth.code.InvalidReauthCodeException;
import com.ecclesiaflow.platform.security.reauth.code.ReauthCodeExpiredException;
import com.ecclesiaflow.platform.security.reauth.code.ReauthCodeLockedException;
import com.ecclesiaflow.platform.security.reauth.code.ReauthCodeNotSentException;
import com.ecclesiaflow.platform.security.reauth.code.ReauthProof;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The store on PostgreSQL holding the tables exactly as the library ships them, and the service on top of it where
 * the database, not the code, settles the outcome: the account's lock, the row locks, the one pending challenge.
 */
@Testcontainers
class JdbcEmailCodeStoreIntegrationTest {

    // Whole milliseconds: within what PostgreSQL keeps of an instant, so a row reads back equal.
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private static final String SUB = "3f2c1d4e-1111-4222-8333-944455556666";
    private static final String ADDRESS = "caleb@gmail.com";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    private JdbcEmailCodeStore store;

    /** Records each mail, and refuses them while {@link #failure} is set. */
    static final class RecordingMailer implements EmailCodeMailer {
        final List<EmailCodeDelivery> sent = Collections.synchronizedList(new ArrayList<>());
        volatile RuntimeException failure;

        @Override
        public void send(EmailCodeDelivery delivery) {
            if (failure != null) {
                throw failure;
            }
            sent.add(delivery);
        }

        String lastCode() {
            return sent.get(sent.size() - 1).code();
        }
    }

    @BeforeAll
    static void createTables() {
        // A connection per call, so concurrent requests really hold separate transactions.
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource(ReauthCodeDdl.RESOURCE)).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void emptyTables() {
        jdbc.execute("TRUNCATE reauth_email_code, reauth_email_code_occurrence RESTART IDENTITY");
        store = new JdbcEmailCodeStore(jdbc);
    }

    private static EmailCodeChallenge challenge(UUID id, String subject) {
        return EmailCodeChallenge.open(id, new EmailCodeIssue(subject, "Subscription.Cancel", "billing.cancel",
                "ab".repeat(32), true, ADDRESS, "fr"), "cd".repeat(32), NOW);
    }

    private <T> T inTransaction(Callable<T> work) {
        return transactions.execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Nested
    @DisplayName("the store")
    class Store {

        @Test
        @DisplayName("writes a challenge and reads it back as it was, by id and as the account's open one")
        void roundTrip() {
            EmailCodeChallenge written = challenge(UUID.randomUUID(), SUB);

            inTransaction(() -> {
                store.insert(written);
                return null;
            });

            assertThat(inTransaction(() -> store.findForUpdate(written.id()))).contains(written);
            assertThat(inTransaction(() -> store.findOpen(SUB))).contains(written);
            assertThat(inTransaction(() -> store.findOpen("someone-else"))).isEmpty();
        }

        @Test
        @DisplayName("updates a code, its count and its state, voiding erasing the digest")
        void update() {
            EmailCodeChallenge written = challenge(UUID.randomUUID(), SUB);
            inTransaction(() -> {
                store.insert(written);
                store.update(written.withFailedCode().withFailedCode());
                return null;
            });
            assertThat(inTransaction(() -> store.findForUpdate(written.id()))).get()
                    .extracting(EmailCodeChallenge::codeFailures).isEqualTo(2);

            inTransaction(() -> {
                store.update(written.voided(NOW.plusSeconds(1)));
                return null;
            });

            EmailCodeChallenge voided = inTransaction(() -> store.findForUpdate(written.id())).orElseThrow();
            assertThat(voided.status()).isEqualTo(EmailCodeStatus.VOID);
            assertThat(voided.codeDigest()).isNull();
            assertThat(voided.closedAt()).isEqualTo(NOW.plusSeconds(1));
            assertThatThrownBy(() -> inTransaction(() -> {
                store.update(challenge(UUID.randomUUID(), SUB));
                return null;
            })).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("holds an account to one pending challenge")
        void onePending() {
            inTransaction(() -> {
                store.insert(challenge(UUID.randomUUID(), SUB));
                return null;
            });

            assertThatThrownBy(() -> inTransaction(() -> {
                store.insert(challenge(UUID.randomUUID(), SUB));
                return null;
            })).isInstanceOf(DuplicateKeyException.class);
            assertThat(inTransaction(() -> {
                store.insert(challenge(UUID.randomUUID(), "another-account"));
                return true;
            })).isTrue();
        }

        @Test
        @DisplayName("refuses a voided challenge that keeps its digest, and a closed one without a date")
        void constraints() {
            EmailCodeChallenge written = challenge(UUID.randomUUID(), SUB);
            inTransaction(() -> {
                store.insert(written);
                return null;
            });

            assertThatThrownBy(() -> jdbc.update("UPDATE reauth_email_code SET status = 'VOID', closed_at = now() "
                    + "WHERE id = ?", written.id())).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE reauth_email_code SET status = 'CONSUMED' WHERE id = ?",
                    written.id())).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("consumes a pending challenge once, never a closed one")
        void consume() {
            EmailCodeChallenge written = challenge(UUID.randomUUID(), SUB);
            inTransaction(() -> {
                store.insert(written);
                return null;
            });

            assertThat(inTransaction(() -> store.consume(written.id(), NOW))).isTrue();
            assertThat(inTransaction(() -> store.consume(written.id(), NOW))).isFalse();
            assertThat(inTransaction(() -> store.findForUpdate(written.id()))).get()
                    .extracting(EmailCodeChallenge::status).isEqualTo(EmailCodeStatus.CONSUMED);
        }

        @Test
        @DisplayName("counts the occurrences strictly after the window's start, per account and kind")
        void window() {
            UUID id = UUID.randomUUID();
            inTransaction(() -> {
                store.record(SUB, EmailCodeOccurrence.FAILED, id, NOW.minusSeconds(3600));
                store.record(SUB, EmailCodeOccurrence.FAILED, id, NOW.minusSeconds(60));
                store.record(SUB, EmailCodeOccurrence.FAILED, id, NOW);
                store.record(SUB, EmailCodeOccurrence.SENT, id, NOW);
                store.record("another-account", EmailCodeOccurrence.FAILED, id, NOW);
                return null;
            });

            assertThat(inTransaction(() -> store.window(SUB, EmailCodeOccurrence.FAILED, NOW.minusSeconds(3600))))
                    .isEqualTo(new EmailCodeStore.CounterWindow(2, NOW.minusSeconds(60)));
            assertThat(inTransaction(() -> store.window(SUB, EmailCodeOccurrence.SENT, NOW.minusSeconds(1))))
                    .isEqualTo(new EmailCodeStore.CounterWindow(1, NOW));
            assertThat(inTransaction(() -> store.window(SUB, EmailCodeOccurrence.SENT, NOW)))
                    .isEqualTo(new EmailCodeStore.CounterWindow(0, null));
        }

        @Test
        @DisplayName("takes back exactly the send it is given")
        void forget() {
            UUID id = UUID.randomUUID();
            inTransaction(() -> {
                store.record(SUB, EmailCodeOccurrence.SENT, id, NOW.minusSeconds(60));
                store.record(SUB, EmailCodeOccurrence.SENT, id, NOW);
                store.forget(SUB, EmailCodeOccurrence.SENT, id, NOW);
                return null;
            });

            assertThat(inTransaction(() -> store.window(SUB, EmailCodeOccurrence.SENT, NOW.minusSeconds(3600))))
                    .isEqualTo(new EmailCodeStore.CounterWindow(1, NOW.minusSeconds(60)));
        }

        @Test
        @DisplayName("purges challenges and occurrences older than the cutoff")
        void purge() {
            UUID id = UUID.randomUUID();
            inTransaction(() -> {
                store.insert(challenge(id, SUB));
                store.record(SUB, EmailCodeOccurrence.SENT, id, NOW);
                store.record(SUB, EmailCodeOccurrence.FAILED, id, NOW.plusSeconds(10));
                return null;
            });

            assertThat(inTransaction(() -> store.purgeBefore(NOW.plusSeconds(1)))).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reauth_email_code_occurrence", Long.class))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("refuses the account's lock outside a transaction")
        void lockNeedsTransaction() {
            assertThatThrownBy(() -> store.lockAccount(SUB)).isInstanceOf(IllegalTransactionStateException.class);
        }

        @Test
        @DisplayName("holds a second transaction on the account's lock until the first ends, not another account")
        void lockSerializes() throws Exception {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                Future<?> holder = pool.submit(() -> inTransaction(() -> {
                    store.lockAccount(SUB);
                    locked.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return null;
                }));
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                Future<Long> same = pool.submit(() -> inTransaction(() -> {
                    store.lockAccount(SUB);
                    return System.nanoTime();
                }));
                Future<Long> other = pool.submit(() -> inTransaction(() -> {
                    store.lockAccount("another-account");
                    return System.nanoTime();
                }));

                assertThat(other.get(10, TimeUnit.SECONDS)).isPositive();
                Thread.sleep(300);
                assertThat(same.isDone()).isFalse();
                long released = System.nanoTime();
                release.countDown();
                assertThat(same.get(10, TimeUnit.SECONDS)).isGreaterThan(released);
                holder.get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("the service on PostgreSQL")
    class Service {

        private final RecordingMailer mailer = new RecordingMailer();
        private final EmailCodeSecrets secrets = new EmailCodeSecrets(new byte[32], new SecureRandom());
        private final List<Object> events = Collections.synchronizedList(new ArrayList<>());
        private final EmailCodeService service = new EmailCodeService(new JdbcEmailCodeStore(jdbc), secrets, mailer,
                new DataSourceTransactionManager(dataSource), events::add, Clock.systemUTC());

        private PendingConfirmation issue(String bindingDigest, boolean keyed) {
            return service.issue(new EmailCodeIssue(SUB, "Subscription.Cancel", "billing.cancel", bindingDigest,
                    keyed, ADDRESS, "fr"));
        }

        private static String wrong(String code) {
            return code.equals("000000") ? "000001" : "000000";
        }

        /** Two replays of the same proof at once; how many passed. */
        private int concurrentPasses(PendingConfirmation pending, String code, String bindingDigest)
                throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> replay = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        service.verify(SUB, new ReauthProof(pending.challengeId(), code), bindingDigest);
                        return true;
                    } catch (ReauthCodeExpiredException expired) {
                        return false;
                    }
                };
                Future<Boolean> first = pool.submit(replay);
                Future<Boolean> second = pool.submit(replay);
                start.countDown();
                return (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("lets one of two simultaneous replays without a key through, the other one 410")
        void concurrentReplaysWithoutKey() throws Exception {
            String binding = secrets.bindingDigest("Subscription.Cancel", SUB, null);
            PendingConfirmation pending = issue(binding, false);

            assertThat(concurrentPasses(pending, mailer.lastCode(), binding)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM reauth_email_code WHERE id = ?", String.class,
                    pending.challengeId())).isEqualTo("CONSUMED");
        }

        @Test
        @DisplayName("lets both replays of a keyed request through: the idempotency primitive keeps one effect")
        void concurrentReplaysWithKey() throws Exception {
            String binding = secrets.bindingDigest("Invoices.Pay", SUB, UUID.randomUUID());
            PendingConfirmation pending = issue(binding, true);

            assertThat(concurrentPasses(pending, mailer.lastCode(), binding)).isEqualTo(2);
        }

        @Test
        @DisplayName("kills a code at its fifth failure and locks the account at its tenth, despite a resend")
        void failuresAcrossResends() throws Exception {
            String binding = secrets.bindingDigest("Subscription.Cancel", SUB, null);
            PendingConfirmation pending = issue(binding, false);
            String first = mailer.lastCode();
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> service.verify(SUB, new ReauthProof(pending.challengeId(), wrong(first)),
                        binding)).isInstanceOf(InvalidReauthCodeException.class);
            }
            assertThatThrownBy(() -> service.verify(SUB, new ReauthProof(pending.challengeId(), first), binding))
                    .isInstanceOf(ReauthCodeExpiredException.class);
            // The service reads the JVM's clock, which the container's need not match.
            jdbc.update("UPDATE reauth_email_code SET resend_available_at = ? WHERE id = ?",
                    Timestamp.from(Instant.now().minusSeconds(1)), pending.challengeId());
            service.resend(SUB, pending.challengeId(), ADDRESS, "fr");
            String second = mailer.lastCode();
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> service.verify(SUB, new ReauthProof(pending.challengeId(), wrong(second)),
                        binding)).isInstanceOf(InvalidReauthCodeException.class);
            }

            assertThatThrownBy(() -> service.verify(SUB, new ReauthProof(pending.challengeId(), second), binding))
                    .isInstanceOf(ReauthCodeLockedException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reauth_email_code_occurrence WHERE kind = 'FAILED'",
                    Long.class)).isEqualTo(10);
        }

        @Test
        @DisplayName("voids the challenge a refused mail leaves and gives its send back")
        void refusedMail() {
            mailer.failure = new IllegalStateException("communication unreachable");

            assertThatThrownBy(() -> issue(secrets.bindingDigest("x"), false))
                    .isInstanceOf(ReauthCodeNotSentException.class);

            assertThat(jdbc.queryForObject("SELECT status FROM reauth_email_code", String.class)).isEqualTo("VOID");
            assertThat(jdbc.queryForObject("SELECT code_digest FROM reauth_email_code", String.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reauth_email_code_occurrence", Long.class))
                    .isZero();
        }

        @Test
        @DisplayName("replaces the open challenge with the next gesture's, in one pending row")
        void supersedes() {
            PendingConfirmation first = issue(secrets.bindingDigest("one"), false);
            PendingConfirmation second = issue(secrets.bindingDigest("two"), false);

            assertThat(jdbc.queryForList("SELECT id, status FROM reauth_email_code ORDER BY created_at, status"))
                    .hasSize(2);
            assertThat(jdbc.queryForObject("SELECT status FROM reauth_email_code WHERE id = ?", String.class,
                    first.challengeId())).isEqualTo("SUPERSEDED");
            assertThat(jdbc.queryForObject("SELECT status FROM reauth_email_code WHERE id = ?", String.class,
                    second.challengeId())).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("keeps neither the code nor the address in any column")
        void storesNoSecret() {
            issue(secrets.bindingDigest("x"), false);
            String code = mailer.lastCode();

            String row = jdbc.queryForList("SELECT * FROM reauth_email_code").toString()
                    + jdbc.queryForList("SELECT * FROM reauth_email_code_occurrence");
            assertThat(row).doesNotContain(ADDRESS).doesNotContain("\"" + code + "\"")
                    .doesNotContain("=" + code + ",");
        }
    }
}
