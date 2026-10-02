package com.ecclesiaflow.platform.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredObjectRefTest {

    @Test
    @DisplayName("a reference that names no object is refused before it reaches the database")
    void refusesABlankKey() {
        assertThatThrownBy(() -> new StoredObjectRef("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
        assertThatThrownBy(() -> new StoredObjectRef(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void carriesTheKey() {
        assertThat(new StoredObjectRef("member-photos/a.png").key()).isEqualTo("member-photos/a.png");
    }
}
