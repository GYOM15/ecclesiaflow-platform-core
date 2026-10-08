package com.ecclesiaflow.platform.events.sealing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SealingKeyProperties")
class SealingKeyPropertiesTest {

    private static String base64Key(int length, int fill) {
        byte[] key = new byte[length];
        Arrays.fill(key, (byte) fill);
        return Base64.getEncoder().encodeToString(key);
    }

    private static SealingKeyProperties configured(String keyId, String key) {
        SealingKeyProperties props = new SealingKeyProperties();
        props.setKeyId(keyId);
        props.setKey(key);
        return props;
    }

    @Test
    @DisplayName("is unconfigured by default, with no key to fall back on")
    void unconfiguredByDefault() {
        SealingKeyProperties props = new SealingKeyProperties();

        assertThat(props.getKeyId()).isEmpty();
        assertThat(props.getKey()).isEmpty();
        assertThat(props.getRetiredKeys()).isEmpty();
        assertThat(props.isConfigured()).isFalse();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"null, null", "'', key", "k1, ''", "'  ', key", "k1, '  '", "null, key", "k1, null"})
    @DisplayName("is unconfigured while the key or its id is blank")
    void configuredNeedsBoth(String keyId, String key) {
        assertThat(configured(keyId, key).isConfigured()).isFalse();
    }

    @Test
    @DisplayName("is configured with both a key and its id")
    void configuredWithBoth() {
        assertThat(configured("k1", "key").isConfigured()).isTrue();
    }

    @Test
    @DisplayName("refuses to build a keyring while unconfigured")
    void refusesUnconfigured() {
        assertThatThrownBy(() -> new SealingKeyProperties().toKeyring())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("binds the current key and the retired ones from the module's prefix")
    void bindsFromProperties() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "ecclesiaflow.setup-token.sealing.key-id", "2027-01",
                "ecclesiaflow.setup-token.sealing.key", base64Key(32, 8),
                "ecclesiaflow.setup-token.sealing.retired-keys.2026-10", base64Key(32, 7))));

        SealingKeyProperties props = binder.bind("ecclesiaflow.setup-token.sealing", SealingKeyProperties.class).get();
        SealingKeyring keyring = props.toKeyring();

        assertThat(keyring.currentKeyId()).isEqualTo("2027-01");
        byte[] sealedByRetired = new EventFieldSealer(configured("2026-10", base64Key(32, 7)).toKeyring())
                .seal("token", "event");
        assertThat(new EventFieldSealer(keyring).open("2026-10", sealedByRetired, "event")).isEqualTo("token");
    }

    @Test
    @DisplayName("reads a key with the line break an environment file leaves around it")
    void trimsTheKey() {
        SealingKeyring keyring = configured("k1", " " + base64Key(32, 1) + "\n").toKeyring();

        assertThat(keyring.find("k1")).isPresent();
    }

    @Test
    @DisplayName("refuses a key that is not Base64, naming its id but not its value")
    void refusesInvalidBase64() {
        assertThatThrownBy(() -> configured("k1", "not*base64*secret").toKeyring())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k1")
                .hasMessageNotContaining("secret")
                .hasNoCause();
    }

    @Test
    @DisplayName("refuses a key that does not decode to 32 bytes")
    void refusesWrongLength() {
        assertThatThrownBy(() -> configured("k1", base64Key(16, 1)).toKeyring())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("refuses a retired key under the current key's id")
    void refusesRetiredKeyUnderCurrentId() {
        SealingKeyProperties props = configured("k1", base64Key(32, 1));
        props.setRetiredKeys(Map.of("k1", base64Key(32, 2)));

        assertThatThrownBy(props::toKeyring)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k1");
    }

    @Test
    @DisplayName("refuses a retired id left without its key")
    void refusesRetiredIdWithoutKey() {
        SealingKeyProperties props = configured("k1", base64Key(32, 1));
        Map<String, String> retired = new HashMap<>();
        retired.put("k0", null);
        props.setRetiredKeys(retired);

        assertThatThrownBy(props::toKeyring)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k0");
    }

    @Test
    @DisplayName("treats a missing retired-key map as empty")
    void nullRetiredKeys() {
        SealingKeyProperties props = configured("k1", base64Key(32, 1));
        props.setRetiredKeys(null);

        assertThat(props.getRetiredKeys()).isEmpty();
        assertThat(props.toKeyring().find("k1")).isPresent();
    }

    @Test
    @DisplayName("never prints its keys")
    void toStringHidesKeys() {
        SealingKeyProperties props = configured("k1", base64Key(32, 0x41));
        props.setRetiredKeys(Map.of("k0", base64Key(32, 0x42)));

        assertThat(props.toString()).contains("k1").contains("k0").doesNotContain("QUFB").doesNotContain("QkJC");
    }
}
