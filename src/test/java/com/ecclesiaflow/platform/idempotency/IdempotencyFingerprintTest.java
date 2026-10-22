package com.ecclesiaflow.platform.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyFingerprintTest {

    private static final UUID EVENT = UUID.fromString("0b6a8d4e-3c21-4f7a-8e90-5d1c2b3a4f56");

    enum Visibility { PUBLIC }

    record Mention(UUID member, int offset) {
    }

    static final class Opaque {
    }

    @Nested
    @DisplayName("the same request gives the same fingerprint")
    class Stable {

        @Test
        @DisplayName("as 64 hex characters, whatever the instance that computes it")
        void hexSha256() {
            String fingerprint = IdempotencyFingerprint.of(EVENT, "Hello", 3, Visibility.PUBLIC,
                    Instant.parse("2026-10-22T08:00:00Z"), new Mention(EVENT, 4));

            assertThat(fingerprint).hasSize(64).matches("[0-9a-f]{64}")
                    .isEqualTo(IdempotencyFingerprint.of(UUID.fromString(EVENT.toString()), "Hello", 3,
                            Visibility.PUBLIC, Instant.parse("2026-10-22T08:00:00Z"), new Mention(EVENT, 4)));
        }

        @Test
        @DisplayName("over an ordered collection or an object array, element by element")
        void sequences() {
            List<String> mentions = new ArrayList<>(List.of("a", "b"));

            assertThat(IdempotencyFingerprint.of(mentions))
                    .isEqualTo(IdempotencyFingerprint.of(new LinkedHashSet<>(mentions)))
                    .isEqualTo(IdempotencyFingerprint.of((Object) new String[]{"a", "b"}));
        }

        @Test
        @DisplayName("over no part at all, or a lone null")
        void degenerate() {
            assertThat(IdempotencyFingerprint.of()).hasSize(64);
            assertThat(IdempotencyFingerprint.of((Object[]) null)).isEqualTo(IdempotencyFingerprint.of((Object) null));
        }
    }

    @Nested
    @DisplayName("a different request gives a different fingerprint")
    class Distinct {

        @Test
        @DisplayName("when a boundary moves between parts")
        void boundaries() {
            assertThat(IdempotencyFingerprint.of("ab", "c")).isNotEqualTo(IdempotencyFingerprint.of("a", "bc"));
        }

        @Test
        @DisplayName("when a part is null rather than the text null, or missing")
        void nulls() {
            assertThat(IdempotencyFingerprint.of("x", null))
                    .isNotEqualTo(IdempotencyFingerprint.of("x", "null"))
                    .isNotEqualTo(IdempotencyFingerprint.of("x"));
        }

        @Test
        @DisplayName("when the parts are reordered or nested differently")
        void structure() {
            assertThat(IdempotencyFingerprint.of("a", "b")).isNotEqualTo(IdempotencyFingerprint.of("b", "a"));
            assertThat(IdempotencyFingerprint.of(List.of("a", "b"))).isNotEqualTo(IdempotencyFingerprint.of("a", "b"));
            assertThat(IdempotencyFingerprint.of(List.of())).isNotEqualTo(IdempotencyFingerprint.of((Object) null));
        }

        @Test
        @DisplayName("when one character of the body changes")
        void body() {
            assertThat(IdempotencyFingerprint.of(EVENT, "Hello")).isNotEqualTo(IdempotencyFingerprint.of(EVENT, "Hellö"));
        }
    }

    @Nested
    @DisplayName("a part whose text could change between two identical requests is refused")
    class Unstable {

        @Test
        @DisplayName("an unordered collection")
        void unorderedCollection() {
            assertThatThrownBy(() -> IdempotencyFingerprint.of(new HashSet<>(List.of("a", "b"))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no stable order");
        }

        @Test
        @DisplayName("a map")
        void map() {
            assertThatThrownBy(() -> IdempotencyFingerprint.of(Map.of("a", 1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no stable order");
        }

        @Test
        @DisplayName("a primitive array")
        void primitiveArray() {
            assertThatThrownBy(() -> IdempotencyFingerprint.of((Object) new int[]{1, 2}))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity");
        }

        @Test
        @DisplayName("an object without its own toString, nested ones included")
        void opaqueObject() {
            assertThatThrownBy(() -> IdempotencyFingerprint.of(new Opaque()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(Opaque.class.getName());
            assertThatThrownBy(() -> IdempotencyFingerprint.of(Arrays.asList("a", new Opaque())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
