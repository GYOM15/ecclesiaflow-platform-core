package com.ecclesiaflow.platform.security.reauth.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AddressMaskTest {

    @Test
    @DisplayName("keeps the first character and the domain")
    void masks() {
        assertThat(AddressMask.mask("caleb@gmail.com")).isEqualTo("c•••@gmail.com");
        assertThat(AddressMask.mask(" c@x.org ")).isEqualTo("c•••@x.org");
        assertThat(AddressMask.mask("😀smile@x.org")).isEqualTo("😀•••@x.org");
    }

    @Test
    @DisplayName("gives nothing for what does not read as an address, so the client words it without")
    void notAnAddress() {
        assertThat(AddressMask.mask(null)).isNull();
        assertThat(AddressMask.mask("")).isNull();
        assertThat(AddressMask.mask("@gmail.com")).isNull();
        assertThat(AddressMask.mask("caleb@")).isNull();
        assertThat(AddressMask.mask("caleb")).isNull();
    }
}
