package com.ecclesiaflow.platform.journal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalLineTest {

    @Test
    @DisplayName("keeps what was read, a null role included, in copies the reader cannot change")
    void keptAsRead() {
        List<String> roles = new ArrayList<>(Arrays.asList("SUPPORT", null));
        Map<String, String> details = new HashMap<>(Map.of("channel", "KNOWN_PHONE"));
        JournalLine line = new JournalLine(1, Instant.EPOCH, "sub", roles, null, null, "G", "DONE", null, "K", null,
                null, null, details, null, "ab");

        roles.clear();
        details.clear();

        assertThat(line.actorRoles()).containsExactly("SUPPORT", null);
        assertThat(line.details()).containsEntry("channel", "KNOWN_PHONE");
        assertThatThrownBy(() -> line.actorRoles().add("X")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("absent roles and details read as empty")
    void absent() {
        JournalLine line = new JournalLine(1, Instant.EPOCH, "sub", null, null, null, "G", "DONE", null, "K", null,
                null, null, null, null, "ab");

        assertThat(line.actorRoles()).isEmpty();
        assertThat(line.details()).isEmpty();
    }
}
