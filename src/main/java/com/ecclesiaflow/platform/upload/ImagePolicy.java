package com.ecclesiaflow.platform.upload;

import java.util.Objects;
import java.util.Set;

/**
 * Limits for one class of image upload. {@code maxInputPixels} caps the source, read from the header
 * before any decode (WebP is capped lower by {@link ImageSanitizer}); {@code maxDimension} caps the
 * output's longest side; {@code preserveTransparency} only applies to PNG.
 */
public record ImagePolicy(
        long maxBytes,
        Set<String> allowedInputTypes,
        long maxInputPixels,
        int maxDimension,
        OutputType outputType,
        boolean preserveTransparency) {

    public enum OutputType {
        JPEG,
        PNG
    }

    private static final long MB = 1024L * 1024L;

    /** Comfortably above a 6000×6000 photo, far below the billions of pixels a crafted bomb claims. */
    public static final long DEFAULT_MAX_INPUT_PIXELS = 40_000_000L;

    private static final Set<String> DECODABLE_IMAGE_TYPES =
            Set.of(MagicBytes.IMAGE_JPEG, MagicBytes.IMAGE_PNG, MagicBytes.IMAGE_WEBP);

    public ImagePolicy {
        Objects.requireNonNull(allowedInputTypes, "allowedInputTypes");
        Objects.requireNonNull(outputType, "outputType");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (maxInputPixels <= 0) {
            throw new IllegalArgumentException("maxInputPixels must be positive");
        }
        if (maxDimension <= 0) {
            throw new IllegalArgumentException("maxDimension must be positive");
        }
        allowedInputTypes = Set.copyOf(allowedInputTypes);
    }

    public static ImagePolicy avatar() {
        return new ImagePolicy(
                2 * MB,
                DECODABLE_IMAGE_TYPES,
                DEFAULT_MAX_INPUT_PIXELS,
                512,
                OutputType.JPEG,
                false);
    }

    public static ImagePolicy contentImage() {
        return new ImagePolicy(
                5 * MB,
                DECODABLE_IMAGE_TYPES,
                DEFAULT_MAX_INPUT_PIXELS,
                1600,
                OutputType.JPEG,
                false);
    }

    public static ImagePolicy logo() {
        return new ImagePolicy(
                2 * MB,
                DECODABLE_IMAGE_TYPES,
                DEFAULT_MAX_INPUT_PIXELS,
                512,
                OutputType.PNG,
                true);
    }
}
