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
