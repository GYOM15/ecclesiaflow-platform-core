package com.ecclesiaflow.platform.idempotency;

import com.ecclesiaflow.platform.error.DataIntegrityTestData;
import com.ecclesiaflow.platform.idempotency.CommentTable.Comment;
import com.ecclesiaflow.platform.idempotency.CommentTable.NewComment;
import com.ecclesiaflow.platform.idempotency.events.IdempotencyEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotentCreatorTest {

    private static final String OPERATION = "EventComment.Create";
    private static final String AUTHOR = "7d0c2b1e-6a43-4c55-9a1e-2f4b8c9d0e11";
    private static final UUID EVENT = UUID.fromString("0b6a8d4e-3c21-4f7a-8e90-5d1c2b3a4f56");
    private static final UUID KEY = UUID.fromString("5f0e9d8c-7b6a-4e3d-9c2b-1a0f9e8d7c6b");
    private static final NewComment HELLO = new NewComment(EVENT, "Hello");

    private final RecordingTransactionManager transactions = new RecordingTransactionManager();
    private final List<Object> published = new CopyOnWriteArrayList<>();
    private final ApplicationEventPublisher events = published::add;
    private final IdempotentCreator creator = new IdempotentCreator(transactions, events);
    private final CommentTable table = new CommentTable();

    private static Optional<IdempotencyKey> key(UUID value) {
        return Optional.of(new IdempotencyKey(value, OPERATION));
    }

    @AfterEach
    void leaveNoTransactionBehind() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Nested
    @DisplayName("a first request")
    class FirstRequest {

        @Test
        @DisplayName("without a key creates as before, stores no key and never looks one up")
        void withoutKey() {
            CreationOutcome<Comment> first = creator.create(AUTHOR, HELLO, Optional.empty(), table);
            CreationOutcome<Comment> second = creator.create(AUTHOR, HELLO, Optional.empty(), table);

            assertThat(first.replayed()).isFalse();
            assertThat(second.replayed()).isFalse();
            assertThat(table.rows()).hasSize(2).allSatisfy(row -> {
                assertThat(row.key()).isNull();
                assertThat(row.fingerprint()).isNull();
            });
            assertThat(table.finds()).isZero();
            assertThat(transactions.log()).containsExactly("begin", "commit", "begin", "commit");
            assertThat(published).isEmpty();
        }

        @Test
        @DisplayName("with a new key looks up then inserts in one transaction, stamping the key and fingerprint")
        void withNewKey() {
            CreationOutcome<Comment> outcome = creator.create(AUTHOR, HELLO, key(KEY), table);

            assertThat(outcome.replayed()).isFalse();
            assertThat(table.rows()).singleElement().satisfies(row -> {
                assertThat(row.key()).isEqualTo(KEY);
                assertThat(row.fingerprint()).isEqualTo(IdempotencyFingerprint.of(EVENT, "Hello"));
                assertThat(outcome.resource()).isSameAs(row);
            });
            assertThat(transactions.log()).containsExactly("begin", "commit");
            assertThat(published).isEmpty();
        }

        @Test
        @DisplayName("a failure of the creation itself rolls back and propagates untouched")
        void creationFails() {
            IllegalStateException failure = new IllegalStateException("event closed");
            CommentTable closed = new CommentTable() {
                @Override
                public Comment insert(String author, NewComment command, IdempotencyStamp stamp) {
                    throw failure;
                }
            };

            assertThatThrownBy(() -> creator.create(AUTHOR, HELLO, key(KEY), closed)).isSameAs(failure);
            assertThat(transactions.log()).containsExactly("begin", "rollback");
            assertThat(closed.finds()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("a retry under a key already stored")
    class Retry {

        @Test
        @DisplayName("with the same request replays the stored row without inserting")
        void replays() {
            Comment stored = table.seed(AUTHOR, HELLO, KEY);

            CreationOutcome<Comment> outcome = creator.create(AUTHOR, new NewComment(EVENT, "Hello"), key(KEY), table);

            assertThat(outcome.replayed()).isTrue();
            assertThat(outcome.resource()).isSameAs(stored);
            assertThat(table.inserts()).isEqualTo(1);
            assertThat(table.rows()).hasSize(1);
            assertThat(published).containsExactly(new IdempotencyEvents.Replayed(OPERATION, AUTHOR, KEY, false));
        }

        @Test
        @DisplayName("with another request is refused, and nothing is written")
        void conflicts() {
            table.seed(AUTHOR, HELLO, KEY);

            assertThatThrownBy(() -> creator.create(AUTHOR, new NewComment(EVENT, "Goodbye"), key(KEY), table))
                    .isInstanceOf(IdempotencyKeyConflictException.class)
                    .satisfies(e -> assertThat(((IdempotencyKeyConflictException) e).getOperation())
                            .isEqualTo(OPERATION));
            assertThat(table.rows()).hasSize(1);
            assertThat(table.inserts()).isEqualTo(1);
            assertThat(published).containsExactly(new IdempotencyEvents.Conflicted(OPERATION, AUTHOR, KEY, false));
        }

        @Test
        @DisplayName("on another path parameter is refused: the path is part of the request")
        void otherPathConflicts() {
            table.seed(AUTHOR, HELLO, KEY);

            assertThatThrownBy(() -> creator.create(AUTHOR, new NewComment(UUID.randomUUID(), "Hello"), key(KEY),
                    table)).isInstanceOf(IdempotencyKeyConflictException.class);
        }

        @Test
        @DisplayName("by another author creates: a key is only unique per author")
        void otherAuthor() {
            Comment stored = table.seed(AUTHOR, HELLO, KEY);

            CreationOutcome<Comment> outcome = creator.create("another-author", HELLO, key(KEY), table);

            assertThat(outcome.replayed()).isFalse();
            assertThat(outcome.resource()).isNotEqualTo(stored);
            assertThat(table.rows()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("two requests in flight with one key")
    class Race {

        /** The other request commits its row between this one's lookup and its insert. */
        private CommentTable lostRace(NewComment winnerRequest) {
            return new CommentTable() {
                private boolean winnerCommitted;

                @Override
                public Optional<IdempotentRecord<Comment>> find(String author, UUID key) {
                    Optional<IdempotentRecord<Comment>> found = super.find(author, key);
                    if (!winnerCommitted) {
                        winnerCommitted = true;
                        seed(AUTHOR, winnerRequest, KEY);
                    }
                    return found;
                }
            };
        }

        @Test
        @DisplayName("the unique index refuses the loser, which reads the winner in a new read-only "
                + "transaction and replays it")
        void loserReplaysWinner() {
            CommentTable lost = lostRace(HELLO);

            CreationOutcome<Comment> outcome = creator.create(AUTHOR, HELLO, key(KEY), lost);

            assertThat(outcome.replayed()).isTrue();
            assertThat(lost.rows()).singleElement().isSameAs(outcome.resource());
            assertThat(transactions.log()).containsExactly("begin", "rollback", "begin read-only", "commit");
            assertThat(published).containsExactly(new IdempotencyEvents.Replayed(OPERATION, AUTHOR, KEY, true));
        }

        @Test
        @DisplayName("a winner with another request makes the loser a conflict")
        void winnerWithOtherRequest() {
            CommentTable lost = lostRace(new NewComment(EVENT, "Goodbye"));

            assertThatThrownBy(() -> creator.create(AUTHOR, HELLO, key(KEY), lost))
                    .isInstanceOf(IdempotencyKeyConflictException.class);
            assertThat(published).containsExactly(new IdempotencyEvents.Conflicted(OPERATION, AUTHOR, KEY, true));
        }

        @Test
        @DisplayName("on real threads, two requests that both miss the lookup leave one row and two answers")
        void concurrentRequests() throws Exception {
            CyclicBarrier bothLookedUp = new CyclicBarrier(2);
            table.beforeInsert = number -> await(bothLookedUp);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CompletableFuture<CreationOutcome<Comment>> first = CompletableFuture.supplyAsync(
                        () -> creator.create(AUTHOR, HELLO, key(KEY), table), pool);
                CompletableFuture<CreationOutcome<Comment>> second = CompletableFuture.supplyAsync(
                        () -> creator.create(AUTHOR, HELLO, key(KEY), table), pool);

                List<CreationOutcome<Comment>> outcomes = List.of(first.get(10, TimeUnit.SECONDS),
                        second.get(10, TimeUnit.SECONDS));

                assertThat(table.rows()).hasSize(1);
                assertThat(table.inserts()).isEqualTo(2);
                assertThat(outcomes).extracting(CreationOutcome::resource).containsOnly(table.rows().get(0));
                assertThat(outcomes).extracting(CreationOutcome::replayed).containsExactlyInAnyOrder(false, true);
                assertThat(published).containsExactly(new IdempotencyEvents.Replayed(OPERATION, AUTHOR, KEY, true));
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("a refusal by another unique index, with no row under the key, stands")
        void otherUniqueIndex() {
            DataIntegrityViolationException otherIndex = DataIntegrityTestData.uniqueViolation();
            CommentTable refusing = new CommentTable() {
                @Override
                public Comment insert(String author, NewComment command, IdempotencyStamp stamp) {
                    throw otherIndex;
                }
            };

            assertThatThrownBy(() -> creator.create(AUTHOR, HELLO, key(KEY), refusing)).isSameAs(otherIndex);
            assertThat(refusing.finds()).isEqualTo(2);
            assertThat(published).isEmpty();
        }

        @Test
        @DisplayName("an integrity violation that is not a unique one is never read as a race")
        void otherViolation() {
            DataIntegrityViolationException foreignKey = DataIntegrityTestData.foreignKeyViolation();
            CommentTable refusing = new CommentTable() {
                @Override
                public Comment insert(String author, NewComment command, IdempotencyStamp stamp) {
                    throw foreignKey;
                }
            };

            assertThatThrownBy(() -> creator.create(AUTHOR, HELLO, key(KEY), refusing)).isSameAs(foreignKey);
            assertThat(refusing.finds()).isEqualTo(1);
        }

        private void await(CyclicBarrier barrier) {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Nested
    @DisplayName("a unique violation is recognised")
    class UniqueViolation {

        @Test
        @DisplayName("as JDBC raises it")
        void duplicateKey() {
            assertThat(IdempotentCreator.isUniqueViolation(new DuplicateKeyException("duplicate"))).isTrue();
        }

        @Test
        @DisplayName("as JPA raises it, the driver's SQLState at the root")
        void sqlState() {
            assertThat(IdempotentCreator.isUniqueViolation(DataIntegrityTestData.uniqueViolation())).isTrue();
        }

        @Test
        @DisplayName("in a batch, where the driver puts the real error behind the batch one")
        void batch() {
            BatchUpdateException batch = new BatchUpdateException("Batch entry 0 was aborted", "40000", new int[0]);
            batch.setNextException(new SQLException("duplicate key value", "23505"));

            assertThat(IdempotentCreator.isUniqueViolation(new DataIntegrityViolationException("x", batch))).isTrue();
        }

        @Test
        @DisplayName("and nothing else, a cycle in the causes included")
        void nothingElse() {
            SQLException first = new SQLException("deadlock", "40P01");
            SQLException second = new SQLException("deadlock again", "40P01", first);
            first.initCause(second);

            assertThat(IdempotentCreator.isUniqueViolation(first)).isFalse();
            assertThat(IdempotentCreator.isUniqueViolation(DataIntegrityTestData.notNullViolation())).isFalse();
            assertThat(IdempotentCreator.isUniqueViolation(new IllegalStateException())).isFalse();
        }
    }

    @Nested
    @DisplayName("misuse fails at once")
    class Misuse {

        @Test
        @DisplayName("inside a transaction, which a lost race would abort before the winner could be read")
        void insideTransaction() {
            TransactionSynchronizationManager.setActualTransactionActive(true);

            assertThatThrownBy(() -> creator.create(AUTHOR, HELLO, key(KEY), table))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThat(table.finds()).isZero();
            assertThat(table.inserts()).isZero();
        }

        @Test
        @DisplayName("without an author, a key holder or a table")
        void missingArguments() {
            assertThatThrownBy(() -> creator.create(null, HELLO, key(KEY), table))
                    .isInstanceOf(NullPointerException.class).hasMessage("author");
            assertThatThrownBy(() -> creator.create(AUTHOR, HELLO, null, table))
                    .isInstanceOf(NullPointerException.class).hasMessage("key");
            assertThatThrownBy(() -> creator.<String, NewComment, Comment>create(AUTHOR, HELLO, key(KEY), null))
                    .isInstanceOf(NullPointerException.class).hasMessage("creation");
        }

        @Test
        @DisplayName("with a blank fingerprint, which would let any request replay the first one")
        void blankFingerprint() {
            CommentTable careless = new CommentTable() {
                @Override
                public String fingerprint(NewComment command) {
                    return " ";
                }
            };

            assertThatThrownBy(() -> creator.create(AUTHOR, HELLO, key(KEY), careless))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(careless.inserts()).isZero();
        }
    }
}
