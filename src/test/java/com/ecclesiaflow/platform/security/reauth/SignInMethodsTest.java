package com.ecclesiaflow.platform.security.reauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SignInMethodsTest {

    @Test
    @DisplayName("any provider alias is a link, one the caller does not know included")
    void linked() {
        assertThat(new SignInMethods(false, List.of("google")).linkedToProvider()).isTrue();
        assertThat(new SignInMethods(true, List.of("some-future-idp")).linkedToProvider()).isTrue();
    }

    @Test
    @DisplayName("no alias, or no list at all, is no link")
    void notLinked() {
        assertThat(new SignInMethods(true, List.of()).linkedToProvider()).isFalse();
        assertThat(new SignInMethods(true, null).linkedProviders()).isEmpty();
    }
}
