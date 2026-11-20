package com.ecclesiaflow.platform.webhooks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboundNotificationTest {

    private static final String DIGEST = InboundNotification.digestOf(WebhookTestData.BODY);
    private static final Instant AT = WebhookTestData.OCCURRED_AT;

    @Test
    @DisplayName("digests a body as the lowercase hex SHA-256 the inbox keeps instead of it")
    void digestsTheBody() {
        assertThat(InboundNotification.digestOf("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("reads a missing account as the provider's own scope")
    void platformScope() {
        InboundNotification platform = new InboundNotification("acme", null, "evt_1", "account.updated", true,
                null, DIGEST, AT, null);

        assertThat(platform.account()).isEmpty();
        assertThat(platform.objectRef()).isNull();
        assertThat(platform.apiVersion()).isNull();
    }

    @Nested
    @DisplayName("refuses")
    class Refuses {

        @Test
        @DisplayName("a provider id that could not serve as a metric tag")
        void badProvider() {
            for (String provider : new String[]{null, "", "Acme", "acme provider", "a".repeat(33), "1acme"}) {
                assertThatThrownBy(() -> new InboundNotification(provider, "", "evt_1", "t", false, null, DIGEST, AT,
                        null)).as(String.valueOf(provider)).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("provider");
            }
        }

        @Test
        @DisplayName("a notification without its event id or type")
        void missingIdentity() {
            assertThatThrownBy(() -> new InboundNotification("acme", "", " ", "t", false, null, DIGEST, AT, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("eventId is required");
            assertThatThrownBy(() -> new InboundNotification("acme", "", "evt_1", null, false, null, DIGEST, AT, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("type is required");
            assertThatThrownBy(() -> new InboundNotification("acme", "", "evt_1", "t", false, null, DIGEST, null, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("a field longer than its column, naming the field and never the value")
        void tooLong() {
            String longValue = "x".repeat(256);

            assertThatThrownBy(() -> new InboundNotification("acme", longValue, "evt_1", "t", false, null, DIGEST, AT,
                    null)).hasMessage("account exceeds 255 characters").hasMessageNotContaining(longValue);
            assertThatThrownBy(() -> new InboundNotification("acme", "", longValue, "t", false, null, DIGEST, AT,
                    null)).hasMessage("eventId exceeds 255 characters");
            assertThatThrownBy(() -> new InboundNotification("acme", "", "evt_1", "x".repeat(101), false, null,
                    DIGEST, AT, null)).hasMessage("type exceeds 100 characters");
            assertThatThrownBy(() -> new InboundNotification("acme", "", "evt_1", "t", false, longValue, DIGEST, AT,
                    null)).hasMessage("objectRef exceeds 255 characters");
            assertThatThrownBy(() -> new InboundNotification("acme", "", "evt_1", "t", false, null, DIGEST, AT,
                    "v".repeat(41))).hasMessage("apiVersion exceeds 40 characters");
        }

        @Test
        @DisplayName("a digest that is not a lowercase hex SHA-256: the inbox could not compare bodies")
        void badDigest() {
            for (String digest : new String[]{null, "abc", DIGEST.toUpperCase(), DIGEST + "0"}) {
                assertThatThrownBy(() -> new InboundNotification("acme", "", "evt_1", "t", false, null, digest, AT,
                        null)).as(String.valueOf(digest)).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("payloadDigest");
            }
        }
    }

    @Test
    @DisplayName("a recorded notification needs what it records and when it came")
    void recordedNeedsItsParts() {
        assertThatThrownBy(() -> new RecordedNotification(1, null, AT, 0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecordedNotification(1, WebhookTestData.notification("evt_1"), null, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("an invalid body says why, for the counter, never in its message")
    void invalidNotificationReasons() {
        RuntimeException sdk = new RuntimeException("No signatures found matching the expected signature for payload");

        InvalidNotificationException forged = InvalidNotificationException.badSignature(sdk);
        InvalidNotificationException unreadable = InvalidNotificationException.malformed(null);

        assertThat(forged.reason()).isEqualTo(InvalidNotificationException.Reason.SIGNATURE);
        assertThat(forged).hasCause(sdk).hasMessage("The notification's signature does not hold");
        assertThat(unreadable.reason()).isEqualTo(InvalidNotificationException.Reason.MALFORMED);
        assertThat(unreadable).hasNoCause().hasMessage("The signed notification cannot be read");
    }
}
