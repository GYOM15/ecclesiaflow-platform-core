package com.ecclesiaflow.platform.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityMaskingUtilsTest {

    @Nested
    @DisplayName("maskEmail")
    class MaskEmail {
        @Test
        void masksLongLocalPart() {
            assertThat(SecurityMaskingUtils.maskEmail("alice@example.com"))
                    .isEqualTo("al****@example.com");
        }

        @Test
        void masksShortLocalPart() {
            assertThat(SecurityMaskingUtils.maskEmail("ab@x.com")).isEqualTo("a****@x.com");
        }

        @Test
        void returnsUnknownOnNullOrBlank() {
            assertThat(SecurityMaskingUtils.maskEmail(null)).isEqualTo("[UNKNOWN]");
            assertThat(SecurityMaskingUtils.maskEmail("")).isEqualTo("[UNKNOWN]");
            assertThat(SecurityMaskingUtils.maskEmail("   ")).isEqualTo("[UNKNOWN]");
        }

        @Test
        void returnsInvalidWhenNoAt() {
            assertThat(SecurityMaskingUtils.maskEmail("noatsign")).isEqualTo("[INVALID_FORMAT]");
        }

        @Test
        void returnsInvalidWhenAtIsFirstChar() {
            assertThat(SecurityMaskingUtils.maskEmail("@only.com")).isEqualTo("[INVALID_FORMAT]");
        }
    }

    @Nested
    @DisplayName("maskUrlQueryParam")
    class MaskUrlQueryParam {
        @Test
        void masksTargetParamAndRedactsOthers() {
            assertThat(SecurityMaskingUtils.maskUrlQueryParam("https://x.com/api?token=abc&id=42", "token"))
                    .isEqualTo("https://x.com/api?token=****&id=[REDACTED]");
        }

        @Test
        void handlesUrlWithoutQueryString() {
            assertThat(SecurityMaskingUtils.maskUrlQueryParam("https://x.com/api", "token")).isEqualTo("[URL]");
        }

        @Test
        void handlesParamWithoutEquals() {
            assertThat(SecurityMaskingUtils.maskUrlQueryParam("https://x.com/api?flag", "token"))
                    .isEqualTo("https://x.com/api?flag");
        }

        @Test
        void returnsUnknownOnNullOrBlankUrl() {
            assertThat(SecurityMaskingUtils.maskUrlQueryParam(null, "token")).isEqualTo("[UNKNOWN]");
            assertThat(SecurityMaskingUtils.maskUrlQueryParam("", "token")).isEqualTo("[UNKNOWN]");
        }

        @Test
        void returnsUrlPlaceholderOnBlankParamName() {
            assertThat(SecurityMaskingUtils.maskUrlQueryParam("https://x.com/api?token=abc", null))
                    .isEqualTo("[URL]");
        }

        @Test
        void maskConfirmationLinkDelegatesToMaskUrlQueryParam() {
            assertThat(SecurityMaskingUtils.maskConfirmationLink("https://x.com/confirm?token=abc&u=42"))
                    .isEqualTo("https://x.com/confirm?token=****&u=[REDACTED]");
        }
    }

    @Nested
    @DisplayName("maskId")
    class MaskId {
        @Test
        void masksUuid() {
            String id = UUID.randomUUID().toString();
            String masked = SecurityMaskingUtils.maskId(id);
            assertThat(masked).startsWith(id.substring(0, 8)).endsWith("********");
        }

        @Test
        void masksShortIdAsStars() {
            assertThat(SecurityMaskingUtils.maskId("abc")).isEqualTo("********");
        }

        @Test
        void masksLongNonUuidIdKeepingFirstEight() {
            assertThat(SecurityMaskingUtils.maskId("1234567890")).isEqualTo("12345678********");
        }

        @Test
        void returnsUnknownOnNullOrBlank() {
            assertThat(SecurityMaskingUtils.maskId(null)).isEqualTo("[UNKNOWN]");
            assertThat(SecurityMaskingUtils.maskId(" ")).isEqualTo("[UNKNOWN]");
        }
    }

    @Nested
    @DisplayName("maskAny / maskArgs")
    class MaskAny {
        @Test
        void detectsEmail() {
            assertThat(SecurityMaskingUtils.maskAny("alice@x.com")).isEqualTo("al****@x.com");
        }

        @Test
        void redactsJwtLookingValue() {
            assertThat(SecurityMaskingUtils.maskAny("AAAA.BBBB.CCCC")).isEqualTo("[REDACTED]");
        }

        @Test
        void masksUrlWithToken() {
            assertThat(SecurityMaskingUtils.maskAny("https://x.com/api?token=secret")).contains("token=****");
        }

        @Test
        void redactsBearer() {
            assertThat(SecurityMaskingUtils.maskAny("Bearer abcdef")).isEqualTo("Bearer ****");
        }

        @Test
        void abbreviatesLongString() {
            String s = "a".repeat(150);
            assertThat(SecurityMaskingUtils.maskAny(s)).hasSize(123).endsWith("...");
        }

        @Test
        void returnsUnknownOnNullOrBlank() {
            assertThat(SecurityMaskingUtils.maskAny(null)).isEqualTo("[UNKNOWN]");
            assertThat(SecurityMaskingUtils.maskAny("   ")).isEqualTo("[UNKNOWN]");
        }

        @Test
        void maskArgsHandlesNullAndDispatches() {
            assertThat(SecurityMaskingUtils.maskArgs(null)).isEqualTo("[]");
            Object[] args = { "alice@x.com", "Bearer abc", null };
            String result = SecurityMaskingUtils.maskArgs(args);
            assertThat(result).contains("al****@x.com").contains("Bearer ****").contains("[UNKNOWN]");
        }

        @Test
        void returnsUrlPlaceholderForNonTokenUrl() {
            assertThat(SecurityMaskingUtils.maskAny("http://x.com/api")).isEqualTo("[URL]");
        }
    }

    @Nested
    @DisplayName("sanitizeInfra / rootMessage")
    class Sanitize {
        @Test
        void stripsUrlsHostsPorts() {
            String msg = "Connect failed to https://example.com/api host db.example.com:5432";
            assertThat(SecurityMaskingUtils.sanitizeInfra(msg)).contains("[URL]").contains("[HOST:PORT]");
        }

        @Test
        void passesThroughNullOrBlank() {
            assertThat(SecurityMaskingUtils.sanitizeInfra(null)).isNull();
            assertThat(SecurityMaskingUtils.sanitizeInfra("")).isEmpty();
        }

        @Test
        void rootMessageUnwrapsCauses() {
            Throwable root = new IllegalStateException("Boom at https://x.com");
            Throwable wrapper = new RuntimeException("wrap", root);
            assertThat(SecurityMaskingUtils.rootMessage(wrapper)).contains("[URL]");
        }

        @Test
        void rootMessageHandlesNull() {
            assertThat(SecurityMaskingUtils.rootMessage(null)).isEqualTo("[NO_ERROR]");
        }

        @Test
        void rootMessageFallsBackToClassName() {
            assertThat(SecurityMaskingUtils.rootMessage(new IllegalStateException()))
                    .isEqualTo("IllegalStateException");
        }

        @Test
        @DisplayName("F053: a long message costs milliseconds, not minutes")
        void aLongMessageIsBoundedBeforeThePatternsRun() {
            // The host patterns backtrack on long runs of [a-zA-Z0-9._-], so the
            // cost grew with the SQUARE of the input: 64 KB took 41 seconds of
            // CPU. The input is an exception message from a driver, and anyone
            // who can make one long turns a request into minutes of a thread.
            String hostile = "a".repeat(64 * 1024);

            long start = System.nanoTime();
            String masked = SecurityMaskingUtils.sanitizeInfra(hostile);
            long millis = (System.nanoTime() - start) / 1_000_000;

            assertThat(millis).as("64 KB took %d ms", millis).isLessThan(250L);
            // Bounded, and visibly so.
            assertThat(masked).hasSizeLessThanOrEqualTo(512 + 3).endsWith("...");
        }

        @Test
        @DisplayName("F053: the truncation does not cost the masking")
        void theMaskingStillHappensWithinTheBound() {
            String msg = "Connection to db.internal:5432 refused, see https://x.example/y";

            String masked = SecurityMaskingUtils.sanitizeInfra(msg);

            assertThat(masked).contains("[URL]").contains("[HOST:PORT]")
                    .doesNotContain("db.internal").doesNotContain("x.example");
        }

        @Test
        @DisplayName("F053: a bare domain is still masked — the pattern was NOT rewritten")
        void aBareDomainIsStillMasked() {
            // The first attempt at this fix made the host pattern possessive and
            // it silently stopped matching api.example.com: the middle group
            // swallowed the final label and could not give it back. A masker that
            // quietly stops masking is worse than the slowness it replaced, so
            // the patterns stayed as they were and only the LENGTH is bounded.
            assertThat(SecurityMaskingUtils.sanitizeInfra("DNS lookup failed for api.example.com"))
                    .isEqualTo("DNS lookup failed for [HOST]");
        }

    }

    @Nested
    @DisplayName("abbreviate")
    class Abbreviate {
        @Test
        void truncatesLongStrings() {
            assertThat(SecurityMaskingUtils.abbreviate("hello world", 5)).isEqualTo("hello...");
        }

        @Test
        void keepsShortStrings() {
            assertThat(SecurityMaskingUtils.abbreviate("hi", 10)).isEqualTo("hi");
        }

        @Test
        void handlesNull() {
            assertThat(SecurityMaskingUtils.abbreviate(null, 10)).isEqualTo("[UNKNOWN]");
        }
    }
}
