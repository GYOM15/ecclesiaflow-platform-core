package com.ecclesiaflow.platform.events.outbox.relay;

import com.ecclesiaflow.platform.events.outbox.OutboxMessage;
import com.ecclesiaflow.platform.events.outbox.amqp.AmqpOutboxMessageMapper;
import com.ecclesiaflow.platform.events.outbox.events.OutboxRelayEvents;
import org.springframework.amqp.AmqpApplicationContextClosedException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes through the module's template so signing runs at relay time, as for a direct publish;
 * a signature stamped at write time would be stale on retry. An unreachable broker costs no attempt.
 */
public class OutboxRelay {

    static final String CORRELATION_PREFIX = "outbox-";

    private final OutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final AmqpOutboxMessageMapper mapper;
    private final OutboxRetryPolicy retryPolicy;
    private final Settings settings;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** The lease must outlast the confirm wait, or a second relay could publish the rows again meanwhile. */
    public record Settings(int batchSize, Duration confirmTimeout, Duration lease) {

        public Settings {
            if (batchSize < 1) {
                throw new IllegalArgumentException("ecclesiaflow.events.outbox.batch-size must be at least 1");
            }
            if (confirmTimeout == null || confirmTimeout.isNegative() || confirmTimeout.isZero()) {
                throw new IllegalArgumentException("ecclesiaflow.events.outbox.confirm-timeout must be positive");
            }
            if (lease == null || lease.compareTo(confirmTimeout) <= 0) {
                throw new IllegalArgumentException(
                        "ecclesiaflow.events.outbox.lease must be longer than the confirm timeout");
            }
        }
    }

    public OutboxRelay(OutboxRepository repository, RabbitTemplate rabbitTemplate, AmqpOutboxMessageMapper mapper,
                       OutboxRetryPolicy retryPolicy, Settings settings, ApplicationEventPublisher events,
                       Clock clock) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.mapper = mapper;
        this.retryPolicy = retryPolicy;
        this.settings = settings;
        this.events = events;
        this.clock = clock;
    }

    public RelayBatchResult relayDue() {
        // Millisecond precision so the lease written by the claim compares equal when it fences later writes.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant leaseUntil = now.plus(settings.lease());
        List<ClaimedOutboxMessage> claimed = repository.claimDue(now, leaseUntil, settings.batchSize());
        if (claimed.isEmpty()) {
            return RelayBatchResult.EMPTY;
        }
        Batch batch = new Batch(leaseUntil);
        List<InFlight> inFlight = send(claimed, batch);
        awaitConfirms(inFlight, batch);
        try {
            return record(batch, now, claimed.size());
        } finally {
            if (batch.interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private List<InFlight> send(List<ClaimedOutboxMessage> claimed, Batch batch) {
        List<InFlight> inFlight = new ArrayList<>();
        for (ClaimedOutboxMessage row : claimed) {
            if (batch.deferReason != null) {
                batch.deferred.add(row);
                continue;
            }
            OutboxMessage message = row.message();
            CorrelationData correlation = new CorrelationData(CORRELATION_PREFIX + row.id());
            try {
                rabbitTemplate.send(message.exchange(), message.routingKey(), mapper.toAmqpMessage(message), correlation);
                inFlight.add(new InFlight(row, correlation));
            } catch (AmqpConnectException | AmqpApplicationContextClosedException e) {
                batch.deferReason = describe(e);
                batch.deferDelay = retryPolicy.initialBackoff();
                batch.deferred.add(row);
            } catch (RuntimeException e) {
                batch.failed.add(new Failure(row, describe(e)));
            }
        }
        return inFlight;
    }

    private void awaitConfirms(List<InFlight> inFlight, Batch batch) {
        long deadline = System.nanoTime() + settings.confirmTimeout().toNanos();
        for (InFlight flight : inFlight) {
            if (batch.interrupted) {
                batch.deferred.add(flight.row());
                continue;
            }
            try {
                CorrelationData.Confirm confirm = flight.correlation().getFuture()
                        .get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                String failure = deliveryFailure(confirm, flight.correlation().getReturned());
                if (failure == null) {
                    batch.sent.add(flight.row());
                } else {
                    batch.failed.add(new Failure(flight.row(), failure));
                }
            } catch (TimeoutException e) {
                batch.failed.add(new Failure(flight.row(), "no broker confirm within " + settings.confirmTimeout()));
            } catch (ExecutionException e) {
                batch.failed.add(new Failure(flight.row(), describe(e.getCause())));
            } catch (InterruptedException e) {
                // The flag is restored once the outcomes are written: an interrupted thread
                // cannot borrow a pooled connection to write them.
                batch.interrupted = true;
                batch.deferReason = "relay interrupted before the broker confirmed";
                batch.deferDelay = Duration.ZERO;
                batch.deferred.add(flight.row());
            }
        }
    }

    private RelayBatchResult record(Batch batch, Instant now, int claimedCount) {
        List<Object> outcomes = new ArrayList<>();
        if (!batch.sent.isEmpty()) {
            repository.markSent(batch.sent.stream().map(ClaimedOutboxMessage::id).toList(), now);
            batch.sent.forEach(row -> outcomes.add(new OutboxRelayEvents.MessageRelayed(row.id(),
                    row.message().exchange(), row.message().routingKey(), row.attempts() + 1)));
        }
        int retried = 0;
        int parked = 0;
        for (Failure failure : batch.failed) {
            ClaimedOutboxMessage row = failure.row();
            int attempts = row.attempts() + 1;
            if (retryPolicy.isExhausted(attempts)) {
                repository.park(row.id(), batch.leaseUntil, attempts, now, failure.reason());
                outcomes.add(new OutboxRelayEvents.MessageParked(row.id(), row.message().exchange(),
                        row.message().routingKey(), attempts, failure.reason()));
                parked++;
            } else {
                Instant nextAttemptAt = now.plus(retryPolicy.backoffAfter(attempts));
                repository.reschedule(row.id(), batch.leaseUntil, attempts, nextAttemptAt, failure.reason());
                outcomes.add(new OutboxRelayEvents.RetryScheduled(row.id(), row.message().exchange(),
                        row.message().routingKey(), attempts, retryPolicy.maxAttempts(), nextAttemptAt,
                        failure.reason()));
                retried++;
            }
        }
        if (!batch.deferred.isEmpty()) {
            Instant nextAttemptAt = now.plus(batch.deferDelay);
            for (ClaimedOutboxMessage row : batch.deferred) {
                repository.reschedule(row.id(), batch.leaseUntil, row.attempts(), nextAttemptAt, batch.deferReason);
            }
            outcomes.add(new OutboxRelayEvents.MessagesDeferred(batch.deferred.size(), nextAttemptAt,
                    batch.deferReason));
        }
        // Listeners run only once every outcome is stored, so a failing listener cannot leave a row unrecorded.
        outcomes.forEach(events::publishEvent);
        return new RelayBatchResult(claimedCount, batch.sent.size(), retried, parked, batch.deferred.size(),
                claimedCount == settings.batchSize());
    }

    private static String deliveryFailure(CorrelationData.Confirm confirm, ReturnedMessage returned) {
        if (!confirm.isAck()) {
            return confirm.getReason() == null ? "broker nack" : "broker nack: " + confirm.getReason();
        }
        if (returned != null) {
            return "unroutable, no queue bound: " + returned.getReplyCode() + " " + returned.getReplyText();
        }
        return null;
    }

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    private record InFlight(ClaimedOutboxMessage row, CorrelationData correlation) {
    }

    private record Failure(ClaimedOutboxMessage row, String reason) {
    }

    private static final class Batch {
        final Instant leaseUntil;
        final List<ClaimedOutboxMessage> sent = new ArrayList<>();
        final List<Failure> failed = new ArrayList<>();
        final List<ClaimedOutboxMessage> deferred = new ArrayList<>();
        String deferReason;
        Duration deferDelay;
        boolean interrupted;

        Batch(Instant leaseUntil) {
            this.leaseUntil = leaseUntil;
        }
    }
}
