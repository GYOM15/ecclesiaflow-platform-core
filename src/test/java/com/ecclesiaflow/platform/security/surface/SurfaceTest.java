package com.ecclesiaflow.platform.security.surface;

import com.ecclesiaflow.platform.contract.ContractOperation;
import com.ecclesiaflow.platform.contract.InvalidApiContractException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SurfaceTest {

    static ContractOperation operation(boolean tokenless, Map<String, Object> extensions) {
        return new ContractOperation("GET", "/module/x", "X.Get", tokenless, extensions, List.of(), "test");
    }

    private static List<GrantedAuthority> authorities(String... names) {
        return AuthorityUtils.createAuthorityList(names);
    }

    @Nested
    @DisplayName("of")
    class Of {

        @Test
        @DisplayName("no x-ef-surface means console only")
        void absentIsConsole() {
            assertThat(Surface.of(operation(false, Map.of()))).isEqualTo(Surface.CONSOLE);
        }

        @Test
        @DisplayName("member and platform are read as such")
        void knownValues() {
            assertThat(Surface.of(operation(false, Map.of(Surface.EXTENSION, "member")))).isEqualTo(Surface.MEMBER);
            assertThat(Surface.of(operation(false, Map.of(Surface.EXTENSION, "platform"))))
                    .isEqualTo(Surface.PLATFORM);
            assertThat(Surface.of(operation(true, Map.of(Surface.EXTENSION, "member")))).isEqualTo(Surface.MEMBER);
        }

        @Test
        @DisplayName("any other value, a misspelling or anonymous included, stops the module")
        void unknownValues() {
            for (Object value : List.of("anonymous", "Member", "console", true)) {
                assertThatThrownBy(() -> Surface.of(operation(false, Map.of(Surface.EXTENSION, value))))
                        .isInstanceOf(InvalidApiContractException.class)
                        .hasMessageContaining("X.Get")
                        .hasMessageContaining("expected member or platform");
            }
        }

        @Test
        @DisplayName("a key without a value is not the absent key")
        void explicitNull() {
            Map<String, Object> extensions = new HashMap<>();
            extensions.put(Surface.EXTENSION, null);

            assertThatThrownBy(() -> Surface.of(operation(false, Collections.unmodifiableMap(extensions))))
                    .isInstanceOf(InvalidApiContractException.class)
                    .hasMessageContaining("x-ef-surface: null");
        }

        @Test
        @DisplayName("platform on a security: [] operation stops the module: moderation would need no token")
        void tokenlessPlatform() {
            assertThatThrownBy(() -> Surface.of(operation(true, Map.of(Surface.EXTENSION, "platform"))))
                    .isInstanceOf(InvalidApiContractException.class)
                    .hasMessageContaining("security: []");
        }
    }

    @Nested
    @DisplayName("permits")
    class Permits {

        @Test
        @DisplayName("member opens to any token")
        void member() {
            assertThat(Surface.MEMBER.permits(authorities())).isTrue();
            assertThat(Surface.MEMBER.permits(null)).isTrue();
        }

        @Test
        @DisplayName("console needs ef:surface:full")
        void console() {
            assertThat(Surface.CONSOLE.permits(authorities(Surface.SURFACE_FULL))).isTrue();
            assertThat(Surface.CONSOLE.permits(authorities("SCOPE_openid", Surface.MODERATION_ACT,
                    Surface.PLATFORM_MODERATOR))).isFalse();
            assertThat(Surface.CONSOLE.permits(null)).isFalse();
        }

        @Test
        @DisplayName("platform needs the moderation scope and the role, and ef:surface:full replaces neither")
        void platform() {
            assertThat(Surface.PLATFORM.permits(authorities(Surface.MODERATION_ACT, Surface.PLATFORM_MODERATOR)))
                    .isTrue();
            assertThat(Surface.PLATFORM.permits(authorities(Surface.MODERATION_ACT))).isFalse();
            assertThat(Surface.PLATFORM.permits(authorities(Surface.PLATFORM_MODERATOR))).isFalse();
            assertThat(Surface.PLATFORM.permits(authorities(Surface.SURFACE_FULL, Surface.PLATFORM_MODERATOR)))
                    .isFalse();
        }

        @Test
        @DisplayName("each surface has the tag its metric and log lines carry")
        void tags() {
            assertThat(List.of(Surface.values())).extracting(Surface::tag)
                    .containsExactly("member", "console", "platform");
        }
    }
}
