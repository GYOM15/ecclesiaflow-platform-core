package com.ecclesiaflow.platform.upload;

import java.util.Objects;
import java.util.Set;

/**
 * Declarative limits for one class of image upload, consumed by
 * {@link ImageSanitizer}. Each call site picks a policy that fits its use
 * (an avatar, an inline content image, a transparent logo) rather than sprinkling
 * magic numbers through controllers.
 *
 * @param maxBytes             hard cap on the raw upload size; larger →
 *                             {@link UploadRejectedException.Reason#TOO_LARGE}
 * @param allowedInputTypes    the sniffed media types accepted as input (declared
 *                             types are never consulted); anything else →
 *                             {@link UploadRejectedException.Reason#UNSUPPORTED_TYPE}
 * @param maxInputPixels       cap on {@code width × height} of the SOURCE image,
 *                             enforced before full decode as the decompression-bomb
 *                             guard; larger →
 *                             {@link UploadRejectedException.Reason#TOO_MANY_PIXELS}
 * @param maxDimension         the longest side of the OUTPUT image; the sanitizer
 *                             downscales (preserving aspect ratio) so neither side
 *                             exceeds this
 * @param outputType           the format the sanitizer always re-encodes TO
 * @param preserveTransparency for {@link OutputType#PNG}, whether to keep an alpha
 *                             channel; ignored for JPEG (which cannot carry alpha)
 */
public record ImagePolicy(
        long maxBytes,
        Set<String> allowedInputTypes,
        long maxInputPixels,
        int maxDimension,
        OutputType outputType,
        boolean preserveTransparency) {

    /** Output format the sanitizer re-encodes to. Both are written by native ImageIO writers. */
    public enum OutputType {
        JPEG,
        PNG
    }

    private static final long MB = 1024L * 1024L;

    /**
     * Default decompression-bomb ceiling: 40 megapixels of source raster. Comfortably
     * above a 6000×6000 photo, far below the billions of pixels a crafted bomb claims.
     */
    public static final long DEFAULT_MAX_INPUT_PIXELS = 40_000_000L;

    /** The image inputs the platform can decode (writing is always native JPEG/PNG). */
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
        // Defensive, immutable copy — a policy is a shared, long-lived constant.
        allowedInputTypes = Set.copyOf(allowedInputTypes);
    }

    /**
     * Member/user avatar: small square-ish photo. 2 MB in, downscaled to 512 px,
     * re-encoded to opaque JPEG (avatars never need transparency). Accepts JPEG,
     * PNG or WebP input.
     */
    public static ImagePolicy avatar() {
        return new ImagePolicy(
                2 * MB,
                DECODABLE_IMAGE_TYPES,
                DEFAULT_MAX_INPUT_PIXELS,
                512,
                OutputType.JPEG,
                false);
    }

    /**
     * Inline content image (announcement / event / group banner). 5 MB in,
     * downscaled to 1600 px, re-encoded to opaque JPEG. Accepts JPEG, PNG or WebP.
     */
    public static ImagePolicy contentImage() {
        return new ImagePolicy(
                5 * MB,
                DECODABLE_IMAGE_TYPES,
                DEFAULT_MAX_INPUT_PIXELS,
                1600,
                OutputType.JPEG,
                false);
    }

    /**
     * Church logo: transparency matters, so 2 MB in, downscaled to 512 px, and
     * re-encoded to PNG with its alpha channel preserved. Accepts JPEG, PNG or WebP.
     */
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
