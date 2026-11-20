package com.ecclesiaflow.platform.journal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalHeadTest {

    private static final String HASH = "ab".repeat(32);

    @Test
    @DisplayName("an empty journal has no position and no hash")
    void empty() {
        assertThat(JournalHead.empty()).isEqualTo(new JournalHead(0, null, 0, null));
    }

    @Test
    @DisplayName("a head carries each position with its hash, and starts at or before its end")
    void shape() {
        assertThat(new JournalHead(5, HASH, 2, HASH).startSeq()).isEqualTo(2);
        assertThatThrownBy(() -> new JournalHead(2, HASH, 3, HASH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalHead(-1, null, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalHead(0, null, -1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalHead(3, null, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalHead(0, HASH, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalHead(3, HASH, 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalHead(3, HASH, 0, HASH)).isInstanceOf(IllegalArgumentException.class);
    }
}
