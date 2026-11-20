package com.ecclesiaflow.platform.journal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalVerdictTest {

    @Test
    @DisplayName("an intact chain names no position; a broken one names where it breaks")
    void positions() {
        assertThat(JournalVerdict.intact(4).intact()).isTrue();
        assertThat(JournalVerdict.broken(JournalVerdict.Status.TAIL_REMOVED, 3, 4).intact()).isFalse();
        assertThatThrownBy(() -> new JournalVerdict(JournalVerdict.Status.INTACT, 4, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JournalVerdict.broken(JournalVerdict.Status.LINK_BROKEN, 4, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
