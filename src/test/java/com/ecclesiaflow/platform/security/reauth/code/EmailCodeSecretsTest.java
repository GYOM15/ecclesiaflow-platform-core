package com.ecclesiaflow.platform.security.reauth.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailCodeSecretsTest {

    private static final UUID CHALLENGE = UUID.fromString("0f8d6a3e-5b1c-4d7e-9a2b-3c4d5e6f7a8b");
    private static final byte[] KEY = new byte[32];

    private final EmailCodeSecrets secrets = new EmailCodeSecrets(KEY, new SecureRandom());

    /** Draws the given values in turn, to see the code's shape at the edges of its space. */
    private static SecureRandom drawing(int... values) {
        return new SecureRandom() {
            private int next;

            @Override
            public int nextInt(int bound) {
                assertThat(bound).isEqualTo(1_000_000);
                return values[next++];
            }
        };
    }

    @Nested
    @DisplayName("the code")
    class Code {

        @Test
        @DisplayName("is six digits drawn over the whole space, leading zeros included")
        void sixDigits() {
            EmailCodeSecrets edges = new EmailCodeSecrets(KEY, drawing(0, 42, 999_999));

            assertThat(List.of(edges.newCode(), edges.newCode(), edges.newCode()))
                    .containsExactly("000000", "000042", "999999");
            Set<String> drawn = new HashSet<>();
            for (int i = 0; i < 200; i++) {
                drawn.add(secrets.newCode());
            }
            assertThat(drawn).allMatch(code -> code.matches("[0-9]{6}")).hasSizeGreaterThan(190);
        }

        @Test
        @DisplayName("is stored as a digest bound to its challenge and the module's key")
        void boundDigest() {
            String digest = secrets.codeDigest(CHALLENGE, "123456");

            assertThat(digest).matches("[0-9a-f]{64}")
                    .isNotEqualTo(secrets.codeDigest(UUID.randomUUID(), "123456"))
                    .isNotEqualTo(secrets.codeDigest(CHALLENGE, "123457"))
                    .isNotEqualTo(new EmailCodeSecrets(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                            16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}, new SecureRandom())
                            .codeDigest(CHALLENGE, "123456"));
        }

        @Test
        @DisplayName("matches only its own digest, a missing or malformed one never, by the same comparison")
        void matches() {
            String digest = secrets.codeDigest(CHALLENGE, "123456");

            assertThat(secrets.codeMatches(secrets.codeMac(CHALLENGE, "123456"), digest)).isTrue();
            assertThat(secrets.codeMatches(secrets.codeMac(CHALLENGE, "654321"), digest)).isFalse();
            assertThat(secrets.codeMatches(new byte[32], null)).isFalse();
            assertThat(secrets.codeMatches(new byte[32], "0".repeat(64).replace('0', 'z'))).isFalse();
            assertThat(secrets.codeMatches(new byte[32], "00")).isFalse();
        }
    }

    @Nested
    @DisplayName("the binding")
    class Binding {

        @Test
        @DisplayName("is stable for one request and changes with any of its parts, boundaries included")
        void stable() {
            String digest = secrets.bindingDigest("Subscription.Cancel", "sub", null, List.of("church"));

            assertThat(digest).isEqualTo(secrets.bindingDigest("Subscription.Cancel", "sub", null, List.of("church")))
                    .isNotEqualTo(secrets.bindingDigest("Subscription.Cancel", "sub", "key", List.of("church")))
                    .isNotEqualTo(secrets.bindingDigest("Subscription.Cancel", "sub", null, List.of("other")))
                    .isNotEqualTo(secrets.bindingDigest("Subscription.Cancel", "su", "b", List.of("church")));
            assertThat(secrets.sameBinding(digest, digest)).isTrue();
            assertThat(secrets.sameBinding(digest, secrets.bindingDigest("x"))).isFalse();
        }

        @Test
        @DisplayName("never equals the code's digest of the same bytes: the purposes are kept apart")
        void separatePurposes() {
            assertThat(secrets.bindingDigest("123456")).isNotEqualTo(secrets.codeDigest(CHALLENGE, "123456"));
        }

        @Test
        @DisplayName("refuses a part whose text could differ between two identical requests")
        void unstablePart() {
            assertThatThrownBy(() -> secrets.bindingDigest(new HashSet<>(List.of("a"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the key")
    class Key {

        @Test
        @DisplayName("needs 32 bytes at least")
        void shortKey() {
            assertThatThrownBy(() -> new EmailCodeSecrets(new byte[31], new SecureRandom()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("reads base64, standard or URL-safe")
        void decodes() {
            byte[] key = new byte[48];
            key[0] = (byte) 0xfb;
            key[1] = (byte) 0xff;

            assertThat(EmailCodeSecrets.decodeKey(Base64.getEncoder().encodeToString(key), "K")).isEqualTo(key);
            assertThat(EmailCodeSecrets.decodeKey(" " + Base64.getUrlEncoder().withoutPadding().encodeToString(key),
                    "K")).isEqualTo(key);
        }

        @Test
        @DisplayName("refuses one blank, short or not base64, naming the setting, never the value")
        void refuses() {
            String shortKey = Base64.getEncoder().encodeToString(new byte[31]);

            assertThatThrownBy(() -> EmailCodeSecrets.decodeKey(null, "BILLING_REAUTH_CODE_KEY"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("BILLING_REAUTH_CODE_KEY");
            assertThatThrownBy(() -> EmailCodeSecrets.decodeKey(" ", "K")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> EmailCodeSecrets.decodeKey(shortKey, "K"))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(shortKey);
            assertThatThrownBy(() -> EmailCodeSecrets.decodeKey("not base64!secret", "K"))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret");
        }

        @Test
        @DisplayName("never shows in a text")
        void toStringHidesKey() {
            assertThat(secrets).hasToString("EmailCodeSecrets");
        }
    }
}
