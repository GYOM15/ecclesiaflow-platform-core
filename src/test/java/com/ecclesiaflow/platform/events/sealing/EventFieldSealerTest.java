package com.ecclesiaflow.platform.events.sealing;

import com.ecclesiaflow.platform.events.sealing.SealedFieldUnreadableException.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EventFieldSealer")
class EventFieldSealerTest {

    private static final String EVENT_ID = "5b0e3c1e-8f0a-4c55-9a4e-2f1f7f0c9d11";
    private static final String TOKEN = "setup link value used by this test only";

    private static byte[] key(int fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    private static EventFieldSealer sealer(String keyId, byte[] key) {
        return new EventFieldSealer(new SealingKeyring(keyId, Map.of(keyId, key)));
    }

    private final EventFieldSealer sealer = sealer("2026-10", key(7));

    @Nested
    @DisplayName("seal and open")
    class SealAndOpen {

        @Test
        @DisplayName("opens what it sealed under the same event id")
        void roundTrips() {
            byte[] sealed = sealer.seal(TOKEN, EVENT_ID);

            assertThat(sealer.open("2026-10", sealed, EVENT_ID)).isEqualTo(TOKEN);
        }

        @Test
        @DisplayName("names the key it seals with")
        void namesItsKey() {
            assertThat(sealer.keyId()).isEqualTo("2026-10");
        }

        @Test
        @DisplayName("draws a fresh nonce for every value")
        void freshNonceEachTime() {
            byte[] first = sealer.seal(TOKEN, EVENT_ID);
            byte[] second = sealer.seal(TOKEN, EVENT_ID);

            assertThat(first).isNotEqualTo(second);
            assertThat(Arrays.copyOf(first, 12)).isNotEqualTo(Arrays.copyOf(second, 12));
            assertThat(sealer.open("2026-10", second, EVENT_ID)).isEqualTo(TOKEN);
        }

        @Test
        @DisplayName("never carries the value in clear")
        void neverCarriesTheValue() {
            String sealed = new String(sealer.seal(TOKEN, EVENT_ID), StandardCharsets.ISO_8859_1);

            assertThat(sealed).doesNotContain(TOKEN).doesNotContain(TOKEN.substring(0, 8));
        }

        @Test
        @DisplayName("is the 12-byte nonce, then AES-256-GCM ciphertext and tag over the event id")
        void layoutIsNonceThenCiphertextAndTag() throws Exception {
            byte[] sealed = sealer.seal(TOKEN, EVENT_ID);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(7), "AES"),
                    new GCMParameterSpec(128, sealed, 0, 12));
            cipher.updateAAD(EVENT_ID.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(sealed, 12, sealed.length - 12);

            assertThat(sealed).hasSize(12 + TOKEN.length() + 16);
            assertThat(new String(plaintext, StandardCharsets.UTF_8)).isEqualTo(TOKEN);
        }

        @Test
        @DisplayName("seals an empty value")
        void sealsEmptyValue() {
            assertThat(sealer.open("2026-10", sealer.seal("", EVENT_ID), EVENT_ID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("refusals when opening")
    class Refusals {

        @Test
        @DisplayName("refuses a value moved to another event")
        void refusesTamperedAssociatedData() {
            byte[] sealed = sealer.seal(TOKEN, EVENT_ID);

            assertThatThrownBy(() -> sealer.open("2026-10", sealed, "c4f1d1b6-0000-4000-8000-000000000000"))
                    .isInstanceOf(SealedFieldUnreadableException.class)
                    .extracting("reason").isEqualTo(Reason.NOT_AUTHENTIC);
        }

        @Test
        @DisplayName("refuses an altered ciphertext")
        void refusesAlteredCiphertext() {
            byte[] sealed = sealer.seal(TOKEN, EVENT_ID);
            sealed[20] ^= 0x01;

            assertThatThrownBy(() -> sealer.open("2026-10", sealed, EVENT_ID))
                    .isInstanceOf(SealedFieldUnreadableException.class)
                    .extracting("reason").isEqualTo(Reason.NOT_AUTHENTIC);
        }

        @Test
        @DisplayName("refuses a value sealed by another key under the same id")
        void refusesOtherKeyMaterial() {
            byte[] sealed = sealer("2026-10", key(9)).seal(TOKEN, EVENT_ID);

            assertThatThrownBy(() -> sealer.open("2026-10", sealed, EVENT_ID))
                    .isInstanceOf(SealedFieldUnreadableException.class)
                    .extracting("reason").isEqualTo(Reason.NOT_AUTHENTIC);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"2025-01"})
        @DisplayName("refuses a key id it does not hold")
        void refusesUnknownKey(String keyId) {
            byte[] sealed = sealer.seal(TOKEN, EVENT_ID);

            assertThatThrownBy(() -> sealer.open(keyId, sealed, EVENT_ID))
                    .isInstanceOf(SealedFieldUnreadableException.class)
                    .extracting("reason").isEqualTo(Reason.UNKNOWN_KEY);
        }

        @Test
        @DisplayName("refuses bytes too short to hold a nonce and a tag")
        void refusesTruncatedValue() {
            byte[] sealed = Arrays.copyOf(sealer.seal(TOKEN, EVENT_ID), 27);

            assertThatThrownBy(() -> sealer.open("2026-10", sealed, EVENT_ID))
                    .isInstanceOf(SealedFieldUnreadableException.class)
                    .extracting("reason").isEqualTo(Reason.MALFORMED);
        }

        @Test
        @DisplayName("refuses a missing value")
        void refusesMissingValue() {
            assertThatThrownBy(() -> sealer.open("2026-10", null, EVENT_ID))
                    .isInstanceOf(SealedFieldUnreadableException.class)
                    .extracting("reason").isEqualTo(Reason.MALFORMED);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        @DisplayName("refuses an event without an id")
        void refusesMissingEventId(String eventId) {
            byte[] sealed = sealer.seal(TOKEN, EVENT_ID);

            assertThatThrownBy(() -> sealer.open("2026-10", sealed, eventId))
                    .isInstanceOf(SealedFieldUnreadableException.class)
                    .extracting("reason").isEqualTo(Reason.MALFORMED);
        }

        @Test
        @DisplayName("says why without the value, the key or the event id")
        void messageCarriesNothingSecret() {
            byte[] sealed = sealer.seal(TOKEN, EVENT_ID);

            assertThatThrownBy(() -> sealer.open("2026-10", sealed, "another-event"))
                    .hasMessageNotContaining(TOKEN)
                    .hasMessageNotContaining("another-event")
                    .hasMessageContaining("NOT_AUTHENTIC");
        }
    }

    @Nested
    @DisplayName("refusals when sealing")
    class SealingRefusals {

        @Test
        @DisplayName("refuses a missing value")
        void refusesNullValue() {
            assertThatThrownBy(() -> sealer.seal(null, EVENT_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        @DisplayName("refuses to seal without an event id to bind the value to")
        void refusesMissingEventId(String eventId) {
            assertThatThrownBy(() -> sealer.seal(TOKEN, eventId))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("key rotation")
    class Rotation {

        @Test
        @DisplayName("seals with the new key and still opens what the retired one sealed")
        void opensWhatTheRetiredKeySealed() {
            byte[] sealedBefore = sealer.seal(TOKEN, EVENT_ID);
            EventFieldSealer rotated = new EventFieldSealer(
                    new SealingKeyring("2027-01", Map.of("2026-10", key(7), "2027-01", key(8))));

            byte[] sealedAfter = rotated.seal(TOKEN, EVENT_ID);

            assertThat(rotated.keyId()).isEqualTo("2027-01");
            assertThat(rotated.open("2026-10", sealedBefore, EVENT_ID)).isEqualTo(TOKEN);
            assertThat(rotated.open("2027-01", sealedAfter, EVENT_ID)).isEqualTo(TOKEN);
            assertThatThrownBy(() -> sealer.open("2027-01", sealedAfter, EVENT_ID))
                    .extracting("reason").isEqualTo(Reason.UNKNOWN_KEY);
        }
    }
}
