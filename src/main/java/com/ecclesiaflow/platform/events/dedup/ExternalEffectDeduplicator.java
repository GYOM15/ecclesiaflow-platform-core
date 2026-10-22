package com.ecclesiaflow.platform.events.dedup;

/**
 * Applies a domain event's external effect (a Keycloak call, an email, a chat push) once per
 * {@code event_id}, recording the event only after the effect succeeded. An effect that fails leaves
 * no record, so a delayed retry applies it again; recording first would mark it done and drop it.
 *
 * <p>A crash between the effect and the record repeats the effect on the next delivery, and two
 * concurrent deliveries may both apply it: the effect must be idempotent at its target. A message
 * without {@code event_id} has nothing to deduplicate on; its effect runs on every delivery.</p>
 */
public class ExternalEffectDeduplicator {

    private final ProcessedDomainEventStore store;

    public ExternalEffectDeduplicator(ProcessedDomainEventStore store) {
        this.store = store;
    }

    /**
     * Returns {@code false} when the event was already applied and the effect did not run. Whatever the
     * effect throws propagates, with nothing recorded.
     */
    public boolean applyOnce(String eventType, String eventId, Runnable externalEffect) {
        if (eventType == null || eventType.isBlank() || externalEffect == null) {
            throw new IllegalArgumentException("An external effect is applied for a named event type");
        }
        boolean identified = eventId != null && !eventId.isBlank();
        if (identified && store.contains(eventType, eventId)) {
            return false;
        }
        externalEffect.run();
        if (identified) {
            store.record(eventType, eventId);
        }
        return true;
    }
}
