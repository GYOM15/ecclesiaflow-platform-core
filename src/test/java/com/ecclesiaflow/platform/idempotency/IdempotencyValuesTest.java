package com.ecclesiaflow.platform.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The values the primitive passes between the web layer, the creator and the module's table. */
class IdempotencyValuesTest {

    private static final UUID KEY = UUID.fromString("5f0e9d8c-7b6a-4e3d-9c2b-1a0f9e8d7c6b");

    @Test
    @DisplayName("a stamp holds a key with its fingerprint, or neither")
    void stamp() {
        assertThat(IdempotencyStamp.NONE.key()).isNull();
        assertThat(IdempotencyStamp.NONE.fingerprint()).isNull();
        assertThat(new IdempotencyStamp(KEY, "f").key()).isEqualTo(KEY);

        assertThatThrownBy(() -> new IdempotencyStamp(KEY, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyStamp(null, "f")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyStamp(KEY, "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a key names its operation, a record its fingerprint, an outcome its resource")
    void requiredParts() {
        assertThatThrownBy(() -> new IdempotencyKey(null, "Op")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IdempotencyKey(KEY, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IdempotentRecord<>("row", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IdempotentRecord<>(null, "f")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CreationOutcome.created(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("an outcome tells a creation from a replay")
    void outcome() {
        assertThat(CreationOutcome.created("row").replayed()).isFalse();
        assertThat(CreationOutcome.replay("row").replayed()).isTrue();
        assertThat(CreationOutcome.replay("row").resource()).isEqualTo("row");
    }
}
