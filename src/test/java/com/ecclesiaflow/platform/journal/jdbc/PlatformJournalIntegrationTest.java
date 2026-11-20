package com.ecclesiaflow.platform.journal.jdbc;

import com.ecclesiaflow.platform.journal.JournalActor;
import com.ecclesiaflow.platform.journal.JournalEntry;
import com.ecclesiaflow.platform.journal.JournalHead;
import com.ecclesiaflow.platform.journal.JournalIntegrity;
import com.ecclesiaflow.platform.journal.JournalLine;
import com.ecclesiaflow.platform.journal.JournalPage;
import com.ecclesiaflow.platform.journal.JournalPosition;
import com.ecclesiaflow.platform.journal.JournalPrivilegeException;
import com.ecclesiaflow.platform.journal.JournalQuery;
import com.ecclesiaflow.platform.journal.JournalRetention;
import com.ecclesiaflow.platform.journal.JournalVerdict;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
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
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The journal's guarantees rest on PostgreSQL's triggers, row lock, grants and SECURITY DEFINER functions: a real
 * server, with the roles a module runs under (an owner that never connects, an application role that writes).
 */
@Testcontainers
class PlatformJournalIntegrationTest {

    private static final String SUBJECT = "3f2c1d4e-1111-4222-8333-944455556666";
    private static final String TARGET = "8c1d2e3f-2222-4333-8444-a55566667777";
    private static final ApplicationEventPublisher NO_EVENTS = event -> { };
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String RESTRICT_VIOLATION = "23001";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** The container's superuser, standing in for the pre-boot step and for an intruder who holds the owner. */
    private static JdbcTemplate admin;
    private static DataSource appDataSource;
    private static JdbcTemplate app;

    private final MovableClock clock = new MovableClock(Instant.now().truncatedTo(ChronoUnit.MICROS));
    private TransactionTemplate gesture;
    private JdbcPlatformJournal journal;
    private JdbcJournalRepository repository;
    private JournalIntegrity integrity;

    static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now;

        MovableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void set(Instant instant) {
            now.set(instant);
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

    @BeforeAll
    static void createRoles() {
        admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        admin.execute("CREATE ROLE journal_owner NOLOGIN");
        admin.execute("CREATE ROLE journal_app LOGIN PASSWORD 'app'");
        admin.execute("CREATE ROLE journal_owner_login LOGIN PASSWORD 'owner' IN ROLE journal_owner");
        appDataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "journal_app", "app");
        app = new JdbcTemplate(appDataSource);
    }

    /** A fresh journal per test, installed the way a module's Flyway line installs it: as the schema's owner. */
    @BeforeEach
    void installJournal() throws Exception {
        admin.execute("DROP SCHEMA IF EXISTS platform_journal CASCADE");
        admin.execute("CREATE SCHEMA platform_journal AUTHORIZATION journal_owner");
        // One statement for the whole file: the driver keeps the functions' dollar-quoted bodies whole.
        admin.execute("SET ROLE journal_owner;\n" + JournalDdlTest.ddlFor("journal_app") + "\nRESET ROLE;");

        gesture = new TransactionTemplate(new DataSourceTransactionManager(appDataSource));
        TransactionTemplate snapshot = new TransactionTemplate(new DataSourceTransactionManager(appDataSource));
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        journal = new JdbcPlatformJournal(app, new ObjectMapper(), NO_EVENTS, clock);
        repository = new JdbcJournalRepository(app, new ObjectMapper());
        integrity = new JournalIntegrity(repository, snapshot, NO_EVENTS, clock, "members");
    }

    private JournalLine append(String gestureName) {
        JournalEntry entry = JournalEntry.done(JournalActor.of(SUBJECT, List.of("SUPPORT"), "sid-1"),
                "4bf92f3577b34da6a3ce929d0e0e4736", gestureName, "ACCOUNT", TARGET)
                .withDetail("channel", "KNOWN_PHONE");
        return gesture.execute(status -> journal.append(entry));
    }

    private static void asOwner(String sql) {
        admin.execute("SET ROLE journal_owner; " + sql + "; RESET ROLE;");
    }

    private static String sqlStateOf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    @Nested
    @DisplayName("the chain")
    class Chain {

        @Test
        @DisplayName("each line gets the next position and links to the one before; Java recomputes every hash")
        void chained() {
            JournalLine first = append("ACCOUNT_SEARCHED");
            JournalLine second = append("ACCOUNT_VIEWED");

            assertThat(first.seq()).isEqualTo(1);
            assertThat(first.prevHashHex()).isNull();
            assertThat(second.seq()).isEqualTo(2);
            assertThat(second.prevHashHex()).isEqualTo(first.rowHashHex());
            assertThat(integrity.head()).isEqualTo(new JournalHead(2, second.rowHashHex(), 0, null));
            assertThat(integrity.verify().verdict()).isEqualTo(JournalVerdict.intact(2));
        }

        @Test
        @DisplayName("the line returned is the line stored")
        void returnedIsStored() {
            JournalLine written = append("PHONE_REVEALED");

            assertThat(repository.lines(0, 1, 10)).containsExactly(written);
        }

        @Test
        @DisplayName("the Java canonical form escapes exactly as the database's, separators and backslashes included")
        void escapingAgrees() {
            // Raw inserts the application role could make: the database chains whatever it is given.
            app.update("INSERT INTO platform_journal.journal_line (occurred_at, actor_subject, actor_roles, gesture, "
                    + "outcome, target_kind, target_ref, details) VALUES (now(), 'a|b', ARRAY['R|1', 'R\\2'], 'G', "
                    + "'DONE', 'K', 'x\\|y', CAST('{\"k|1\": \"v\\\\|2\", \"a\": \"b\"}' AS jsonb))");
            append("ACCOUNT_VIEWED");

            assertThat(integrity.verify().verdict()).isEqualTo(JournalVerdict.intact(2));
        }

        @Test
        @DisplayName("concurrent gestures queue on the head: no fork, no gap, the chain verifies")
        void concurrentInserts() throws Exception {
            int writers = 8;
            int perWriter = 25;
            ExecutorService pool = Executors.newFixedThreadPool(writers);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<Long>>> results = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                Callable<List<Long>> writer = () -> {
                    start.await();
                    List<Long> positions = new ArrayList<>();
                    for (int i = 0; i < perWriter; i++) {
                        positions.add(append("ACCOUNT_VIEWED").seq());
                    }
                    return positions;
                };
                results.add(pool.submit(writer));
            }
            start.countDown();
            Set<Long> positions = new TreeSet<>();
            for (Future<List<Long>> result : results) {
                positions.addAll(result.get(60, TimeUnit.SECONDS));
            }
            pool.shutdown();

            assertThat(positions).hasSize(writers * perWriter).first().isEqualTo(1L);
            assertThat(positions).last().isEqualTo((long) writers * perWriter);
            assertThat(integrity.verify().verdict()).isEqualTo(JournalVerdict.intact(writers * perWriter));
        }

        @Test
        @DisplayName("a position or a hash the insert carries is overwritten by the database")
        void databaseSetsTheChain() {
            app.update("INSERT INTO platform_journal.journal_line (seq, occurred_at, actor_subject, gesture, outcome, "
                    + "target_kind, prev_hash, row_hash) VALUES (99, now(), 's', 'G', 'DONE', 'K', '\\x01'::bytea, "
                    + "'\\x02'::bytea)");

            assertThat(integrity.head().lastSeq()).isEqualTo(1);
            assertThat(integrity.verify().verdict().intact()).isTrue();
        }

        @Test
        @DisplayName("a multi-row insert fails loudly instead of giving two lines one position")
        void multiRowInsert() {
            assertThatThrownBy(() -> app.update("INSERT INTO platform_journal.journal_line (occurred_at, "
                    + "actor_subject, gesture, outcome, target_kind) VALUES (now(), 's', 'G', 'DONE', 'K'), "
                    + "(now(), 's', 'G', 'DONE', 'K')")).isInstanceOf(DataAccessException.class);
            assertThat(integrity.head().lastSeq()).isZero();
        }
    }

    @Nested
    @DisplayName("append only")
    class AppendOnly {

        @Test
        @DisplayName("the application role may neither update, delete nor truncate a line, nor touch the head")
        void applicationRole() {
            append("ACCOUNT_VIEWED");

            for (String sql : List.of("UPDATE platform_journal.journal_line SET target_ref = 'x'",
                    "DELETE FROM platform_journal.journal_line", "TRUNCATE platform_journal.journal_line",
                    "SELECT * FROM platform_journal.journal_head",
                    "UPDATE platform_journal.journal_head SET last_seq = 0, last_hash = NULL")) {
                assertThatThrownBy(() -> app.execute(sql)).as(sql)
                        .satisfies(failure -> assertThat(sqlStateOf(failure)).isEqualTo(INSUFFICIENT_PRIVILEGE));
            }
        }

        @Test
        @DisplayName("the owner too is refused UPDATE, DELETE and TRUNCATE by the triggers")
        void owner() {
            append("ACCOUNT_VIEWED");

            for (String sql : List.of("UPDATE platform_journal.journal_line SET target_ref = 'x'",
                    "DELETE FROM platform_journal.journal_line", "TRUNCATE platform_journal.journal_line",
                    "DELETE FROM platform_journal.journal_head", "TRUNCATE platform_journal.journal_head")) {
                assertThatThrownBy(() -> asOwner(sql)).as(sql)
                        .satisfies(failure -> assertThat(sqlStateOf(failure)).isEqualTo(RESTRICT_VIOLATION));
            }
        }

        @Test
        @DisplayName("the head only advances onto the line just written: never back, never onto an invented hash")
        void headGuard() {
            append("ACCOUNT_VIEWED");
            append("ACCOUNT_VIEWED");

            for (String sql : List.of(
                    "UPDATE platform_journal.journal_head SET last_seq = 1, "
                            + "last_hash = (SELECT row_hash FROM platform_journal.journal_line WHERE seq = 1)",
                    "UPDATE platform_journal.journal_head SET last_seq = 3, last_hash = '\\x00'::bytea",
                    "UPDATE platform_journal.journal_head SET start_seq = 1, start_hash = '\\x00'::bytea")) {
                assertThatThrownBy(() -> asOwner(sql)).as(sql)
                        .satisfies(failure -> assertThat(sqlStateOf(failure)).isEqualTo(RESTRICT_VIOLATION));
            }
            assertThat(integrity.verify().verdict()).isEqualTo(JournalVerdict.intact(2));
        }
    }

    @Nested
    @DisplayName("tampering past the triggers")
    class Tampering {

        @Test
        @DisplayName("lines removed from the end are found by the head")
        void removedTail() {
            for (int i = 0; i < 4; i++) {
                append("ACCOUNT_VIEWED");
            }

            asOwner("ALTER TABLE platform_journal.journal_line DISABLE TRIGGER trg_journal_line_no_delete; "
                    + "DELETE FROM platform_journal.journal_line WHERE seq >= 3; "
                    + "ALTER TABLE platform_journal.journal_line ENABLE TRIGGER trg_journal_line_no_delete");

            assertThat(integrity.verify().verdict())
                    .isEqualTo(JournalVerdict.broken(JournalVerdict.Status.TAIL_REMOVED, 2, 3));
            assertThat(integrity.position(3).state()).isEqualTo(JournalPosition.State.ABSENT);
        }

        @Test
        @DisplayName("a line rewritten in place is found by the verifier")
        void rewrittenLine() {
            for (int i = 0; i < 3; i++) {
                append("ACCOUNT_VIEWED");
            }

            asOwner("ALTER TABLE platform_journal.journal_line DISABLE TRIGGER trg_journal_line_no_update; "
                    + "UPDATE platform_journal.journal_line SET gesture = 'NOTHING_SEEN' WHERE seq = 2; "
                    + "ALTER TABLE platform_journal.journal_line ENABLE TRIGGER trg_journal_line_no_update");

            assertThat(integrity.verify().verdict())
                    .isEqualTo(JournalVerdict.broken(JournalVerdict.Status.LINE_REWRITTEN, 3, 2));
        }

        @Test
        @DisplayName("a line rewritten and re-hashed with the database's own function breaks the next link")
        void rehashedLine() {
            for (int i = 0; i < 3; i++) {
                append("ACCOUNT_VIEWED");
            }

            asOwner("ALTER TABLE platform_journal.journal_line DISABLE TRIGGER trg_journal_line_no_update; "
                    + "UPDATE platform_journal.journal_line SET gesture = 'NOTHING_SEEN' WHERE seq = 2; "
                    + "UPDATE platform_journal.journal_line l SET row_hash = sha256(l.prev_hash "
                    + "|| convert_to(platform_journal.canonical(l), 'UTF8')) WHERE seq = 2; "
                    + "ALTER TABLE platform_journal.journal_line ENABLE TRIGGER trg_journal_line_no_update");

            assertThat(integrity.verify().verdict())
                    .isEqualTo(JournalVerdict.broken(JournalVerdict.Status.LINK_BROKEN, 3, 3));
        }
    }

    @Nested
    @DisplayName("the purge")
    class Purge {

        @Test
        @DisplayName("removes the lines past 24 months, oldest first, and the rest still verifies from its start point")
        void verifiableAfterPurge() {
            Instant now = clock.instant();
            clock.set(now.minus(Duration.ofDays(800)));
            append("ACCOUNT_SEARCHED");
            append("ACCOUNT_VIEWED");
            JournalLine lastExpired = append("ACCOUNT_SUSPENDED");
            clock.set(now);
            append("ACCOUNT_VIEWED");
            append("JOURNAL_READ");
            JournalRetention retention = new JournalRetention(repository, NO_EVENTS, "members");

            assertThat(retention.purgeExpired()).isEqualTo(3);

            assertThat(integrity.head()).isEqualTo(new JournalHead(5, integrity.head().lastHashHex(), 3,
                    lastExpired.rowHashHex()));
            assertThat(integrity.verify().verdict()).isEqualTo(JournalVerdict.intact(2));
            assertThat(integrity.position(3)).isEqualTo(new JournalPosition(3, JournalPosition.State.START,
                    lastExpired.rowHashHex()));
            assertThat(integrity.position(1).state()).isEqualTo(JournalPosition.State.PURGED);

            append("ACCOUNT_VIEWED");
            assertThat(integrity.verify().verdict()).isEqualTo(JournalVerdict.intact(3));
            assertThat(retention.purgeExpired()).isZero();
        }

        @Test
        @DisplayName("stops at the first line inside the term, so a recent line is never removed with old ones")
        void stopsAtTheTerm() {
            Instant now = clock.instant();
            clock.set(now.minus(Duration.ofDays(800)));
            append("ACCOUNT_SEARCHED");
            clock.set(now);
            append("ACCOUNT_VIEWED");
            clock.set(now.minus(Duration.ofDays(800)));
            append("ACCOUNT_SEARCHED");

            assertThat(repository.purgeExpired()).isEqualTo(1);
            assertThat(repository.lines(0, 3, 10)).extracting(JournalLine::seq).containsExactly(2L, 3L);
            assertThat(integrity.verify().verdict().intact()).isTrue();
        }

        @Test
        @DisplayName("a journal purged to its last line is empty and intact, and the next line links to its start")
        void purgedEntirely() {
            clock.set(clock.instant().minus(Duration.ofDays(800)));
            append("ACCOUNT_SEARCHED");
            append("ACCOUNT_VIEWED");

            assertThat(repository.purgeExpired()).isEqualTo(2);
            assertThat(integrity.verify().verdict()).isEqualTo(JournalVerdict.intact(0));

            clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
            JournalLine next = append("ACCOUNT_VIEWED");
            assertThat(next.seq()).isEqualTo(3);
            assertThat(integrity.verify().verdict()).isEqualTo(JournalVerdict.intact(1));
        }
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("pages run newest first, filtered, and their cursors walk the whole journal once")
        void pages() {
            for (int i = 0; i < 5; i++) {
                append(i % 2 == 0 ? "ACCOUNT_VIEWED" : "ACCOUNT_SEARCHED");
            }
            JdbcJournalReader reader = new JdbcJournalReader(app, new ObjectMapper());

            JournalPage first = reader.list(JournalQuery.newest(2));
            JournalPage second = reader.list(new JournalQuery(null, null, null, null, null, null,
                    first.nextCursor(), 2));
            JournalPage last = reader.list(new JournalQuery(null, null, null, null, null, null,
                    second.nextCursor(), 2));
            JournalPage viewed = reader.list(new JournalQuery(null, null, SUBJECT, "ACCOUNT", TARGET,
                    "ACCOUNT_VIEWED", null, 50));

            assertThat(first.items()).extracting(JournalLine::seq).containsExactly(5L, 4L);
            assertThat(second.items()).extracting(JournalLine::seq).containsExactly(3L, 2L);
            assertThat(last.items()).extracting(JournalLine::seq).containsExactly(1L);
            assertThat(last.nextCursor()).isNull();
            assertThat(viewed.items()).extracting(JournalLine::seq).containsExactly(5L, 3L, 1L);
        }

        @Test
        @DisplayName("no value of any line is shaped like an address or a phone number")
        void noPersonalData() {
            append("ACCOUNT_VIEWED");
            append("PHONE_REVEALED");
            Pattern personal = Pattern.compile(".*@.*|\\+?[0-9][0-9 ().-]{6,}");

            List<String> values = app.queryForList("""
                    SELECT v FROM platform_journal.journal_line l,
                    LATERAL unnest(ARRAY[l.actor_subject, l.actor_session, l.request_id, l.gesture, l.outcome,
                                         l.refusal_code, l.target_kind, l.target_ref, l.case_ref, l.reason_code]
                                   || l.actor_roles
                                   || ARRAY(SELECT key || '=' || value FROM jsonb_each_text(l.details))) AS v
                    WHERE v IS NOT NULL""", String.class);

            assertThat(values).isNotEmpty().allSatisfy(value -> assertThat(personal.matcher(value).matches())
                    .as(value).isFalse());
        }
    }

    @Nested
    @DisplayName("the module's role")
    class Role {

        @Test
        @DisplayName("the application role starts: INSERT and SELECT on the lines, the two functions, nothing more")
        void applicationRole() {
            assertThatCode(() -> new JournalPrivilegeCheck(app).check()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a role that owns the journal, or a superuser, does not start")
        void ownerOrSuperuser() {
            JdbcTemplate ownerLogin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                    "journal_owner_login", "owner"));

            assertThatThrownBy(() -> new JournalPrivilegeCheck(ownerLogin).check())
                    .isInstanceOf(JournalPrivilegeException.class)
                    .hasMessageContaining("member of its owner");
            assertThatThrownBy(() -> new JournalPrivilegeCheck(admin).check())
                    .isInstanceOf(JournalPrivilegeException.class)
                    .hasMessageContaining("superuser");
        }

        @Test
        @DisplayName("a role granted more on the lines does not start")
        void grantedMore() {
            admin.execute("GRANT DELETE ON platform_journal.journal_line TO journal_app");
            try {
                assertThatThrownBy(() -> new JournalPrivilegeCheck(app).check())
                        .isInstanceOf(JournalPrivilegeException.class)
                        .hasMessageContaining("UPDATE, DELETE, TRUNCATE");
            } finally {
                admin.execute("REVOKE DELETE ON platform_journal.journal_line FROM journal_app");
            }
        }
    }
}
