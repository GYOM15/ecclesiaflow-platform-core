package com.ecclesiaflow.platform.upload;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Re-encodes an untrusted image upload; every module routes image uploads through it before
 * {@link com.ecclesiaflow.platform.storage.ObjectStorage#put}. The real type is sniffed, never taken from
 * the client. The pixel count is checked from the header before any raster is allocated (decompression
 * bombs), and the source is decoded subsampled so the raster is bounded by the output size. Every image
 * is redrawn into a fresh raster, even when no resize is needed: that drops polyglot payloads and
 * metadata, and the bilinear resample rewrites the low bits that carry LSB steganography.
 *
 * <p>Each decode holds one of a fixed number of slots, owned by the instance (share the auto-configured
 * bean), and is refused with {@link ImageDecodeCapacityExceededException} when none frees up in time.</p>
 */
public class ImageSanitizer {

    /** The WebP decoder holds about 24 bytes per source pixel whatever the subsampling (4 MP: about 90 MB). */
    static final long WEBP_MAX_INPUT_PIXELS = 4_000_000L;

    /**
     * A WebP at its cap holds about 90 MB, so two decodes would take nearly two thirds of the 288 MB heap of a
     * 384 MB container; with one CPU per container a second slot adds risk, not throughput.
     */
    public static final int DEFAULT_MAX_CONCURRENT_DECODES = 1;

    /** Covers a few large decodes queued ahead and stays well inside the 20 s the web front end allows. */
    public static final Duration DEFAULT_DECODE_WAIT = Duration.ofSeconds(5);

    private final Semaphore decodeSlots;
    private final int maxConcurrentDecodes;
    private final Duration decodeWait;
    private final long retryAfterSeconds;

    public ImageSanitizer() {
        this(DEFAULT_MAX_CONCURRENT_DECODES, DEFAULT_DECODE_WAIT);
    }

    /** A zero {@code decodeWait} refuses at once when every slot is busy. */
    public ImageSanitizer(int maxConcurrentDecodes, Duration decodeWait) {
        if (maxConcurrentDecodes < 1) {
            throw new IllegalArgumentException(
                    "maxConcurrentDecodes must be at least 1, was " + maxConcurrentDecodes);
        }
        if (decodeWait == null || decodeWait.isNegative()) {
            throw new IllegalArgumentException("decodeWait must be zero or positive, was " + decodeWait);
        }
        this.maxConcurrentDecodes = maxConcurrentDecodes;
        this.decodeWait = decodeWait;
        // A refused upload has already waited this long; asking for less invites the same refusal.
        this.retryAfterSeconds = Math.max(1L, (decodeWait.toMillis() + 999) / 1000);
        // Fair, so an upload that has waited is not overtaken by one that just arrived.
        this.decodeSlots = new Semaphore(maxConcurrentDecodes, true);
    }

    /**
     * Throws {@link UploadRejectedException} for an upload that is too large, of the wrong type, has too
     * many pixels or cannot be decoded, and {@link ImageDecodeCapacityExceededException} when no decode slot
     * frees up in time.
     */
    public SanitizedUpload sanitize(byte[] bytes, ImagePolicy policy) {
        if (bytes == null) {
            throw new UploadRejectedException(
                    UploadRejectedException.Reason.UNREADABLE, "no upload bytes");
        }
        if (bytes.length > policy.maxBytes()) {
            throw new UploadRejectedException(
                    UploadRejectedException.Reason.TOO_LARGE,
                    "upload is " + bytes.length + " bytes, exceeds cap of " + policy.maxBytes());
        }

        String detected = MagicBytes.detect(bytes).orElse(null);
        if (detected == null || !policy.allowedInputTypes().contains(detected)) {
            throw new UploadRejectedException(
                    UploadRejectedException.Reason.UNSUPPORTED_TYPE,
                    "detected media type " + detected + " is not an accepted image input");
        }

        long maxPixels = MagicBytes.IMAGE_WEBP.equals(detected)
                ? Math.min(policy.maxInputPixels(), WEBP_MAX_INPUT_PIXELS)
                : policy.maxInputPixels();
        // The slot is held until the re-encode: the decoded source stays live until then.
        acquireDecodeSlot();
        try {
            DecodedImage source = decodeWithinLimits(bytes, maxPixels, policy.maxDimension());

            byte[] reencoded = redrawAndEncode(source, policy);
            String outputType = policy.outputType() == ImagePolicy.OutputType.JPEG
                    ? MagicBytes.IMAGE_JPEG
                    : MagicBytes.IMAGE_PNG;
            return new SanitizedUpload(reencoded, outputType, reencoded.length);
        } finally {
            decodeSlots.release();
        }
    }

    private void acquireDecodeSlot() {
        try {
            if (decodeSlots.tryAcquire(decodeWait.toNanos(), TimeUnit.NANOSECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageDecodeCapacityExceededException(
                    "interrupted while waiting for an image decode slot", retryAfterSeconds, e);
        }
        throw new ImageDecodeCapacityExceededException("all " + maxConcurrentDecodes
                + " image decode slots stayed busy for " + decodeWait.toMillis() + " ms", retryAfterSeconds);
    }

    private DecodedImage decodeWithinLimits(byte[] bytes, long maxPixels, int maxDimension) {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new UploadRejectedException(
                        UploadRejectedException.Reason.UNREADABLE, "no image reader could decode the bytes");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (pixels > maxPixels) {
                    throw new UploadRejectedException(
                            UploadRejectedException.Reason.TOO_MANY_PIXELS,
                            "image declares " + pixels + " pixels, exceeds cap of " + maxPixels);
                }
                int step = subsamplingStep(width, height, maxDimension);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return new DecodedImage(reader.read(0, param), width, height);
            } finally {
                reader.dispose();
            }
        } catch (UploadRejectedException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new UploadRejectedException(
                    UploadRejectedException.Reason.UNREADABLE, "image could not be decoded", e);
        }
    }

    /**
     * Brings the decoded longest side to at most twice {@code maxDimension}, so the raster is bounded by the
     * output size, yet keeps it above {@code maxDimension} so the redraw never upscales.
     */
    static int subsamplingStep(int width, int height, int maxDimension) {
        long window = 2L * maxDimension;
        return (int) Math.max(1L, (Math.max(width, height) + window - 1) / window);
    }

    private byte[] redrawAndEncode(DecodedImage source, ImagePolicy policy) {
        // Sized from the declared dimensions, not the subsampled raster, so the
        // output is exactly what a full-resolution decode would have produced.
        int srcW = source.width();
        int srcH = source.height();
        int longest = Math.max(srcW, srcH);
        double scale = longest > policy.maxDimension()
                ? (double) policy.maxDimension() / longest
                : 1.0;
        int targetW = Math.max(1, (int) Math.round(srcW * scale));
        int targetH = Math.max(1, (int) Math.round(srcH * scale));

        boolean argb = policy.outputType() == ImagePolicy.OutputType.PNG
                && policy.preserveTransparency();
        int imageType = argb ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;

        BufferedImage target = new BufferedImage(targetW, targetH, imageType);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            if (!argb) {
                // No alpha channel in the output: flatten onto white so transparent areas do not render black.
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, targetW, targetH);
            }
            g.drawImage(source.image(), 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }

        String formatName = policy.outputType() == ImagePolicy.OutputType.JPEG ? "jpeg" : "png";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(target, formatName, out)) {
                // Native JPEG/PNG writers ship with every JRE: a missing one is a broken runtime, not a bad upload.
                throw new IllegalStateException("no ImageIO writer available for " + formatName);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to re-encode sanitized image as " + formatName, e);
        }
        return out.toByteArray();
    }

    /** A decoded, possibly subsampled raster with the source's declared size. */
    private record DecodedImage(BufferedImage image, int width, int height) {
    }

    /**
     * Empty when the upload is rejected. A capacity refusal still throws: the upload was not judged, so an
     * empty answer would misreport it as rejected.
     */
    public Optional<SanitizedUpload> trySanitize(byte[] bytes, ImagePolicy policy) {
        try {
            return Optional.of(sanitize(bytes, policy));
        } catch (UploadRejectedException e) {
            return Optional.empty();
        }
    }
}
