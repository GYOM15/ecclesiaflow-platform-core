package com.ecclesiaflow.platform.events.erasure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RecipientDigest")
class RecipientDigestTest {

    // Known answers, from: printf '%s' <canonical address> | shasum -a 256
    private static final String JEAN = "30ef3c11199e136a01bc3c6c26caac6d323c5f476d95eba58f456493b85fe3bf";
    private static final String PHONE = "06eb244cc4f54a9158304b99189b8b77901d7d296e6a8f2ae0c0b4e06a9d89a6";

    @Nested
    @DisplayName("ofEmail")
    class OfEmail {

        @Test
        @DisplayName("is the lowercase hex SHA-256 of the address")
        void isTheSha256OfTheAddress() {
            assertThat(RecipientDigest.ofEmail("jean.dupont@example.org")).hasValue(JEAN);
        }

        @Test
        @DisplayName("reads every spelling of one address as the same digest")
        void readsEverySpellingAsOne() {
            assertThat(RecipientDigest.ofEmail("  Jean.Dupont@Example.ORG ")).hasValue(JEAN);
        }

        @Test
        @DisplayName("never carries the address itself")
        void neverCarriesTheAddress() {
            assertThat(RecipientDigest.ofEmail("jean.dupont@example.org").orElseThrow())
                    .doesNotContain("jean")
                    .matches(RecipientDigest::isDigest);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "no-at-sign", "@example.org", "jean@"})
        @DisplayName("is empty for what is not an address")
        void isEmptyForANonAddress(String value) {
            assertThat(RecipientDigest.ofEmail(value)).isEmpty();
        }
    }

    @Nested
    @DisplayName("ofPhone")
    class OfPhone {

        @Test
        @DisplayName("is the digest of + and the digits, as an E.164 number is stored")
        void isTheDigestOfTheE164Form() {
            assertThat(RecipientDigest.ofPhone("+15145550123")).hasValue(PHONE);
        }

        @Test
        @DisplayName("reads a number without its + or with separators as the same digest")
        void readsEverySpellingAsOne() {
            assertThat(RecipientDigest.ofPhone("15145550123")).hasValue(PHONE);
            assertThat(RecipientDigest.ofPhone(" +1 (514) 555-0123 ")).hasValue(PHONE);
            assertThat(RecipientDigest.ofPhone("+1.514.555.0123")).hasValue(PHONE);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "+0123456", "abc", "+1234567890123456", "1"})
        @DisplayName("is empty for what is not an international number")
        void isEmptyForANonNumber(String value) {
            assertThat(RecipientDigest.ofPhone(value)).isEmpty();
        }
    }

    @Nested
    @DisplayName("isDigest")
    class IsDigest {

        @Test
        @DisplayName("accepts 64 lowercase hex characters")
        void acceptsADigest() {
            assertThat(RecipientDigest.isDigest(JEAN)).isTrue();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"jean.dupont@example.org", "+15145550123", JEAN + "0",
                "30EF3C11199E136A01BC3C6C26CAAC6D323C5F476D95EBA58F456493B85FE3BF",
                "z0ef3c11199e136a01bc3c6c26caac6d323c5f476d95eba58f456493b85fe3bf"})
        @DisplayName("refuses anything else, an address in clear first")
        void refusesTheRest(String value) {
            assertThat(RecipientDigest.isDigest(value)).isFalse();
        }
    }
}
