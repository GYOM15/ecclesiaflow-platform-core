package com.ecclesiaflow.platform.events.signing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventSigningPropertiesTest {

    @Test
    void defaultsAreSafeAndInert() {
        EventSigningProperties props = new EventSigningProperties();
        // no weak default secret — blank means signing disabled
        assertThat(props.getHmacSecret()).isEmpty();
        assertThat(props.isSigningEnabled()).isFalse();
        // strict verification is off by default — consumers can ship before publishers sign
        assertThat(props.isVerifySignatures()).isFalse();
    }

    @Test
    void signingEnabledOnlyWithNonBlankSecret() {
        EventSigningProperties props = new EventSigningProperties();

        props.setHmacSecret("   ");
        assertThat(props.isSigningEnabled()).isFalse();

        props.setHmacSecret("real-secret");
        assertThat(props.isSigningEnabled()).isTrue();
    }

    @Test
    void signingEnabledFalseWhenSecretNull() {
        EventSigningProperties props = new EventSigningProperties();
        props.setHmacSecret(null);
        assertThat(props.isSigningEnabled()).isFalse();
    }

    @Test
    void settersAndGettersRoundTrip() {
        EventSigningProperties props = new EventSigningProperties();
        props.setHmacSecret("abc");
        props.setVerifySignatures(true);
        assertThat(props.getHmacSecret()).isEqualTo("abc");
        assertThat(props.isVerifySignatures()).isTrue();
    }
}
