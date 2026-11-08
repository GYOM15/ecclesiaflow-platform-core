package com.ecclesiaflow.platform.upload;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** JPEG fixtures carrying an EXIF orientation, as phones write them: an APP1 segment after the JFIF one. */
final class ExifJpegs {

    static final Color TOP_LEFT = Color.RED;
    static final Color TOP_RIGHT = Color.BLUE;
    static final Color BOTTOM_LEFT = Color.GREEN;
    static final Color BOTTOM_RIGHT = Color.BLACK;

    private ExifJpegs() {
    }

    /** A stored raster of four solid quadrants, so every turn and flip lands each colour somewhere distinct. */
    static byte[] quadrants(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        int halfW = width / 2;
        int halfH = height / 2;
        fill(g, TOP_LEFT, 0, 0, halfW, halfH);
        fill(g, TOP_RIGHT, halfW, 0, width - halfW, halfH);
        fill(g, BOTTOM_LEFT, 0, halfH, halfW, height - halfH);
        fill(g, BOTTOM_RIGHT, halfW, halfH, width - halfW, height - halfH);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "jpeg", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void fill(Graphics2D g, Color color, int x, int y, int w, int h) {
        g.setColor(color);
        g.fillRect(x, y, w, h);
    }

    /** A TIFF block holding one IFD0 entry. */
    static byte[] tiff(ByteOrder order, int tag, int type, int value) {
        ByteBuffer t = ByteBuffer.allocate(26).order(order);
        t.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        t.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        t.putShort((short) 42).putInt(8);
        t.putShort((short) 1);
        t.putShort((short) tag).putShort((short) type).putInt(1);
        t.putShort((short) value).putShort((short) 0);
        t.putInt(0);
        return t.array();
    }

    static byte[] withOrientation(byte[] jpeg, int orientation, ByteOrder order) {
        return withApp1(jpeg, exifPayload(tiff(order, 0x0112, 3, orientation)));
    }

    static byte[] exifPayload(byte[] tiff) {
        byte[] payload = new byte[6 + tiff.length];
        System.arraycopy(new byte[] {'E', 'x', 'i', 'f', 0, 0}, 0, payload, 0, 6);
        System.arraycopy(tiff, 0, payload, 6, tiff.length);
        return payload;
    }

    /** Inserts an APP1 segment after the JFIF APP0 segment ImageIO writes first. */
    static byte[] withApp1(byte[] jpeg, byte[] payload) {
        int app0End = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        int length = payload.length + 2;
        ByteBuffer out = ByteBuffer.allocate(jpeg.length + 2 + length);
        out.put(jpeg, 0, app0End);
        out.put((byte) 0xFF).put((byte) 0xE1).putShort((short) length).put(payload);
        out.put(jpeg, app0End, jpeg.length - app0End);
        return out.array();
    }
}
