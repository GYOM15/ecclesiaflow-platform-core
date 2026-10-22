package com.ecclesiaflow.platform.events.dedup;

/**
 * The domain events a consumer has already applied, by event type and {@code event_id}: the module's
 * {@code processed_domain_events} table behind its own adapter.
 */
public interface ProcessedDomainEventStore {

    boolean contains(String eventType, String eventId);

    /** Insert-if-absent: a concurrent delivery that recorded the event first must not make this one fail. */
    void record(String eventType, String eventId);
}
