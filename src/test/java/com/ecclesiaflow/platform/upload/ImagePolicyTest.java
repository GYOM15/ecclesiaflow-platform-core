package com.ecclesiaflow.platform.upload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A policy is a shared constant: a nonsensical bound must fail where it is declared. */
class ImagePolicyTest {

    private static final Set<String> JPEG_ONLY = Set.of(MagicBytes.IMAGE_JPEG);

    @Test
    @DisplayName("a size, pixel or dimension bound of zero or less is refused")
    void refusesNonPositiveBounds() {
        assertThatThrownBy(() -> new ImagePolicy(0, JPEG_ONLY, 1, 1, ImagePolicy.OutputType.JPEG, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBytes");
        assertThatThrownBy(() -> new ImagePolicy(1, JPEG_ONLY, 0, 1, ImagePolicy.OutputType.JPEG, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxInputPixels");
        assertThatThrownBy(() -> new ImagePolicy(1, JPEG_ONLY, 1, -1, ImagePolicy.OutputType.JPEG, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDimension");
    }

    @Test
    @DisplayName("the accepted types cannot be changed after the policy is built")
    void copiesTheAcceptedTypes() {
        Set<String> types = new HashSet<>(JPEG_ONLY);
        ImagePolicy policy = new ImagePolicy(1, types, 1, 1, ImagePolicy.OutputType.JPEG, false);

        types.add(MagicBytes.IMAGE_GIF);

        assertThat(policy.allowedInputTypes()).containsExactly(MagicBytes.IMAGE_JPEG);
        assertThatThrownBy(() -> policy.allowedInputTypes().add(MagicBytes.IMAGE_PNG))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
