package com.ecclesiaflow.platform.rpc.s2s.interceptor;

import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S2sAzpAllowListTest {

    private static S2sProperties props(List<String> allowedAzp, boolean required) {
        S2sProperties props = new S2sProperties();
        props.setAllowedAzp(allowedAzp);
        props.setRequireAllowedAzp(required);
        return props;
    }

    @Test
    void anEmptyListLetsEveryClientThrough() {
        S2sAzpAllowList allowList = S2sAzpAllowList.from(props(List.of(), false));

        assertThat(allowList.isEnforced()).isFalse();
        assertThat(allowList.permits("ecclesiaflow-frontend")).isTrue();
        assertThat(allowList.permits(null)).isTrue();
    }

    @Test
    void aSetListLetsOnlyItsClientsThrough() {
        S2sAzpAllowList allowList = S2sAzpAllowList.from(props(List.of(" ecclesiaflow-church-backend "), false));

        assertThat(allowList.isEnforced()).isTrue();
        assertThat(allowList.clientIds()).containsExactly("ecclesiaflow-church-backend");
        assertThat(allowList.permits("ecclesiaflow-church-backend")).isTrue();
        assertThat(allowList.permits("ecclesiaflow-frontend")).isFalse();
        assertThat(allowList.permits(null)).isFalse();
    }

    @Test
    void blankEntriesReadAsNotConfigured() {
        S2sAzpAllowList allowList = S2sAzpAllowList.from(props(Arrays.asList("", "  ", null), false));

        assertThat(allowList.isEnforced()).isFalse();
    }

    @Test
    void anUnsetListReadsAsNotConfigured() {
        S2sAzpAllowList allowList = S2sAzpAllowList.from(props(null, false));

        assertThat(allowList.isEnforced()).isFalse();
    }

    @Test
    void aRequiredListMustNotBeEmpty() {
        assertThatThrownBy(() -> S2sAzpAllowList.from(props(List.of(" "), true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-azp");
    }

    @Test
    void aRequiredListWithEntriesIsAccepted() {
        assertThat(S2sAzpAllowList.from(props(List.of("ecclesiaflow-auth-backend"), true)).isEnforced()).isTrue();
    }
}
