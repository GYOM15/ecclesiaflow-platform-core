package com.ecclesiaflow.platform.events.dedup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalEffectDeduplicatorTest {

    private static final String TYPE = "members.account.suspended.v1";
    private static final String EVENT_ID = "8d0f6a3e-5a7c-4f3e-9d61-6f0a1f3b2c11";

    @Mock
    private ProcessedDomainEventStore store;
    @Mock
    private Runnable keycloakDisable;

    @Test
    @DisplayName("Applies a new event's effect, then records it")
    void recordsAfterTheEffect() {
        boolean applied = new ExternalEffectDeduplicator(store).applyOnce(TYPE, EVENT_ID, keycloakDisable);

        assertThat(applied).isTrue();
        InOrder order = inOrder(store, keycloakDisable);
        order.verify(store).contains(TYPE, EVENT_ID);
        order.verify(keycloakDisable).run();
        order.verify(store).record(TYPE, EVENT_ID);
    }

    @Test
    @DisplayName("Skips the effect of an event already applied")
    void skipsAnAppliedEvent() {
        when(store.contains(TYPE, EVENT_ID)).thenReturn(true);

        boolean applied = new ExternalEffectDeduplicator(store).applyOnce(TYPE, EVENT_ID, keycloakDisable);

        assertThat(applied).isFalse();
        verify(keycloakDisable, never()).run();
        verify(store, never()).record(anyString(), anyString());
    }

    @Test
    @DisplayName("Records nothing when the effect fails, so the next delivery applies it again")
    void recordsNothingOnFailure() {
        IllegalStateException down = new IllegalStateException("Keycloak unreachable");
        Runnable failing = () -> {
            throw down;
        };

        assertThatThrownBy(() -> new ExternalEffectDeduplicator(store).applyOnce(TYPE, EVENT_ID, failing))
                .isSameAs(down);
        verify(store, never()).record(anyString(), anyString());
    }

    @Test
    @DisplayName("Runs the effect of a message without event_id on every delivery, recording nothing")
    void runsUnidentifiedEventsEachTime() {
        AtomicInteger runs = new AtomicInteger();
        ExternalEffectDeduplicator deduplicator = new ExternalEffectDeduplicator(store);

        deduplicator.applyOnce(TYPE, null, runs::incrementAndGet);
        deduplicator.applyOnce(TYPE, " ", runs::incrementAndGet);

        assertThat(runs).hasValue(2);
        verify(store, never()).contains(anyString(), anyString());
        verify(store, never()).record(anyString(), anyString());
    }

    @Test
    @DisplayName("Refuses an effect without an event type or without the effect")
    void refusesAnUnnamedEffect() {
        ExternalEffectDeduplicator deduplicator = new ExternalEffectDeduplicator(store);

        assertThatThrownBy(() -> deduplicator.applyOnce(null, EVENT_ID, keycloakDisable))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deduplicator.applyOnce(" ", EVENT_ID, keycloakDisable))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deduplicator.applyOnce(TYPE, EVENT_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
