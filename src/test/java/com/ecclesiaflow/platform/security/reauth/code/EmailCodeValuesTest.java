package com.ecclesiaflow.platform.security.reauth.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The values that carry a code, an address or a digest never print them. */
class EmailCodeValuesTest {

    private static final UUID CHALLENGE = UUID.fromString("0f8d6a3e-5b1c-4d7e-9a2b-3c4d5e6f7a8b");

    @Test
    @DisplayName("a delivery prints neither its code nor its address")
    void delivery() {
        EmailCodeDelivery delivery = new EmailCodeDelivery("caleb@gmail.com", "042137", "billing.cancel",
                Duration.ofMinutes(10), "fr");

        assertThat(delivery.toString()).contains("billing.cancel").doesNotContain("042137", "caleb");
        assertThatThrownBy(() -> new EmailCodeDelivery(null, "042137", "g", Duration.ZERO, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a proof prints its challenge, never its code")
    void proof() {
        assertThat(new ReauthProof(CHALLENGE, "042137").toString()).contains(CHALLENGE.toString())
                .doesNotContain("042137");
        assertThatThrownBy(() -> new ReauthProof(CHALLENGE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("an issue prints neither its subject, its address nor its binding")
    void issue() {
        EmailCodeIssue issue = new EmailCodeIssue("3f2c1d4e-sub", "Subscription.Cancel", "billing.cancel",
                "ab".repeat(32), true, "caleb@gmail.com", "fr");

        assertThat(issue.toString()).contains("Subscription.Cancel").doesNotContain("3f2c1d4e", "caleb", "abab");
        assertThatThrownBy(() -> new EmailCodeIssue("s", "a", null, "d", false, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a challenge needs its id, subject, binding, status and lifetimes")
    void challengeRequires() {
        Instant now = Instant.parse("2026-11-14T10:00:00Z");

        assertThatThrownBy(() -> new EmailCodeChallenge(CHALLENGE, "s", "a", "g", null, false, null, 0, now, now,
                now, EmailCodeStatus.PENDING, now, now, null)).isInstanceOf(NullPointerException.class);
        EmailCodeChallenge open = EmailCodeChallenge.open(CHALLENGE, new EmailCodeIssue("s", "a", "g", "d", false,
                "x@y.z", null), "c", now);
        assertThat(open.codeUsableAt(now)).isTrue();
        assertThat(open.voided(now).codeLiveAt(now)).isFalse();
        assertThat(open.consumed(now).openAt(now)).isFalse();
        assertThat(open.codeUsableAt(now.plus(Duration.ofMinutes(10)))).isFalse();
    }
}
