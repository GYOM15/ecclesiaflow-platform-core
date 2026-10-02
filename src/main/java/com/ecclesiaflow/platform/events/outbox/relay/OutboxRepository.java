package com.ecclesiaflow.platform.events.outbox.relay;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Storage the relay claims due messages from and records their outcomes in. */
public interface OutboxRepository {

    /**
     * Claims up to {@code limit} pending rows due at {@code now}, skipping rows another relay
     * holds and keyed rows behind an older unsent row of their key, and keeps them out of reach
     * until {@code leaseUntil}.
     */
    List<ClaimedOutboxMessage> claimDue(Instant now, Instant leaseUntil, int limit);

    /** Marks rows the broker confirmed and routed. */
    void markSent(Collection<Long> ids, Instant sentAt);

    /** Makes a claimed row due again, if {@code leaseUntil} is still its lease. */
    void reschedule(long id, Instant leaseUntil, int attempts, Instant nextAttemptAt, String lastError);

    /** Sets a claimed row aside for manual replay, if {@code leaseUntil} is still its lease. */
    void park(long id, Instant leaseUntil, int attempts, Instant parkedAt, String lastError);

    /** Deletes up to {@code limit} relayed rows sent before {@code sentBefore}; returns how many. */
    int purgeSent(Instant sentBefore, int limit);

    long countPending();

    long countParked();

    Optional<Instant> oldestPendingCreatedAt();
}
