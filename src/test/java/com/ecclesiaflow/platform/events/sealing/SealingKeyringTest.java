package com.ecclesiaflow.platform.events.sealing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SealingKeyring")
class SealingKeyringTest {

    private static byte[] key(int length, int fill) {
        byte[] key = new byte[length];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    @Test
    @DisplayName("holds the current key and the retired ones by id")
    void holdsKeysById() {
        SealingKeyring keyring = new SealingKeyring("k2", Map.of("k1", key(32, 1), "k2", key(32, 2)));

        assertThat(keyring.currentKeyId()).isEqualTo("k2");
        assertThat(keyring.find("k1")).isPresent();
        assertThat(keyring.find("k2")).isPresent();
        assertThat(keyring.find("k3")).isEmpty();
        assertThat(keyring.find(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 16, 24, 31, 33, 64})
    @DisplayName("refuses a key that is not 32 bytes, naming its id but not its bytes")
    void refusesWrongLength(int length) {
        assertThatThrownBy(() -> new SealingKeyring("k1", Map.of("k1", key(length, 0x41))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k1")
                .hasMessageContaining("32")
                .hasMessageNotContaining("AAAA");
    }

    @Test
    @DisplayName("refuses a current id it holds no key for")
    void refusesMissingCurrentKey() {
        assertThatThrownBy(() -> new SealingKeyring("k2", Map.of("k1", key(32, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k2");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("refuses a blank current id")
    void refusesBlankCurrentId(String keyId) {
        assertThatThrownBy(() -> new SealingKeyring(keyId, Map.of("k1", key(32, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses a blank id among the keys")
    void refusesBlankKeyId() {
        assertThatThrownBy(() -> new SealingKeyring("k1", Map.of("k1", key(32, 1), " ", key(32, 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses a null key id or key")
    void refusesNullEntries() {
        Map<String, byte[]> nullId = new HashMap<>(Map.of("k1", key(32, 1)));
        nullId.put(null, key(32, 2));
        Map<String, byte[]> nullKey = new HashMap<>(Map.of("k1", key(32, 1)));
        nullKey.put("k2", null);

        assertThatThrownBy(() -> new SealingKeyring("k1", nullId)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SealingKeyring("k1", nullKey)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SealingKeyring("k1", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("keeps its own copy of the key bytes")
    void copiesTheKeys() {
        byte[] material = key(32, 1);
        SealingKeyring keyring = new SealingKeyring("k1", Map.of("k1", material));
        byte[] sealed = new EventFieldSealer(keyring).seal("value", "event");

        Arrays.fill(material, (byte) 0);

        assertThat(new EventFieldSealer(keyring).open("k1", sealed, "event")).isEqualTo("value");
    }

    @Test
    @DisplayName("never prints its keys")
    void toStringHidesKeys() {
        assertThat(new SealingKeyring("k1", Map.of("k1", key(32, 0x41))).toString())
                .contains("k1")
                .doesNotContain("AAAA")
                .doesNotContain("QUFB");
    }
}
