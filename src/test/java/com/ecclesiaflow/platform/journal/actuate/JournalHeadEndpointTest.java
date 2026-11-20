package com.ecclesiaflow.platform.journal.actuate;

import com.ecclesiaflow.platform.journal.JournalHead;
import com.ecclesiaflow.platform.journal.JournalIntegrity;
import com.ecclesiaflow.platform.journal.JournalPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JournalHeadEndpointTest {

    @Mock
    private JournalIntegrity integrity;

    @Test
    @DisplayName("serves the head with the module's name, positions and hashes only")
    void head() {
        when(integrity.head()).thenReturn(new JournalHead(9, "99".repeat(32), 4, "44".repeat(32)));

        assertThat(new JournalHeadEndpoint(integrity, "chat").head()).isEqualTo(
                new JournalHeadEndpoint.HeadDescriptor("chat", 9, "99".repeat(32), 4, "44".repeat(32)));
    }

    @Test
    @DisplayName("serves the hash at the position an anchor named, with its state")
    void position() {
        when(integrity.position(7)).thenReturn(new JournalPosition(7, JournalPosition.State.LINE, "77".repeat(32)));

        assertThat(new JournalHeadEndpoint(integrity, "chat").position(7)).isEqualTo(
                new JournalHeadEndpoint.PositionDescriptor("chat", 7, "LINE", "77".repeat(32)));
    }
}
