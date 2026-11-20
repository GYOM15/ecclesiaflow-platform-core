package com.ecclesiaflow.platform.webhooks.processing;

import com.ecclesiaflow.platform.webhooks.RecordedNotification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InboxRepository {

    /**
     * Claims up to {@code limit} pending notifications due at {@code now}, skipping those another processor holds,
     * and keeps them out of reach until {@code leaseUntil}.
     */
    List<RecordedNotification> claimDue(Instant now, Instant leaseUntil, int limit);

    /** Closes a claimed notification as handled, if {@code leaseUntil} is still its lease. */
    void markDone(long id, Instant leaseUntil, String outcome, Instant doneAt);

    /** Makes a claimed notification due again, if {@code leaseUntil} is still its lease. */
    void reschedule(long id, Instant leaseUntil, int attempts, Instant nextAttemptAt, String lastError);

    /** Sets a claimed notification aside for an operator, if {@code leaseUntil} is still its lease. */
    void park(long id, Instant leaseUntil, int attempts, Instant parkedAt, String lastError);

    long countPending();

    long countParked();

    long countUnmatched();

    Optional<Instant> oldestPendingReceivedAt();
}
