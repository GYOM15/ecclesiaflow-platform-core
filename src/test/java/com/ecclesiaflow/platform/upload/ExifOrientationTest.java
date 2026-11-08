package com.ecclesiaflow.platform.upload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExifOrientation - reads the tag, never fails on a bad header")
class ExifOrientationTest {

    private static final byte[] JPEG = ExifJpegs.quadrants(8, 4);

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("each tag value is read in both byte orders")
    void readsEveryValue(int value) {
        ExifOrientation expected = ExifOrientation.values()[value - 1];

        assertThat(ExifOrientation.of(ExifJpegs.withOrientation(JPEG, value, ByteOrder.BIG_ENDIAN)))
                .isEqualTo(expected);
        assertThat(ExifOrientation.of(ExifJpegs.withOrientation(JPEG, value, ByteOrder.LITTLE_ENDIAN)))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("only the quarter turns swap width and height")
    void quarterTurnsSwapAxes() {
        assertThat(Arrays.stream(ExifOrientation.values()).filter(ExifOrientation::swapsAxes))
                .containsExactly(ExifOrientation.TRANSPOSE, ExifOrientation.ROTATE_90_CW,
                        ExifOrientation.TRANSVERSE, ExifOrientation.ROTATE_90_CCW);
    }

    @Test
    @DisplayName("a JPEG without EXIF is upright")
    void noExif() {
        assertThat(ExifOrientation.of(JPEG)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    @DisplayName("bytes that are not a JPEG are upright")
    void notAJpeg() {
        assertThat(ExifOrientation.of(new byte[] {(byte) 0x89, 'P', 'N', 'G'})).isEqualTo(ExifOrientation.NORMAL);
        assertThat(ExifOrientation.of(new byte[0])).isEqualTo(ExifOrientation.NORMAL);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 9, 0xFFFF})
    @DisplayName("an out-of-range value is upright")
    void outOfRange(int value) {
        assertThat(ExifOrientation.of(ExifJpegs.withOrientation(JPEG, value, ByteOrder.BIG_ENDIAN)))
                .isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    @DisplayName("an orientation entry of the wrong type is ignored")
    void wrongType() {
        byte[] tiff = ExifJpegs.tiff(ByteOrder.BIG_ENDIAN, 0x0112, 4, 6);

        assertThat(ExifOrientation.of(ExifJpegs.withApp1(JPEG, ExifJpegs.exifPayload(tiff))))
                .isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    @DisplayName("an IFD without the orientation tag is upright")
    void otherTag() {
        byte[] tiff = ExifJpegs.tiff(ByteOrder.BIG_ENDIAN, 0x010F, 3, 6);

        assertThat(ExifOrientation.of(ExifJpegs.withApp1(JPEG, ExifJpegs.exifPayload(tiff))))
                .isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    @DisplayName("an APP1 segment that is not EXIF is skipped")
    void xmpSegment() {
        byte[] xmp = "http://ns.adobe.com/xap/1.0/\0<x/>".getBytes(StandardCharsets.US_ASCII);

        assertThat(ExifOrientation.of(ExifJpegs.withApp1(JPEG, xmp))).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    @DisplayName("a bad TIFF header is upright")
    void badTiffHeader() {
        byte[] wrongOrder = ExifJpegs.tiff(ByteOrder.BIG_ENDIAN, 0x0112, 3, 6);
        wrongOrder[0] = 'X';
        byte[] wrongMagic = ExifJpegs.tiff(ByteOrder.BIG_ENDIAN, 0x0112, 3, 6);
        wrongMagic[3] = 41;

        assertThat(ExifOrientation.of(ExifJpegs.withApp1(JPEG, ExifJpegs.exifPayload(wrongOrder))))
                .isEqualTo(ExifOrientation.NORMAL);
        assertThat(ExifOrientation.of(ExifJpegs.withApp1(JPEG, ExifJpegs.exifPayload(wrongMagic))))
                .isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    @DisplayName("an IFD offset or entry count past the segment is upright")
    void pointsOutsideTheSegment() {
        byte[] farIfd = ExifJpegs.tiff(ByteOrder.BIG_ENDIAN, 0x0112, 3, 6);
        farIfd[7] = 0x7F;
        byte[] manyEntries = ExifJpegs.tiff(ByteOrder.BIG_ENDIAN, 0x0110, 3, 6);
        manyEntries[9] = 0x40;

        assertThat(ExifOrientation.of(ExifJpegs.withApp1(JPEG, ExifJpegs.exifPayload(farIfd))))
                .isEqualTo(ExifOrientation.NORMAL);
        assertThat(ExifOrientation.of(ExifJpegs.withApp1(JPEG, ExifJpegs.exifPayload(manyEntries))))
                .isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    @DisplayName("a file cut inside the EXIF segment is upright, not an error")
    void truncated() {
        byte[] full = ExifJpegs.withOrientation(JPEG, 6, ByteOrder.BIG_ENDIAN);
        int app1 = 2;
        while (!((full[app1] & 0xFF) == 0xFF && (full[app1 + 1] & 0xFF) == 0xE1)) {
            app1++;
        }
        // Marker and length (4), "Exif\0\0" (6), then the value sits 18 bytes into the TIFF block.
        int valueEnd = app1 + 4 + 6 + 18 + 2;

        for (int cut = 2; cut < valueEnd; cut++) {
            assertThat(ExifOrientation.of(Arrays.copyOf(full, cut))).isEqualTo(ExifOrientation.NORMAL);
        }
    }

    @Test
    @DisplayName("a segment length under two stops the walk")
    void zeroLengthSegment() {
        byte[] bytes = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1};

        assertThat(ExifOrientation.of(bytes)).isEqualTo(ExifOrientation.NORMAL);
    }
}
