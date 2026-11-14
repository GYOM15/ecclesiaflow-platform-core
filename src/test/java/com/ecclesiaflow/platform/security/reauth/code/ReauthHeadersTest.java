package com.ecclesiaflow.platform.security.reauth.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReauthHeadersTest {

    private static final String CHALLENGE = "0f8d6a3e-5b1c-4d7e-9a2b-3c4d5e6f7a8b";

    @Test
    @DisplayName("are the names the console sends")
    void names() {
        assertThat(ReauthHeaders.CHALLENGE).isEqualTo("Reauth-Challenge");
        assertThat(ReauthHeaders.CODE).isEqualTo("Reauth-Code");
    }

    @Test
    @DisplayName("read as nothing when both are absent: the gesture asks for a code")
    void absent() {
        assertThat(ReauthHeaders.parse(List.of(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("read a canonical challenge and six digits, leading zeros kept, surrounding spaces dropped")
    void proof() {
        assertThat(ReauthHeaders.parse(List.of(" " + CHALLENGE), List.of("004217 ")))
                .contains(new ReauthProof(UUID.fromString(CHALLENGE), "004217"));
    }

    @ParameterizedTest(name = "code \"{0}\"")
    @ValueSource(strings = {"", "12345", "1234567", "12 456", "12a456", "١٢٣٤٥٦"})
    @DisplayName("refuse a code that is not six ASCII digits, as malformed")
    void malformedCode(String code) {
        assertThatThrownBy(() -> ReauthHeaders.parse(List.of(CHALLENGE), List.of(code)))
                .isInstanceOfSatisfying(InvalidReauthCodeException.class,
                        refused -> assertThat(refused.isCounted()).isFalse());
    }

    @ParameterizedTest(name = "challenge \"{0}\"")
    @ValueSource(strings = {"", "1-2-3-4-5", "0f8d6a3e5b1c4d7e9a2b3c4d5e6f7a8b", "not-a-uuid"})
    @DisplayName("refuse a challenge that is not a canonical UUID, as malformed")
    void malformedChallenge(String challenge) {
        assertThatThrownBy(() -> ReauthHeaders.parse(List.of(challenge), List.of("123456")))
                .isInstanceOf(InvalidReauthCodeException.class);
    }

    @Test
    @DisplayName("refuse one without the other, a header sent twice, or a missing value")
    void incomplete() {
        assertThatThrownBy(() -> ReauthHeaders.parse(List.of(CHALLENGE), List.of()))
                .isInstanceOf(InvalidReauthCodeException.class);
        assertThatThrownBy(() -> ReauthHeaders.parse(List.of(), List.of("123456")))
                .isInstanceOf(InvalidReauthCodeException.class);
        assertThatThrownBy(() -> ReauthHeaders.parse(List.of(CHALLENGE, CHALLENGE), List.of("123456")))
                .isInstanceOf(InvalidReauthCodeException.class);
        assertThatThrownBy(() -> ReauthHeaders.parse(Arrays.asList((String) null), Arrays.asList((String) null)))
                .isInstanceOf(InvalidReauthCodeException.class);
    }
}
