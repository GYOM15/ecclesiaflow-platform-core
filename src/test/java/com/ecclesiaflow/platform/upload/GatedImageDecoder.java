package com.ecclesiaflow.platform.upload;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Takes over the decoding of one marked PNG and holds every such decode until
 * {@link #open()}, counting how many are in flight at once. Installed ahead of
 * every other reader, so {@link ImageSanitizer} reaches it through ImageIO exactly
 * as it reaches a real decoder; any other image still goes to the real readers.
 */
public final class GatedImageDecoder implements AutoCloseable {

    private static final int MARK_WIDTH = 7;
    private static final int MARK_HEIGHT = 3;

    private final CountDownLatch gate = new CountDownLatch(1);
    private final Object monitor = new Object();
    private final Spi spi = new Spi();
    private int inFlight;
    private int maxInFlight;

    private GatedImageDecoder() {
    }

    public static GatedImageDecoder install() {
        GatedImageDecoder decoder = new GatedImageDecoder();
        IIORegistry registry = IIORegistry.getDefaultInstance();
        List<ImageReaderSpi> others = new ArrayList<>();
        registry.getServiceProviders(ImageReaderSpi.class, false).forEachRemaining(others::add);
        registry.registerServiceProvider(decoder.spi, ImageReaderSpi.class);
        others.forEach(other -> registry.setOrdering(ImageReaderSpi.class, decoder.spi, other));
        return decoder;
    }

    /** A genuine PNG, so it passes every check that runs before the decode. */
    public byte[] markedPng() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(MARK_WIDTH, MARK_HEIGHT, BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether {@code count} decodes were in flight together at some point within {@code timeout}. */
    public boolean awaitInFlight(int count, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            while (maxInFlight < count) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(monitor, left);
            }
            return true;
        }
    }

    public int maxInFlight() {
        synchronized (monitor) {
            return maxInFlight;
        }
    }

    /** Lets every held decode, and every later one, finish. */
    public void open() {
        gate.countDown();
    }

    @Override
    public void close() {
        open();
        IIORegistry.getDefaultInstance().deregisterServiceProvider(spi, ImageReaderSpi.class);
    }

    private BufferedImage decode() throws IIOException {
        synchronized (monitor) {
            inFlight++;
            maxInFlight = Math.max(maxInFlight, inFlight);
            monitor.notifyAll();
        }
        try {
            if (!gate.await(30, TimeUnit.SECONDS)) {
                throw new IIOException("the decode gate was never opened");
            }
            return new BufferedImage(MARK_WIDTH, MARK_HEIGHT, BufferedImage.TYPE_INT_RGB);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IIOException("interrupted at the decode gate", e);
        } finally {
            synchronized (monitor) {
                inFlight--;
            }
        }
    }

    private final class Spi extends ImageReaderSpi {

        Spi() {
            super("ecclesiaflow-test", "1", new String[]{"gated-png"}, null, null,
                    GatedReader.class.getName(), new Class<?>[]{ImageInputStream.class},
                    null, false, null, null, null, null, false, null, null, null, null);
        }

        @Override
        public boolean canDecodeInput(Object source) throws IOException {
            if (!(source instanceof ImageInputStream stream)) {
                return false;
            }
            byte[] header = new byte[24];
            stream.mark();
            try {
                stream.readFully(header);
            } catch (IOException e) {
                return false;
            } finally {
                stream.reset();
            }
            // PNG signature, IHDR, then the width and height that mark the test image.
            return (header[0] & 0xFF) == 0x89 && header[1] == 'P'
                    && readInt(header, 16) == MARK_WIDTH && readInt(header, 20) == MARK_HEIGHT;
        }

        @Override
        public ImageReader createReaderInstance(Object extension) {
            return new GatedReader(this);
        }

        @Override
        public String getDescription(Locale locale) {
            return "Gated test PNG reader";
        }

        private static int readInt(byte[] bytes, int offset) {
            return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                    | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
        }
    }

    private final class GatedReader extends ImageReader {

        GatedReader(ImageReaderSpi provider) {
            super(provider);
        }

        @Override
        public int getNumImages(boolean allowSearch) {
            return 1;
        }

        @Override
        public int getWidth(int imageIndex) {
            return MARK_WIDTH;
        }

        @Override
        public int getHeight(int imageIndex) {
            return MARK_HEIGHT;
        }

        @Override
        public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) {
            return List.of(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB)).iterator();
        }

        @Override
        public IIOMetadata getStreamMetadata() {
            return null;
        }

        @Override
        public IIOMetadata getImageMetadata(int imageIndex) {
            return null;
        }

        @Override
        public BufferedImage read(int imageIndex, ImageReadParam param) throws IIOException {
            return decode();
        }
    }
}
