package com.ecclesiaflow.platform.security.reauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SensitiveActionTest {

    @Test
    @DisplayName("keeps the arguments in order, nulls included, and cannot be changed afterwards")
    void arguments() {
        List<Object> received = new ArrayList<>(Arrays.asList("new@example.org", null, 7));
        SensitiveAction action = new SensitiveAction("EMAIL_CHANGE", "sub", received);
        received.clear();

        assertThat(action.arguments()).containsExactly("new@example.org", null, 7);
        assertThatThrownBy(() -> action.arguments().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("finds the first argument of a type, or none")
    void argumentOfType() {
        SensitiveAction action = new SensitiveAction("EMAIL_CHANGE", "sub", Arrays.asList(null, 7, "a", "b"));

        assertThat(action.argument(String.class)).contains("a");
        assertThat(action.argument(Integer.class)).contains(7);
        assertThat(action.argument(Long.class)).isEmpty();
    }

    @Test
    @DisplayName("a method without arguments gives an empty list")
    void noArguments() {
        assertThat(new SensitiveAction("ACCOUNT_DELETION", "sub", null).arguments()).isEmpty();
    }
}
