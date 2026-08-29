package com.ecclesiaflow.platform.upload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("ImageSanitizer - decode, resample, native re-encode")
class ImageSanitizerTest {

    private final ImageSanitizer sanitizer = new ImageSanitizer();

    // --- helpers -----------------------------------------------------------

    private static byte[] pngBytes(int w, int h, boolean withAlpha) throws Exception {
        int type = withAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage img = new BufferedImage(w, h, type);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(10, 120, 200, withAlpha ? 128 : 255));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, "png", out)).isTrue();
        return out.toByteArray();
    }

    private static byte[] jpegBytes(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 80, 40));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, "jpeg", out)).isTrue();
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    // --- tests -------------------------------------------------------------

    @Test
    @DisplayName("a real PNG sanitizes to a re-encoded JPEG under avatar()")
    void pngToAvatarJpeg() throws Exception {
        byte[] input = pngBytes(100, 100, true);

        SanitizedUpload result = sanitizer.sanitize(input, ImagePolicy.avatar());

        assertThat(result.contentType()).isEqualTo(MagicBytes.IMAGE_JPEG);
        assertThat(result.size()).isEqualTo(result.data().length);
        // Output really is a JPEG (magic FF D8 FF) and decodes back to an image.
        assertThat(MagicBytes.detect(result.data())).contains(MagicBytes.IMAGE_JPEG);
        assertThat(decode(result.data())).isNotNull();
    }

    @Test
    @DisplayName("a real JPEG sanitizes to a re-encoded JPEG under contentImage()")
    void jpegToContentImage() throws Exception {
        byte[] input = jpegBytes(120, 90);

        SanitizedUpload result = sanitizer.sanitize(input, ImagePolicy.contentImage());

        assertThat(result.contentType()).isEqualTo(MagicBytes.IMAGE_JPEG);
        assertThat(MagicBytes.detect(result.data())).contains(MagicBytes.IMAGE_JPEG);
        BufferedImage out = decode(result.data());
        assertThat(out.getWidth()).isEqualTo(120);
        assertThat(out.getHeight()).isEqualTo(90);
    }

    @Test
    @DisplayName("logo() re-encodes to PNG and keeps an alpha channel")
    void logoToPngWithAlpha() throws Exception {
        byte[] input = pngBytes(64, 64, true);

        SanitizedUpload result = sanitizer.sanitize(input, ImagePolicy.logo());

        assertThat(result.contentType()).isEqualTo(MagicBytes.IMAGE_PNG);
        assertThat(MagicBytes.detect(result.data())).contains(MagicBytes.IMAGE_PNG);
        BufferedImage out = decode(result.data());
        assertThat(out.getColorModel().hasAlpha()).isTrue();
    }

    @Test
    @DisplayName("an oversized image resizes so the longest side <= maxDimension")
    void resizeDownscales() throws Exception {
        byte[] input = pngBytes(1000, 400, false); // longest side 1000 > 512

        SanitizedUpload result = sanitizer.sanitize(input, ImagePolicy.avatar());

        BufferedImage out = decode(result.data());
        assertThat(Math.max(out.getWidth(), out.getHeight())).isEqualTo(512);
        // aspect ratio preserved: 1000x400 -> 512x205
        assertThat(out.getWidth()).isEqualTo(512);
        assertThat(out.getHeight()).isEqualTo(205);
    }

    @Test
    @DisplayName("even an already-small image is re-drawn into a fresh raster (bytes change)")
    void alwaysReencodes() throws Exception {
        byte[] input = pngBytes(40, 40, false);

        SanitizedUpload result = sanitizer.sanitize(input, ImagePolicy.logo());

        // Re-encoded output is not a verbatim copy of the input bytes.
        assertThat(result.data()).isNotEqualTo(input);
        assertThat(decode(result.data())).isNotNull();
    }

    @Test
    @DisplayName("raw text bytes 'declared' png are rejected UNSUPPORTED_TYPE (declared type ignored)")
    void textDeclaredAsPngRejected() {
        byte[] text = "just,some,csv\n1,2,3\n".getBytes(StandardCharsets.UTF_8);

        UploadRejectedException ex = catchThrowableOfType(
                () -> sanitizer.sanitize(text, ImagePolicy.avatar()),
                UploadRejectedException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getReason()).isEqualTo(UploadRejectedException.Reason.UNSUPPORTED_TYPE);
    }

    @Test
    @DisplayName("a ZIP (non-image) is rejected UNSUPPORTED_TYPE")
    void zipRejected() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(baos)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("evil.sh"));
            zip.write("rm -rf /".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThatThrownBy(() -> sanitizer.sanitize(baos.toByteArray(), ImagePolicy.avatar()))
                .isInstanceOf(UploadRejectedException.class)
                .extracting(e -> ((UploadRejectedException) e).getReason())
                .isEqualTo(UploadRejectedException.Reason.UNSUPPORTED_TYPE);
    }

    @Test
    @DisplayName("an image larger than the byte cap is rejected TOO_LARGE")
    void oversizeRejected() throws Exception {
        byte[] input = jpegBytes(50, 50);
        // A policy with an absurdly small byte cap forces TOO_LARGE without a huge fixture.
        ImagePolicy tiny = new ImagePolicy(
                8, Set.of(MagicBytes.IMAGE_JPEG), ImagePolicy.DEFAULT_MAX_INPUT_PIXELS,
                512, ImagePolicy.OutputType.JPEG, false);

        assertThatThrownBy(() -> sanitizer.sanitize(input, tiny))
                .isInstanceOf(UploadRejectedException.class)
                .extracting(e -> ((UploadRejectedException) e).getReason())
                .isEqualTo(UploadRejectedException.Reason.TOO_LARGE);
    }

    @Test
    @DisplayName("an image claiming too many pixels is rejected TOO_MANY_PIXELS before decode")
    void pixelBombRejected() throws Exception {
        byte[] input = pngBytes(100, 100, false); // 10 000 pixels
        ImagePolicy stingy = new ImagePolicy(
                2_000_000, Set.of(MagicBytes.IMAGE_PNG), 4L /* max 4 px */,
                512, ImagePolicy.OutputType.JPEG, false);

        assertThatThrownBy(() -> sanitizer.sanitize(input, stingy))
                .isInstanceOf(UploadRejectedException.class)
                .extracting(e -> ((UploadRejectedException) e).getReason())
                .isEqualTo(UploadRejectedException.Reason.TOO_MANY_PIXELS);
    }

    @Test
    @DisplayName("bytes with a valid image header but corrupt body are rejected UNREADABLE")
    void undecodableRejected() {
        // Valid 8-byte PNG signature followed by junk — sniffs as image/png, but
        // no reader can decode it.
        byte[] corrupt = {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

        UploadRejectedException ex = catchThrowableOfType(
                () -> sanitizer.sanitize(corrupt, ImagePolicy.avatar()),
                UploadRejectedException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getReason()).isEqualTo(UploadRejectedException.Reason.UNREADABLE);
    }

    @Test
    @DisplayName("null bytes are rejected UNREADABLE")
    void nullRejected() {
        assertThatThrownBy(() -> sanitizer.sanitize(null, ImagePolicy.avatar()))
                .isInstanceOf(UploadRejectedException.class)
                .extracting(e -> ((UploadRejectedException) e).getReason())
                .isEqualTo(UploadRejectedException.Reason.UNREADABLE);
    }

    @Test
    @DisplayName("trySanitize returns empty instead of throwing on a rejected upload")
    void trySanitizeSwallows() {
        byte[] text = "nope".getBytes(StandardCharsets.UTF_8);
        assertThat(sanitizer.trySanitize(text, ImagePolicy.avatar())).isEmpty();
    }

    @Test
    @DisplayName("the TwelveMonkeys WebP reader is registered on the classpath")
    void webpReaderIsAvailable() {
        assertThat(ImageIO.getImageReadersByFormatName("webp").hasNext())
                .as("imageio-webp plugin must be present so WebP inputs can be decoded")
                .isTrue();
    }
}
