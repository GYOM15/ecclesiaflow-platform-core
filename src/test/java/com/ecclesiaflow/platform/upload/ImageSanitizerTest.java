package com.ecclesiaflow.platform.upload;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    @DisplayName("a sniffed type no image reader handles is rejected UNREADABLE")
    void noReaderRejected() {
        byte[] text = "a;b\n1;2\n".getBytes(StandardCharsets.UTF_8);
        ImagePolicy acceptsText = new ImagePolicy(
                1024, Set.of(MagicBytes.TEXT_CSV), ImagePolicy.DEFAULT_MAX_INPUT_PIXELS,
                512, ImagePolicy.OutputType.JPEG, false);

        UploadRejectedException ex = catchThrowableOfType(
                () -> sanitizer.sanitize(text, acceptsText), UploadRejectedException.class);

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

    @Nested
    @DisplayName("bounded decode of large sources")
    class BoundedDecode {

        private static final long MB = 1024L * 1024L;

        /** 6000 x 6666 = 39 996 000 px, just under the 40 MP default cap. */
        private static final int BIG_W = 6000;
        private static final int BIG_H = 6666;

        /**
         * Below the 108-120 MB of a single full-resolution raster of these
         * sources; the JPEG encoder alone churns about 32 MB of row buffers for a
         * 1600 px output, which counts here although it is never live at once.
         */
        private static final long CONTENT_IMAGE_BUDGET = 96 * MB;

        @Test
        @DisplayName("a 40 MP avatar never allocates a full-resolution raster (3 bytes/px = 120 MB)")
        void avatarDecodeIsBounded() throws Exception {
            byte[] input = streamedGradientPng(BIG_W, BIG_H);
            assertThat(input.length).as("fixture fits the avatar byte cap").isLessThan(2 * (int) MB);

            long allocated = allocatedDuring(() -> sanitizer.sanitize(input, ImagePolicy.avatar()));

            assertThat(allocated).isLessThan(16 * MB);
        }

        @Test
        @DisplayName("a 40 MP content image allocates well under a full-resolution raster")
        void contentImageDecodeIsBounded() throws Exception {
            byte[] input = streamedGradientPng(BIG_W, BIG_H);

            long allocated = allocatedDuring(() -> sanitizer.sanitize(input, ImagePolicy.contentImage()));

            assertThat(allocated).isLessThan(CONTENT_IMAGE_BUDGET);
        }

        @Test
        @DisplayName("a 36 MP square content image (longest side under 4x the output) is still subsampled")
        void contentImageJustUnderFourTimesIsBounded() throws Exception {
            byte[] input = streamedGradientPng(6000, 6000);

            long allocated = allocatedDuring(() -> sanitizer.sanitize(input, ImagePolicy.contentImage()));

            assertThat(allocated).isLessThan(CONTENT_IMAGE_BUDGET);
        }

        @Test
        @DisplayName("the decoded longest side stays within (maxDimension, 2 x maxDimension]")
        void subsamplingStepBounds() {
            assertThat(ImageSanitizer.subsamplingStep(100, 100, 512)).isEqualTo(1);
            assertThat(ImageSanitizer.subsamplingStep(1024, 10, 512)).isEqualTo(1);
            assertThat(ImageSanitizer.subsamplingStep(10, 1025, 512)).isEqualTo(2);
            assertThat(ImageSanitizer.subsamplingStep(6000, 6000, 1600)).isEqualTo(2);
            assertThat(ImageSanitizer.subsamplingStep(6000, 6666, 512)).isEqualTo(7);
            assertThat(ImageSanitizer.subsamplingStep(6000, 6666, Integer.MAX_VALUE)).isEqualTo(1);
            for (int longest = 1; longest <= 20_000; longest += 37) {
                int step = ImageSanitizer.subsamplingStep(longest, 1, 1600);
                int decoded = (longest + step - 1) / step;
                assertThat(decoded).isLessThanOrEqualTo(3200);
                if (longest > 1600) {
                    assertThat(decoded).isGreaterThan(1600);
                }
            }
        }

        @Test
        @DisplayName("output dimensions of a large source are those of a full-resolution decode")
        void outputDimensionsUnchanged() throws Exception {
            byte[] input = streamedGradientPng(BIG_W, BIG_H);

            BufferedImage out = decode(sanitizer.sanitize(input, ImagePolicy.avatar()).data());

            // 6000x6666 scaled by 512/6666 -> round(460.84) x 512
            assertThat(out.getWidth()).isEqualTo(461);
            assertThat(out.getHeight()).isEqualTo(512);
        }

        @Test
        @DisplayName("output pixels match a full-resolution decode + redraw")
        void outputMatchesFullDecode() throws Exception {
            byte[] input = streamedGradientPng(3000, 2000);
            BufferedImage reference = fullDecodeRedraw(input, 512, 341);

            BufferedImage out = decode(sanitizer.sanitize(input, ImagePolicy.avatar()).data());

            assertThat(out.getWidth()).isEqualTo(512);
            assertThat(out.getHeight()).isEqualTo(341);
            assertThat(meanAbsoluteDifference(out, reference)).isLessThan(2.0);
        }

        @Test
        @DisplayName("the pixel cap is still enforced from the header before any decode")
        void pixelCapStillEnforced() throws Exception {
            byte[] input = streamedGradientPng(BIG_W, BIG_H);
            ImagePolicy capped = new ImagePolicy(
                    2 * MB, Set.of(MagicBytes.IMAGE_PNG), 1_000_000L, 512, ImagePolicy.OutputType.JPEG, false);

            long allocated = allocatedDuring(() -> assertThatThrownBy(() -> sanitizer.sanitize(input, capped))
                    .isInstanceOf(UploadRejectedException.class)
                    .extracting(e -> ((UploadRejectedException) e).getReason())
                    .isEqualTo(UploadRejectedException.Reason.TOO_MANY_PIXELS));

            assertThat(allocated).isLessThan(8 * MB);
        }

        private long allocatedDuring(Runnable action) {
            com.sun.management.ThreadMXBean threads =
                    (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
            long before = threads.getCurrentThreadAllocatedBytes();
            action.run();
            return threads.getCurrentThreadAllocatedBytes() - before;
        }
    }

    @Nested
    @DisplayName("concurrent decodes")
    class ConcurrentDecodes {

        private static final Duration LONG_ENOUGH_TO_SHOW_A_LEAK = Duration.ofMillis(500);
        private static final long MB = 1024L * 1024L;

        private GatedImageDecoder decoder;
        private ExecutorService pool;

        @BeforeEach
        void installGate() {
            decoder = GatedImageDecoder.install();
            pool = Executors.newCachedThreadPool();
        }

        @AfterEach
        void removeGate() {
            decoder.close();
            pool.shutdownNow();
        }

        @Test
        @DisplayName("a burst of uploads never decodes more images at once than the default bound")
        void aBurstStaysWithinTheDefaultBound() throws Exception {
            ImageSanitizer defaults = new ImageSanitizer();

            List<Future<SanitizedUpload>> uploads = submit(6, defaults);

            assertThat(decoder.awaitInFlight(1, Duration.ofSeconds(5))).isTrue();
            assertThat(decoder.awaitInFlight(2, LONG_ENOUGH_TO_SHOW_A_LEAK))
                    .as("a second decode started while the first still held its raster")
                    .isFalse();
            decoder.open();
            assertThat(completed(uploads)).allMatch(upload -> upload.contentType().equals(MagicBytes.IMAGE_JPEG));
            assertThat(decoder.maxInFlight()).isEqualTo(1);
        }

        @Test
        @DisplayName("a configured bound is filled, and never exceeded")
        void aConfiguredBoundIsHeldExactly() throws Exception {
            ImageSanitizer three = new ImageSanitizer(3, Duration.ofSeconds(10));

            List<Future<SanitizedUpload>> uploads = submit(8, three);

            assertThat(decoder.awaitInFlight(3, Duration.ofSeconds(5))).isTrue();
            assertThat(decoder.awaitInFlight(4, LONG_ENOUGH_TO_SHOW_A_LEAK)).isFalse();
            decoder.open();
            assertThat(completed(uploads)).hasSize(8);
            assertThat(decoder.maxInFlight()).isEqualTo(3);
        }

        @Test
        @DisplayName("an upload that finds every slot busy for the whole wait is refused, never decoded")
        void aFullDecoderRefusesAfterTheWait() throws Exception {
            ImageSanitizer one = new ImageSanitizer(1, Duration.ofMillis(200));
            byte[] image = decoder.markedPng();
            Future<SanitizedUpload> holder = pool.submit(() -> one.sanitize(image, ImagePolicy.avatar()));
            assertThat(decoder.awaitInFlight(1, Duration.ofSeconds(5))).isTrue();

            Future<Long> late = pool.submit(() -> {
                long start = System.nanoTime();
                assertThatThrownBy(() -> one.sanitize(image, ImagePolicy.avatar()))
                        .isInstanceOf(ImageDecodeCapacityExceededException.class)
                        .isNotInstanceOf(UploadRejectedException.class)
                        .hasMessage("all 1 image decode slots stayed busy for 200 ms");
                return Duration.ofNanos(System.nanoTime() - start).toMillis();
            });

            assertThat(late.get(5, TimeUnit.SECONDS)).as("waited the configured time first").isGreaterThanOrEqualTo(200L);
            decoder.open();
            assertThat(holder.get(5, TimeUnit.SECONDS).contentType()).isEqualTo(MagicBytes.IMAGE_JPEG);
            assertThat(decoder.maxInFlight()).isEqualTo(1);
        }

        @Test
        @DisplayName("a zero wait refuses at once while the slot is taken")
        void aZeroWaitRefusesAtOnce() throws Exception {
            ImageSanitizer impatient = new ImageSanitizer(1, Duration.ZERO);
            List<Future<SanitizedUpload>> holder = submit(1, impatient);
            assertThat(decoder.awaitInFlight(1, Duration.ofSeconds(5))).isTrue();

            assertThatThrownBy(() -> impatient.sanitize(pngBytes(10, 10, false), ImagePolicy.avatar()))
                    .isInstanceOf(ImageDecodeCapacityExceededException.class);

            decoder.open();
            assertThat(completed(holder)).hasSize(1);
        }

        @Test
        @DisplayName("the slot is given back whether the decode succeeds or the upload is rejected")
        void theSlotIsGivenBackWhateverTheOutcome() throws Exception {
            ImageSanitizer impatient = new ImageSanitizer(1, Duration.ZERO);
            byte[] corrupt = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5, 6, 7, 8};
            ImagePolicy fiftyPixels = new ImagePolicy(
                    MB, Set.of(MagicBytes.IMAGE_PNG), 50L, 512, ImagePolicy.OutputType.JPEG, false);

            assertRejected(() -> impatient.sanitize(corrupt, ImagePolicy.avatar()),
                    UploadRejectedException.Reason.UNREADABLE);
            assertRejected(() -> impatient.sanitize(pngBytes(10, 10, false), fiftyPixels),
                    UploadRejectedException.Reason.TOO_MANY_PIXELS);

            assertThat(impatient.sanitize(pngBytes(10, 10, false), ImagePolicy.avatar()).contentType())
                    .isEqualTo(MagicBytes.IMAGE_JPEG);
        }

        @Test
        @DisplayName("an upload refused on its size or type never waits for a slot")
        void cheapRejectionsTakeNoSlot() throws Exception {
            ImageSanitizer impatient = new ImageSanitizer(1, Duration.ZERO);
            List<Future<SanitizedUpload>> holder = submit(1, impatient);
            assertThat(decoder.awaitInFlight(1, Duration.ofSeconds(5))).isTrue();

            assertRejected(() -> impatient.sanitize(new byte[3 * (int) MB], ImagePolicy.avatar()),
                    UploadRejectedException.Reason.TOO_LARGE);
            assertRejected(() -> impatient.sanitize("not an image".getBytes(StandardCharsets.US_ASCII),
                    ImagePolicy.avatar()), UploadRejectedException.Reason.UNSUPPORTED_TYPE);

            decoder.open();
            assertThat(completed(holder)).hasSize(1);
        }

        @Test
        @DisplayName("an upload interrupted while it waits is refused and keeps its interrupt")
        void anInterruptedWaitIsRefused() throws Exception {
            ImageSanitizer patient = new ImageSanitizer(1, Duration.ofSeconds(30));
            List<Future<SanitizedUpload>> holder = submit(1, patient);
            assertThat(decoder.awaitInFlight(1, Duration.ofSeconds(5))).isTrue();
            AtomicReference<Throwable> refusal = new AtomicReference<>();
            AtomicBoolean interruptKept = new AtomicBoolean();

            Thread waiter = new Thread(() -> {
                try {
                    patient.sanitize(pngBytes(10, 10, false), ImagePolicy.avatar());
                } catch (Throwable e) {
                    refusal.set(e);
                    interruptKept.set(Thread.currentThread().isInterrupted());
                }
            });
            waiter.start();
            waiter.interrupt();
            waiter.join(5_000);

            assertThat(refusal.get())
                    .isInstanceOf(ImageDecodeCapacityExceededException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(interruptKept).isTrue();
            decoder.open();
            assertThat(completed(holder)).hasSize(1);
        }

        @Test
        @DisplayName("trySanitize still throws when no slot frees up: the upload was not judged")
        void trySanitizeDoesNotHideAFullDecoder() throws Exception {
            ImageSanitizer impatient = new ImageSanitizer(1, Duration.ZERO);
            List<Future<SanitizedUpload>> holder = submit(1, impatient);
            assertThat(decoder.awaitInFlight(1, Duration.ofSeconds(5))).isTrue();

            assertThatThrownBy(() -> impatient.trySanitize(pngBytes(10, 10, false), ImagePolicy.avatar()))
                    .isInstanceOf(ImageDecodeCapacityExceededException.class);

            decoder.open();
            assertThat(completed(holder)).hasSize(1);
        }

        @Test
        @DisplayName("a bound below one, or a negative or missing wait, is refused at construction")
        void refusesAnUnusableBound() {
            assertThatThrownBy(() -> new ImageSanitizer(0, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxConcurrentDecodes");
            assertThatThrownBy(() -> new ImageSanitizer(1, Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("decodeWait");
            assertThatThrownBy(() -> new ImageSanitizer(1, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("decodeWait");
        }

        private void assertRejected(ThrowingCallable upload, UploadRejectedException.Reason reason) {
            assertThatThrownBy(upload)
                    .isInstanceOf(UploadRejectedException.class)
                    .extracting(e -> ((UploadRejectedException) e).getReason())
                    .isEqualTo(reason);
        }

        private List<Future<SanitizedUpload>> submit(int count, ImageSanitizer target) {
            byte[] image = decoder.markedPng();
            List<Future<SanitizedUpload>> uploads = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                uploads.add(pool.submit(() -> target.sanitize(image, ImagePolicy.avatar())));
            }
            return uploads;
        }

        private static List<SanitizedUpload> completed(List<Future<SanitizedUpload>> uploads) throws Exception {
            List<SanitizedUpload> results = new ArrayList<>();
            for (Future<SanitizedUpload> upload : uploads) {
                results.add(upload.get(10, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    @Nested
    @DisplayName("WebP sources")
    class WebpSources {

        /** An 8 x 8 lossy WebP written by cwebp 1.x ("VP8 " chunk, no alpha, no metadata). */
        private static final byte[] TINY_WEBP = {
                0x52, 0x49, 0x46, 0x46, 0x44, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20,
                0x38, 0x00, 0x00, 0x00, (byte) 0xD0, 0x01, 0x00, (byte) 0x9D, 0x01, 0x2A, 0x08, 0x00, 0x08, 0x00,
                0x02, 0x00, 0x34, 0x25, (byte) 0xA8, 0x02, 0x74, 0x00, (byte) 0xF4, (byte) 0x87, (byte) 0xFB, 0x6C,
                0x00, 0x00, (byte) 0xFE, (byte) 0x98, 0x7B, (byte) 0xDC, 0x73, (byte) 0x90, 0x1F, (byte) 0xB3, 0x6E,
                (byte) 0x85, (byte) 0xD9, 0x5A, 0x7D, 0x7F, (byte) 0xFE, 0x3E, 0x38, (byte) 0xE6, (byte) 0xFF,
                (byte) 0xFC, (byte) 0xB4, 0x38, (byte) 0xFF, (byte) 0xFC, 0x7C, 0x71, (byte) 0xCD, (byte) 0xF6,
                (byte) 0xEC, 0x00, 0x00, 0x00};

        /** Offsets of the 14-bit width and height in a simple-format VP8 frame header. */
        private static final int WIDTH_OFFSET = 26;
        private static final int HEIGHT_OFFSET = 28;

        @Test
        @DisplayName("a real lossy WebP sanitizes to a re-encoded JPEG")
        void webpDecodes() throws Exception {
            SanitizedUpload result = sanitizer.sanitize(TINY_WEBP, ImagePolicy.avatar());

            assertThat(result.contentType()).isEqualTo(MagicBytes.IMAGE_JPEG);
            BufferedImage out = decode(result.data());
            assertThat(out.getWidth()).isEqualTo(8);
            assertThat(out.getHeight()).isEqualTo(8);
        }

        @Test
        @DisplayName("a WebP declaring 16 MP is refused from its header, under the general 40 MP cap")
        void largeWebpRefusedBeforeDecode() {
            byte[] declared16Mp = withDeclaredSize(TINY_WEBP, 4000, 4000);

            UploadRejectedException ex = catchThrowableOfType(
                    () -> sanitizer.sanitize(declared16Mp, ImagePolicy.avatar()),
                    UploadRejectedException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getReason()).isEqualTo(UploadRejectedException.Reason.TOO_MANY_PIXELS);
        }

        @Test
        @DisplayName("a WebP at the WebP cap is still decoded")
        void webpAtCapAccepted() {
            int side = (int) Math.sqrt(ImageSanitizer.WEBP_MAX_INPUT_PIXELS);
            byte[] atCap = withDeclaredSize(TINY_WEBP, side, side);

            // The body only holds 8 x 8 pixels: whatever the decoder makes of it,
            // the header cap must not be what refuses it.
            UploadRejectedException ex = catchThrowableOfType(
                    () -> sanitizer.sanitize(atCap, ImagePolicy.avatar()),
                    UploadRejectedException.class);

            if (ex != null) {
                assertThat(ex.getReason()).isNotEqualTo(UploadRejectedException.Reason.TOO_MANY_PIXELS);
            }
        }

        private static byte[] withDeclaredSize(byte[] webp, int width, int height) {
            byte[] patched = webp.clone();
            patched[WIDTH_OFFSET] = (byte) (width & 0xFF);
            patched[WIDTH_OFFSET + 1] = (byte) ((width >> 8) & 0x3F);
            patched[HEIGHT_OFFSET] = (byte) (height & 0xFF);
            patched[HEIGHT_OFFSET + 1] = (byte) ((height >> 8) & 0x3F);
            return patched;
        }
    }

    /**
     * Writes an RGB PNG row by row (horizontal gradient, identical rows) so the
     * fixture itself never materialises a width x height raster.
     */
    private static byte[] streamedGradientPng(int width, int height) throws IOException {
        byte[] row = new byte[1 + width * 3];
        for (int x = 0; x < width; x++) {
            row[1 + 3 * x] = (byte) (x * 255 / width);
            row[2 + 3 * x] = (byte) (255 - x * 255 / width);
            row[3 + 3 * x] = (byte) 128;
        }
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream z = new DeflaterOutputStream(idat, deflater)) {
            for (int y = 0; y < height; y++) {
                z.write(row);
            }
        } finally {
            deflater.end();
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        pngChunk(png, "IHDR", ByteBuffer.allocate(13)
                .putInt(width).putInt(height)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0)
                .array());
        pngChunk(png, "IDAT", idat.toByteArray());
        pngChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void pngChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        out.write(typeBytes);
        out.write(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    /** The pre-subsampling pipeline: full decode, one bilinear redraw, JPEG round trip. */
    private static BufferedImage fullDecodeRedraw(byte[] input, int width, int height) throws Exception {
        BufferedImage full = decode(input);
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.drawImage(full, 0, 0, width, height, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(target, "jpeg", out)).isTrue();
        return decode(out.toByteArray());
    }

    private static double meanAbsoluteDifference(BufferedImage a, BufferedImage b) {
        long total = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                total += Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF))
                        + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                        + Math.abs((p & 0xFF) - (q & 0xFF));
            }
        }
        return (double) total / (3L * a.getWidth() * a.getHeight());
    }

    @Test
    @DisplayName("the TwelveMonkeys WebP reader is registered on the classpath")
    void webpReaderIsAvailable() {
        assertThat(ImageIO.getImageReadersByFormatName("webp").hasNext())
                .as("imageio-webp plugin must be present so WebP inputs can be decoded")
                .isTrue();
    }
}
