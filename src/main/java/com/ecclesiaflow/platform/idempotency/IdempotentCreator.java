package com.ecclesiaflow.platform.idempotency;

import com.ecclesiaflow.platform.idempotency.events.IdempotencyEvents;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Runs a creation at most once per author and {@code Idempotency-Key}. A retry with the same request gets the
 * row back, marked replayed; the same key with another request is refused. Two requests in flight together
 * are settled by the module's unique index on (author, key): the database holds the second insert until the
 * first commits, refuses it, and the second request answers with the first one's row. Nothing is reserved
 * beforehand, so no request ever waits on, or is refused because of, another one still running.
 *
 * <p>Call it outside any transaction: it opens its own, one to look up and insert, and after a lost race a
 * read-only one to read the winner, which the aborted transaction can no longer see.</p>
 */
public class IdempotentCreator {

    private static final String UNIQUE_VIOLATION = "23505";

    // The seen set stops a cycle; this stops a batch error chaining one next exception per row.
    private static final int MAX_LINKS = 32;

    private final TransactionOperations writes;
    private final TransactionOperations reads;
    private final ApplicationEventPublisher events;

    public IdempotentCreator(PlatformTransactionManager transactionManager, ApplicationEventPublisher events) {
        this.writes = new TransactionTemplate(transactionManager);
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        this.reads = readOnly;
        this.events = events;
    }

    /**
     * @param author   the creation's author, as the unique index stores it
     * @param command  the request, path parameters included
     * @param key      the request's key, empty when it carries none or its operation does not run the
     *                 primitive: the creation then runs once per call, as without the primitive
     * @param creation the module's table
     * @return the created resource, or the one an earlier request created under the same key
     * @throws IdempotencyKeyConflictException     when the author already used the key for another request
     * @throws IllegalTransactionStateException    when called inside a transaction
     */
    public <A, C, R> CreationOutcome<R> create(A author, C command, Optional<IdempotencyKey> key,
                                               IdempotentCreation<A, C, R> creation) {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(creation, "creation");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("An idempotent creation must run outside any transaction: "
                    + "a lost race aborts the transaction it ran in, and only a new one can read the winner");
        }
        if (key.isEmpty()) {
            return CreationOutcome.created(writes.execute(status ->
                    creation.insert(author, command, IdempotencyStamp.NONE)));
        }
        IdempotencyKey requested = key.get();
        IdempotencyStamp stamp = new IdempotencyStamp(requested.value(), creation.fingerprint(command));
        Attempt<R> attempt;
        try {
            attempt = writes.execute(status -> creation.find(author, requested.value())
                    .map(Attempt::<R>found)
                    .orElseGet(() -> Attempt.inserted(creation.insert(author, command, stamp))));
        } catch (RuntimeException e) {
            if (!isUniqueViolation(e)) {
                throw e;
            }
            // Another unique index may have refused the row; then there is no winner and the error stands.
            IdempotentRecord<R> winner = reads.execute(status -> creation.find(author, requested.value()))
                    .orElseThrow(() -> e);
            return replay(requested, author, stamp, winner, true);
        }
        return attempt.existing() != null
                ? replay(requested, author, stamp, attempt.existing(), false)
                : CreationOutcome.created(attempt.created());
    }

    private <R> CreationOutcome<R> replay(IdempotencyKey key, Object author, IdempotencyStamp stamp,
                                          IdempotentRecord<R> stored, boolean raced) {
        if (!stored.fingerprint().equals(stamp.fingerprint())) {
            events.publishEvent(new IdempotencyEvents.Conflicted(key.operation(), author, key.value(), raced));
            throw new IdempotencyKeyConflictException(key.operation());
        }
        events.publishEvent(new IdempotencyEvents.Replayed(key.operation(), author, key.value(), raced));
        return CreationOutcome.replay(stored.resource());
    }

    // JDBC raises DuplicateKeyException; JPA a DataIntegrityViolationException over the driver's SQLException,
    // possibly a batch one carrying the real error as its next exception.
    static boolean isUniqueViolation(Throwable error) {
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.push(error);
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!pending.isEmpty() && seen.size() < MAX_LINKS) {
            Throwable link = pending.pop();
            if (!seen.add(link)) {
                continue;
            }
            if (link instanceof DuplicateKeyException) {
                return true;
            }
            if (link instanceof SQLException sql) {
                if (UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                    return true;
                }
                if (sql.getNextException() != null) {
                    pending.push(sql.getNextException());
                }
            }
            if (link.getCause() != null) {
                pending.push(link.getCause());
            }
        }
        return false;
    }

    private record Attempt<R>(IdempotentRecord<R> existing, R created) {

        static <R> Attempt<R> found(IdempotentRecord<R> existing) {
            return new Attempt<>(existing, null);
        }

        static <R> Attempt<R> inserted(R created) {
            return new Attempt<>(null, created);
        }
    }
}
