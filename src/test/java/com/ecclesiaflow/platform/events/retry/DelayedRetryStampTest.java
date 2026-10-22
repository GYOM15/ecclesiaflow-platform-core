package com.ecclesiaflow.platform.events.retry;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelayedRetryStampTest {

    private static final String SECRET = "stamp-test-secret";
    private static final RetryHop HOP = new RetryHop("auth.subscriber.member-account-status", 1, 1_790_000_000_000L,
            "ecclesiaflow.domain-events", "members.account.deactivated");
    private static final String SIGNED_AT = "1789999990000";
    private static final String SIGNATURE = "c2lnbmF0dXJl";
    private static final byte[] BODY = "wire".getBytes(StandardCharsets.UTF_8);

    private final DelayedRetryStamp stamp = new DelayedRetryStamp(SECRET);

    @Test
    @DisplayName("Matches the stamp it signed")
    void matchesItsOwnStamp() {
        String signed = stamp.sign(HOP, SIGNED_AT, SIGNATURE, BODY);

        assertThat(stamp.isEnabled()).isTrue();
        assertThat(stamp.matches(HOP, SIGNED_AT, SIGNATURE, BODY, signed)).isTrue();
        assertThat(new DelayedRetryStamp(SECRET).matches(HOP, SIGNED_AT, SIGNATURE, BODY, signed)).isTrue();
    }

    @Nested
    @DisplayName("Refuses a stamp once anything it covers changed")
    class Tampering {

        private final String signed = stamp.sign(HOP, SIGNED_AT, SIGNATURE, BODY);

        @Test
        @DisplayName("the stage, the instant, the queue or the original route")
        void theHop() {
            assertThat(stamp.matches(new RetryHop(HOP.queue(), 2, HOP.retriedAt(), HOP.originalExchange(),
                    HOP.originalRoutingKey()), SIGNED_AT, SIGNATURE, BODY, signed)).isFalse();
            assertThat(stamp.matches(new RetryHop(HOP.queue(), 1, HOP.retriedAt() + 7_200_000L,
                    HOP.originalExchange(), HOP.originalRoutingKey()), SIGNED_AT, SIGNATURE, BODY, signed)).isFalse();
            assertThat(stamp.matches(new RetryHop("chat.subscriber.member-account-status", 1, HOP.retriedAt(),
                    HOP.originalExchange(), HOP.originalRoutingKey()), SIGNED_AT, SIGNATURE, BODY, signed)).isFalse();
            assertThat(stamp.matches(new RetryHop(HOP.queue(), 1, HOP.retriedAt(), HOP.originalExchange(),
                    "members.account.reactivated"), SIGNED_AT, SIGNATURE, BODY, signed)).isFalse();
        }

        @Test
        @DisplayName("the event's signature headers or its body")
        void theEvent() {
            assertThat(stamp.matches(HOP, "1789999999999", SIGNATURE, BODY, signed)).isFalse();
            assertThat(stamp.matches(HOP, SIGNED_AT, "b3RoZXI=", BODY, signed)).isFalse();
            assertThat(stamp.matches(HOP, SIGNED_AT, SIGNATURE, "wirf".getBytes(StandardCharsets.UTF_8), signed))
                    .isFalse();
        }

        @Test
        @DisplayName("an absent header, which never reads as an empty one")
        void anAbsentHeader() {
            String withEmptySignature = stamp.sign(HOP, SIGNED_AT, "", BODY);

            assertThat(stamp.matches(HOP, SIGNED_AT, null, BODY, withEmptySignature)).isFalse();
            assertThat(stamp.matches(HOP, SIGNED_AT, null, BODY, stamp.sign(HOP, SIGNED_AT, null, BODY))).isTrue();
        }

        @Test
        @DisplayName("another secret")
        void anotherSecret() {
            assertThat(new DelayedRetryStamp("another-secret").matches(HOP, SIGNED_AT, SIGNATURE, BODY, signed))
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Never matches a missing, blank or malformed candidate")
    void refusesUnusableCandidates() {
        assertThat(stamp.matches(HOP, SIGNED_AT, SIGNATURE, BODY, null)).isFalse();
        assertThat(stamp.matches(HOP, SIGNED_AT, SIGNATURE, BODY, " ")).isFalse();
        assertThat(stamp.matches(HOP, SIGNED_AT, SIGNATURE, BODY, "not base64 !")).isFalse();
        assertThat(stamp.matches(null, SIGNED_AT, SIGNATURE, BODY, "c3RhbXA=")).isFalse();
        assertThat(stamp.matches(HOP, SIGNED_AT, SIGNATURE, null, "c3RhbXA=")).isFalse();
    }

    @Test
    @DisplayName("Uses a key of its own: no stamp is the event signature of the same bytes")
    void neverDoublesAsAnEventSignature() {
        DomainEventSigner signer = new DomainEventSigner(SECRET);
        byte[] canonical = DelayedRetryStamp.canonical(HOP, SIGNED_AT, SIGNATURE, BODY);
        String eventSignature = signer.sign("", "", 0L, canonical);

        assertThat(stamp.sign(HOP, SIGNED_AT, SIGNATURE, BODY)).isNotEqualTo(eventSignature);
        assertThat(Base64.getDecoder().decode(stamp.sign(HOP, SIGNED_AT, SIGNATURE, BODY))).hasSize(32);
    }

    @Test
    @DisplayName("Length-prefixes each field, so bytes cannot move from one field to the next")
    void lengthPrefixesItsFields() {
        RetryHop shifted = new RetryHop(HOP.queue(), 1, HOP.retriedAt(), "ecclesiaflow.domain-eventsm",
                "embers.account.deactivated");

        assertThat(DelayedRetryStamp.canonical(HOP, SIGNED_AT, SIGNATURE, BODY))
                .isNotEqualTo(DelayedRetryStamp.canonical(shifted, SIGNED_AT, SIGNATURE, BODY));
    }

    @Nested
    @DisplayName("Without a secret")
    class Disabled {

        @Test
        @DisplayName("stamps nothing and matches nothing")
        void isDisabled() {
            DelayedRetryStamp disabled = new DelayedRetryStamp(" ");

            assertThat(disabled.isEnabled()).isFalse();
            assertThat(new DelayedRetryStamp(null).isEnabled()).isFalse();
            assertThat(disabled.matches(HOP, SIGNED_AT, SIGNATURE, BODY, "c3RhbXA=")).isFalse();
            assertThatThrownBy(() -> disabled.sign(HOP, SIGNED_AT, SIGNATURE, BODY))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("Refuses to stamp without a hop or a body")
    void refusesAnIncompleteStamp() {
        assertThatThrownBy(() -> stamp.sign(null, SIGNED_AT, SIGNATURE, BODY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stamp.sign(HOP, SIGNED_AT, SIGNATURE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
