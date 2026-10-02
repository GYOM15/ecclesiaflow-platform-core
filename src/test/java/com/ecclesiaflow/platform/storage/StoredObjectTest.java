package com.ecclesiaflow.platform.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredObjectTest {

    @Test
    @DisplayName("an object without a content type cannot be served, so it cannot be built")
    void refusesABlankContentType() {
        assertThatThrownBy(() -> new StoredObject(new byte[]{1}, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contentType");
        assertThatThrownBy(() -> new StoredObject(new byte[]{1}, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StoredObject(null, "image/png"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void carriesTheBytesAndType() {
        StoredObject object = new StoredObject(new byte[]{1, 2}, "image/png");

        assertThat(object.data()).containsExactly(1, 2);
        assertThat(object.contentType()).isEqualTo("image/png");
    }
}
