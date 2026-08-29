package com.ecclesiaflow.platform.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

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
 *       and reject before allocating the raster.</li>
 * </ul>
 *
 * <p>The pipeline is: cap size → sniff &amp; allow type → header pixel-bomb guard
 * → full decode → resample into a fresh raster → native ImageIO re-encode to the
 * policy's output format. The output type is authoritative and sniffed, never
 * the client's declaration.</p>
 */
@Component
public class ImageSanitizer {

    private static final Logger log = LoggerFactory.getLogger(ImageSanitizer.class);

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

        // 3) Decompression-bomb guard: read declared dimensions from the header
        //    WITHOUT decoding pixels, and reject before we ever allocate a raster.
        long pixels = readDeclaredPixelCount(bytes);
        if (pixels > policy.maxInputPixels()) {
            throw new UploadRejectedException(
                    UploadRejectedException.Reason.TOO_MANY_PIXELS,
                    "image declares " + pixels + " pixels, exceeds cap of " + policy.maxInputPixels());
        }

        // 4) Full decode. A null result or any failure means the bytes are not a
        //    usable image (truncated, corrupt, or a spoofed header).
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            throw new UploadRejectedException(
                    UploadRejectedException.Reason.UNREADABLE, "image could not be decoded", e);
        }
        if (source == null) {
            throw new UploadRejectedException(
                    UploadRejectedException.Reason.UNREADABLE, "no image reader could decode the bytes");
        }

        // 5) + 6) Resample into a fresh raster (kills steganography, strips
        //          metadata, neutralises polyglots) and re-encode natively.
        byte[] reencoded = redrawAndEncode(source, policy);
        String outputType = policy.outputType() == ImagePolicy.OutputType.JPEG
                ? MagicBytes.IMAGE_JPEG
                : MagicBytes.IMAGE_PNG;

        log.debug("UPLOAD-SANITIZE: {} ({} bytes) -> {} ({} bytes)",
                detected, bytes.length, outputType, reencoded.length);
        return new SanitizedUpload(reencoded, outputType, reencoded.length);
    }

    /**
     * Reads {@code width × height} from the image header using an
     * {@link ImageReader} without decoding the pixel data, so a crafted file
     * cannot force a huge allocation just to be measured.
     *
     * @return the declared pixel count, or {@code 0} if it cannot be read (the
     *         subsequent full decode will then reject it as UNREADABLE)
     */
    private long readDeclaredPixelCount(byte[] bytes) {
        try (ImageInputStream iis =
                     ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) {
                return 0;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return 0;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                long width = reader.getWidth(0);
                long height = reader.getHeight(0);
                return width * height; // long math — cannot overflow for any real image
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            // Header unreadable — let the full-decode step surface the rejection.
            return 0;
        }
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
    private byte[] redrawAndEncode(BufferedImage source, ImagePolicy policy) {
        int srcW = source.getWidth();
        int srcH = source.getHeight();
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
            g.drawImage(source, 0, 0, targetW, targetH, null);
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

    /**
     * Convenience overload that returns {@link Optional#empty()} instead of
     * throwing, for call sites that prefer to branch on success rather than catch.
     */
    public Optional<SanitizedUpload> trySanitize(byte[] bytes, ImagePolicy policy) {
        try {
            return Optional.of(sanitize(bytes, policy));
        } catch (UploadRejectedException e) {
            return Optional.empty();
        }
    }
}
