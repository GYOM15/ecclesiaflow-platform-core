package com.ecclesiaflow.platform.journal;

import com.ecclesiaflow.platform.journal.events.JournalEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JournalRetentionTest {

    @Mock
    private JournalRepository repository;
    @Mock
    private ApplicationEventPublisher events;

    @Test
    @DisplayName("purges, then publishes how many lines left and where the chain now starts")
    void purges() {
        when(repository.purgeExpired()).thenReturn(12L);
        when(repository.head()).thenReturn(new JournalHead(40, "aa".repeat(32), 12, "bb".repeat(32)));

        long removed = new JournalRetention(repository, events, "church").purgeExpired();

        assertThat(removed).isEqualTo(12);
        verify(events).publishEvent(new JournalEvents.Purged("church", 12, 12));
    }
}
