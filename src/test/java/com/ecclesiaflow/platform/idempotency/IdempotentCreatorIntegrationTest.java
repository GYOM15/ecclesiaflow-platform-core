package com.ecclesiaflow.platform.idempotency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The race on a real PostgreSQL: the unique index holds the second insert until the first commits, then
 * refuses it, and the loser answers with the winner's row read in a new transaction.
 */
@Testcontainers
class IdempotentCreatorIntegrationTest {

    private static final String OPERATION = "EventComment.Create";
    private static final UUID ALICE = UUID.fromString("7d0c2b1e-6a43-4c55-9a1e-2f4b8c9d0e11");
    private static final UUID BOB = UUID.fromString("1e2d3c4b-5a69-4788-9a0b-c1d2e3f40516");
    private static final UUID EVENT = UUID.fromString("0b6a8d4e-3c21-4f7a-8e90-5d1c2b3a4f56");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    private static JdbcTemplate jdbc;
    private static IdempotentCreator creator;

    record NewComment(UUID eventId, String body) {
    }

    record Comment(UUID id, UUID author, String body) {
    }

    private final List<Object> published = new java.util.concurrent.CopyOnWriteArrayList<>();
    private ExecutorService pool;

    @BeforeAll
    static void createTable() {
        // A connection per call, so the two requests hold separate transactions.
        DataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE event_comment (
                    id                      UUID PRIMARY KEY,
                    author_user_id          UUID NOT NULL,
                    event_id                UUID NOT NULL,
                    body                    TEXT NOT NULL,
                    idempotency_key         UUID,
                    idempotency_fingerprint TEXT
                )""");
        // Outside a transaction, as the adopters' own migration runs it.
        jdbc.execute("""
                CREATE UNIQUE INDEX CONCURRENTLY ux_event_comment_author_idempotency_key
                    ON event_comment (author_user_id, idempotency_key) WHERE idempotency_key IS NOT NULL""");
        creator = new IdempotentCreator(new DataSourceTransactionManager(dataSource), event -> { });
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE event_comment");
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /** The module's table through JdbcTemplate, with hooks to line both requests up at the insert. */
    static class CommentTable implements IdempotentCreation<UUID, NewComment, Comment> {

        CyclicBarrier bothLookedUp;
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(0);

        @Override
        public Optional<IdempotentRecord<Comment>> find(UUID author, UUID key) {
            return jdbc.query("""
                            SELECT id, author_user_id, body, idempotency_fingerprint FROM event_comment
                            WHERE author_user_id = ? AND idempotency_key = ?""",
                    (rs, n) -> new IdempotentRecord<>(new Comment(rs.getObject("id", UUID.class),
                            rs.getObject("author_user_id", UUID.class), rs.getString("body")),
                            rs.getString("idempotency_fingerprint")),
                    author, key).stream().findFirst();
        }

        @Override
        public String fingerprint(NewComment command) {
            return IdempotencyFingerprint.of(command.eventId(), command.body());
        }

        @Override
        public Comment insert(UUID author, NewComment command, IdempotencyStamp stamp) {
            if (bothLookedUp != null) {
                await(bothLookedUp);
            }
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO event_comment (id, author_user_id, event_id, body, idempotency_key,
                                               idempotency_fingerprint)
                    VALUES (?, ?, ?, ?, ?, ?)""",
                    id, author, command.eventId(), command.body(), stamp.key(), stamp.fingerprint());
            // Only the winner gets here before the other commits: the loser's insert waits on the index.
            firstInserted.countDown();
            await(releaseFirst);
            return new Comment(id, author, command.body());
        }

        private static void await(CyclicBarrier barrier) {
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private static void await(CountDownLatch latch) {
            try {
                assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static Optional<IdempotencyKey> key(UUID value) {
        return Optional.of(new IdempotencyKey(value, OPERATION));
    }

    private static long rows() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM event_comment", Long.class);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("two simultaneous requests with one key: one row, the loser held by the index then replaying")
    void simultaneousRequests() throws Exception {
        UUID key = UUID.randomUUID();
        CommentTable table = new CommentTable();
        table.bothLookedUp = new CyclicBarrier(2);
        table.releaseFirst = new CountDownLatch(1);

        CompletableFuture<CreationOutcome<Comment>> first = CompletableFuture.supplyAsync(
                () -> creator.create(ALICE, new NewComment(EVENT, "Hello"), key(key), table), pool);
        CompletableFuture<CreationOutcome<Comment>> second = CompletableFuture.supplyAsync(
                () -> creator.create(ALICE, new NewComment(EVENT, "Hello"), key(key), table), pool);

        assertThat(table.firstInserted.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);
        assertThat(first.isDone() || second.isDone())
                .as("the loser waits on the index while the winner has not committed").isFalse();
        table.releaseFirst.countDown();

        List<CreationOutcome<Comment>> outcomes = List.of(first.get(10, TimeUnit.SECONDS),
                second.get(10, TimeUnit.SECONDS));
        assertThat(rows()).isEqualTo(1);
        assertThat(outcomes).extracting(outcome -> outcome.resource().id()).containsOnly(
                jdbc.queryForObject("SELECT id FROM event_comment", UUID.class));
        assertThat(outcomes).extracting(CreationOutcome::replayed).containsExactlyInAnyOrder(false, true);
    }

    @Test
    @DisplayName("two simultaneous requests with one key and different bodies: one row, the loser refused")
    void simultaneousConflict() throws Exception {
        UUID key = UUID.randomUUID();
        CommentTable table = new CommentTable();
        table.bothLookedUp = new CyclicBarrier(2);

        CompletableFuture<CreationOutcome<Comment>> hello = CompletableFuture.supplyAsync(
                () -> creator.create(ALICE, new NewComment(EVENT, "Hello"), key(key), table), pool);
        CompletableFuture<CreationOutcome<Comment>> goodbye = CompletableFuture.supplyAsync(
                () -> creator.create(ALICE, new NewComment(EVENT, "Goodbye"), key(key), table), pool);

        List<Throwable> failures = new java.util.ArrayList<>();
        for (CompletableFuture<CreationOutcome<Comment>> request : List.of(hello, goodbye)) {
            try {
                request.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                failures.add(e.getCause());
            }
        }
        assertThat(rows()).isEqualTo(1);
        assertThat(failures).singleElement().isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    @DisplayName("in sequence: a retry replays, another author's key creates, no key creates every time")
    void sequential() {
        UUID key = UUID.randomUUID();
        CommentTable table = new CommentTable();

        CreationOutcome<Comment> created = creator.create(ALICE, new NewComment(EVENT, "Hello"), key(key), table);
        CreationOutcome<Comment> replayed = creator.create(ALICE, new NewComment(EVENT, "Hello"), key(key), table);
        creator.create(BOB, new NewComment(EVENT, "Hello"), key(key), table);
        creator.create(ALICE, new NewComment(EVENT, "Hello"), Optional.empty(), table);
        creator.create(ALICE, new NewComment(EVENT, "Hello"), Optional.empty(), table);

        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.resource().id()).isEqualTo(created.resource().id());
        assertThat(rows()).isEqualTo(4);
        assertThatThrownBy(() -> creator.create(ALICE, new NewComment(EVENT, "Goodbye"), key(key), table))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    @DisplayName("PostgreSQL's refusal reaches the creator as the duplicate key it recognises")
    void driverRefusal() {
        UUID key = UUID.randomUUID();
        jdbc.update("INSERT INTO event_comment VALUES (?, ?, ?, 'x', ?, 'f')", UUID.randomUUID(), ALICE, EVENT, key);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO event_comment VALUES (?, ?, ?, 'y', ?, 'g')",
                UUID.randomUUID(), ALICE, EVENT, key))
                .isInstanceOf(DuplicateKeyException.class)
                .satisfies(e -> assertThat(IdempotentCreator.isUniqueViolation(e)).isTrue());
    }
}
