package com.ecclesiaflow.platform.idempotency;

import com.ecclesiaflow.platform.error.DataIntegrityTestData;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * A module's comment table as the primitive sees it, with a unique index on (author, key) that refuses a
 * second row the way PostgreSQL does through JPA. {@link #beforeInsert} lets a test hold inserts to line up
 * a race.
 */
public class CommentTable implements IdempotentCreation<String, CommentTable.NewComment, CommentTable.Comment> {

    public record NewComment(UUID eventId, String body) {
    }

    public record Comment(UUID id, String author, UUID eventId, String body, UUID key, String fingerprint) {
    }

    private record AuthorKey(String author, UUID key) {
    }

    private final List<Comment> rows = new CopyOnWriteArrayList<>();
    private final Map<AuthorKey, Comment> uniqueIndex = new ConcurrentHashMap<>();
    private final AtomicInteger finds = new AtomicInteger();
    private final AtomicInteger inserts = new AtomicInteger();

    /** Runs at the start of every insert, with the insert's number, from 1. */
    public volatile IntConsumer beforeInsert = number -> { };

    public List<Comment> rows() {
        return rows;
    }

    public int finds() {
        return finds.get();
    }

    public int inserts() {
        return inserts.get();
    }

    /** Stores a row as an earlier request under this key would have, fingerprint included. */
    public Comment seed(String author, NewComment command, UUID key) {
        return insert(author, command, new IdempotencyStamp(key, fingerprint(command)));
    }

    @Override
    public Optional<IdempotentRecord<Comment>> find(String author, UUID key) {
        finds.incrementAndGet();
        return Optional.ofNullable(uniqueIndex.get(new AuthorKey(author, key)))
                .map(row -> new IdempotentRecord<>(row, row.fingerprint()));
    }

    @Override
    public String fingerprint(NewComment command) {
        return IdempotencyFingerprint.of(command.eventId(), command.body());
    }

    @Override
    public Comment insert(String author, NewComment command, IdempotencyStamp stamp) {
        beforeInsert.accept(inserts.incrementAndGet());
        Comment row = new Comment(UUID.randomUUID(), author, command.eventId(), command.body(), stamp.key(),
                stamp.fingerprint());
        if (stamp.key() != null && uniqueIndex.putIfAbsent(new AuthorKey(author, stamp.key()), row) != null) {
            throw DataIntegrityTestData.uniqueViolation();
        }
        rows.add(row);
        return row;
    }
}
