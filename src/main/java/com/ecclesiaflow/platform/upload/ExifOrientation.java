package com.ecclesiaflow.platform.upload;

import java.awt.geom.AffineTransform;

/**
 * The EXIF orientation of a JPEG, read from its first APP1 segment. Phones store the sensor's raster and record
 * how to turn it; ImageIO ignores the tag and the re-encode drops it, so it is applied while redrawing.
 */
enum ExifOrientation {
    NORMAL, FLIP_HORIZONTAL, ROTATE_180, FLIP_VERTICAL, TRANSPOSE, ROTATE_90_CW, TRANSVERSE, ROTATE_90_CCW;

    private static final int ORIENTATION_TAG = 0x0112;
    private static final int TYPE_SHORT = 3;

    /** True when the displayed image is the stored one turned a quarter, so width and height swap. */
    boolean swapsAxes() {
        return ordinal() >= TRANSPOSE.ordinal();
    }

    /** Maps the stored raster, drawn at its oriented-back size, onto a {@code width} x {@code height} canvas. */
    AffineTransform toCanvas(int width, int height) {
        return switch (this) {
            case NORMAL -> new AffineTransform();
            case FLIP_HORIZONTAL -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case ROTATE_180 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case FLIP_VERTICAL -> new AffineTransform(1, 0, 0, -1, 0, height);
            case TRANSPOSE -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case ROTATE_90_CW -> new AffineTransform(0, 1, -1, 0, width, 0);
            case TRANSVERSE -> new AffineTransform(0, -1, -1, 0, width, height);
            case ROTATE_90_CCW -> new AffineTransform(0, -1, 1, 0, 0, height);
        };
    }

    /** {@link #NORMAL} for anything but a well-formed tag: a bad header must not reject a photo it would only turn. */
    static ExifOrientation of(byte[] jpeg) {
        try {
            int value = readTag(jpeg);
            return value >= 1 && value <= 8 ? values()[value - 1] : NORMAL;
        } catch (IndexOutOfBoundsException e) {
            return NORMAL;
        }
    }

    private static int readTag(byte[] b) {
        if (u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) {
            return 1;
        }
        int pos = 2;
        while (u8(b, pos) == 0xFF) {
            int marker = u8(b, pos + 1);
            // Start of scan: no metadata segment follows.
            if (marker == 0xDA) {
                return 1;
            }
            int length = u16(b, pos + 2, false);
            if (length < 2) {
                return 1;
            }
            if (marker == 0xE1 && isExifHeader(b, pos + 4)) {
                return orientationInTiff(b, pos + 10, pos + 2 + length);
            }
            pos += 2 + length;
        }
        return 1;
    }

    private static boolean isExifHeader(byte[] b, int at) {
        return u8(b, at) == 'E' && u8(b, at + 1) == 'x' && u8(b, at + 2) == 'i' && u8(b, at + 3) == 'f'
                && u8(b, at + 4) == 0 && u8(b, at + 5) == 0;
    }

    private static int orientationInTiff(byte[] b, int tiff, int segmentEnd) {
        boolean little;
        if (u8(b, tiff) == 'I' && u8(b, tiff + 1) == 'I') {
            little = true;
        } else if (u8(b, tiff) == 'M' && u8(b, tiff + 1) == 'M') {
            little = false;
        } else {
            return 1;
        }
        if (u16(b, tiff + 2, little) != 42) {
            return 1;
        }
        long ifd = tiff + u32(b, tiff + 4, little);
        if (ifd + 2 > segmentEnd) {
            return 1;
        }
        int entries = u16(b, (int) ifd, little);
        for (int i = 0; i < entries; i++) {
            int entry = (int) ifd + 2 + 12 * i;
            if (entry + 12 > segmentEnd) {
                return 1;
            }
            if (u16(b, entry, little) == ORIENTATION_TAG) {
                return u16(b, entry + 2, little) == TYPE_SHORT ? u16(b, entry + 8, little) : 1;
            }
        }
        return 1;
    }

    private static int u8(byte[] b, int at) {
        return b[at] & 0xFF;
    }

    private static int u16(byte[] b, int at, boolean little) {
        return little ? u8(b, at) | u8(b, at + 1) << 8 : u8(b, at) << 8 | u8(b, at + 1);
    }

    private static long u32(byte[] b, int at, boolean little) {
        long v = little
                ? u16(b, at, true) | (long) u16(b, at + 2, true) << 16
                : (long) u16(b, at, false) << 16 | u16(b, at + 2, false);
        return v & 0xFFFFFFFFL;
    }
}
