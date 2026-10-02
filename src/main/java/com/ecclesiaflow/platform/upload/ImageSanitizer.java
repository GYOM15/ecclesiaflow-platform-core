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
 * Turns an untrusted user-uploaded image into a safe, freshly re-encoded image
 * ready to store. This is the platform's single defence against a whole family
 * of image-borne attacks, and every module MUST route avatar / logo / content
 * image uploads through it <strong>before</strong> calling
 * {@link com.ecclesiaflow.platform.storage.ObjectStorage#put}.
 *
 * <p>Why a size cap plus the client's {@code Content-Type} is not enough:</p>
 * <ul>
 *   <li><b>Type spoofing / polyglots</b> — a file can be a valid image AND a
 *       valid HTML/JS/ZIP payload at once. We sniff the real type
 *       ({@link MagicBytes}) and, more importantly, re-encode from a decoded
 *       raster, so any trailing/leading non-image payload is discarded.</li>
 *   <li><b>Steganography (LSB)</b> — data hidden in the low bits of pixels
 *       survives a byte copy and even a lossless format change. It does
 *       <em>not</em> survive a resample: redrawing the pixels through bilinear
 *       interpolation into a brand-new raster rewrites every pixel, so we always
 *       redraw — even when the image is already small enough that no resize is
 *       needed.</li>
 *   <li><b>EXIF / GPS / metadata</b> — decode → re-encode keeps only pixels; no
 *       metadata chunk from the source is carried into the output.</li>
 *   <li><b>Decompression bombs</b> — a few KB can claim billions of pixels. We
 *       read the declared dimensions from the header <em>without</em> decoding
 *       and reject before allocating the raster. An accepted source is then
 *       decoded subsampled, never at full resolution, so the raster it allocates
 *       is bounded by the output size rather than by the input's pixel count.</li>
 * </ul>
 *
 * <p>The pipeline is: cap size → sniff &amp; allow type → header pixel-bomb guard
 * → subsampled decode → resample into a fresh raster → native ImageIO re-encode
 * to the policy's output format. The output type is authoritative and sniffed,
 * never the client's declaration.</p>
 *
 * <p>Subsampling bounds one decode, not a burst of them: every decode from the
 * header guard to the re-encode holds one of a fixed number of slots, waits a
 * bounded time for one, and is refused with
 * {@link ImageDecodeCapacityExceededException} when none frees up. The slots belong
 * to the instance, so the auto-configured bean is the one to share.</p>
 */
public class ImageSanitizer {

    /**
     * The WebP decoder holds about 24 bytes per source pixel whatever the
     * subsampling (4 MP measured at about 90 MB of heap), so WebP sources get a
     * lower cap than the policy's.
     */
    static final long WEBP_MAX_INPUT_PIXELS = 4_000_000L;

    /**
     * One decode at a time. A WebP at its cap holds about 90 MB, so two would take
     * nearly two thirds of the 288 MB heap a 384 MB container gets; with one CPU per
     * container, a second slot would add that risk without adding throughput.
     */
    public static final int DEFAULT_MAX_CONCURRENT_DECODES = 1;

    /**
     * Covers a few large decodes queued ahead, and keeps the answer well inside the
     * 20 s the web front end gives an image upload.
     */
    public static final Duration DEFAULT_DECODE_WAIT = Duration.ofSeconds(5);

    private final Semaphore decodeSlots;
    private final int maxConcurrentDecodes;
    private final Duration decodeWait;

    /** A sanitizer with the default decode bound and wait. */
    public ImageSanitizer() {
        this(DEFAULT_MAX_CONCURRENT_DECODES, DEFAULT_DECODE_WAIT);
    }

    /**
     * @param maxConcurrentDecodes how many decodes may run at once; at least 1
     * @param decodeWait           how long an upload waits for a slot before it is
     *                             refused; zero refuses at once
     */
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
        // Fair, so an upload that has waited is not overtaken by one that just arrived.
        this.decodeSlots = new Semaphore(maxConcurrentDecodes, true);
    }

    /**
     * Decodes, sanitizes and re-encodes an image upload per {@code policy}.
     *
     * @param bytes  the raw upload bytes exactly as received (declared type is
     *               ignored — the real type is sniffed here)
     * @param policy the limits and target format for this upload class
     * @return a {@link SanitizedUpload} holding freshly re-encoded bytes and the
     *         authoritative output content type
     * @throws UploadRejectedException if the upload is too large, of an
     *         unsupported type, claims too many pixels, or cannot be decoded
     * @throws ImageDecodeCapacityExceededException if no decode slot freed up
     *         within the allowed wait
     * @throws IllegalStateException   if the JVM has no writer for the target
     *         format (a server misconfiguration, not the uploader's fault)
     */
    public SanitizedUpload sanitize(byte[] bytes, ImagePolicy policy) {
        if (bytes == null) {
            throw new UploadRejectedException(
                    UploadRejectedException.Reason.UNREADABLE, "no upload bytes");
        }
        // 1) Size cap — cheapest check first, and a guard before any decoding work.
        if (bytes.length > policy.maxBytes()) {
            throw new UploadRejectedException(
                    UploadRejectedException.Reason.TOO_LARGE,
                    "upload is " + bytes.length + " bytes, exceeds cap of " + policy.maxBytes());
        }

        // 2) Sniff the REAL type and enforce the allow-list. Never trust the client.
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
            // 3) + 4) Pixel-bomb guard on the header, then a subsampled decode.
            DecodedImage source = decodeWithinLimits(bytes, maxPixels, policy.maxDimension());

            // 5) + 6) Resample into a fresh raster (kills steganography, strips
            //          metadata, neutralises polyglots) and re-encode natively.
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
                    "interrupted while waiting for an image decode slot", e);
        }
        throw new ImageDecodeCapacityExceededException("all " + maxConcurrentDecodes
                + " image decode slots stayed busy for " + decodeWait.toMillis() + " ms");
    }

    /**
     * Reads the declared dimensions from the header, rejects a source over
     * {@code maxPixels} before any pixel is decoded, then decodes it subsampled
     * for an output of at most {@code maxDimension}.
     *
     * @throws UploadRejectedException {@code TOO_MANY_PIXELS} over the cap,
     *         {@code UNREADABLE} when no reader accepts the bytes or decoding fails
     */
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
     * The smallest step that brings the decoded longest side to at most twice
     * {@code maxDimension}: the raster is then bounded by the output size, and it
     * stays above {@code maxDimension} so the redraw never upscales.
     */
    static int subsamplingStep(int width, int height, int maxDimension) {
        long window = 2L * maxDimension;
        return (int) Math.max(1L, (Math.max(width, height) + window - 1) / window);
    }

    /**
     * Redraws {@code source} into a brand-new {@link BufferedImage}, downscaled so
     * the longest side is at most {@code policy.maxDimension()}, then re-encodes it
     * with a native ImageIO writer to the policy's output format.
     *
     * <p>The redraw is unconditional: even at scale 1.0 we resample every pixel
     * into a fresh raster of the target type, which is precisely what destroys
     * LSB steganography and discards any source metadata.</p>
     */
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
                // Opaque target (JPEG, or PNG without transparency): flatten any
                // source alpha onto solid white so transparent areas don't render
                // black in a format that has no alpha channel.
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
                // Native JPEG/PNG writers ship with every JRE; absence is a broken
                // runtime, not a bad upload.
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
     * Convenience overload that returns {@link Optional#empty()} instead of
     * throwing an {@link UploadRejectedException}, for call sites that prefer to
     * branch on success rather than catch. A full decoder still throws: the upload
     * was not judged, so an empty answer would misreport it as rejected.
     */
    public Optional<SanitizedUpload> trySanitize(byte[] bytes, ImagePolicy policy) {
        try {
            return Optional.of(sanitize(bytes, policy));
        } catch (UploadRejectedException e) {
            return Optional.empty();
        }
    }
}
