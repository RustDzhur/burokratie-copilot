package de.buerokratiecopilot.documents.extraction;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** How many pictures an image file holds. One for a JPEG, several for a multi-page TIFF. */
public final class ImageFrames {

    private ImageFrames() {
    }

    /**
     * One frame as a standalone image, or the original bytes when the file has one frame or cannot be read.
     *
     * <p><strong>Everything here falls back to the original on purpose.</strong> The first version let an
     * exception escape, and four photo tests failed: a reader that cannot enumerate its frames is not a reason
     * to fail a document. The engine can still read the whole file, and the worst case is what happened
     * before this class existed.</p>
     */
    public static byte[] frame(byte[] content, int pageNumber) {
        try {
            return extract(content, pageNumber);
        }
        catch (IOException | RuntimeException ex) {
            return content;
        }
    }

    private static byte[] extract(byte[] content, int pageNumber) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (stream == null) {
                return content;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return content;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, false, false);
                int frames = reader.getNumImages(true);
                if (frames <= 1) {
                    // One frame is already what the engine needs; re-encoding it would only cost quality and
                    // could drop the metadata the rotation depends on.
                    return content;
                }
                int index = Math.max(0, Math.min(pageNumber - 1, frames - 1));
                BufferedImage image = reader.read(index);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(image, "png", out);
                return out.toByteArray();
            }
            finally {
                reader.dispose();
            }
        }
    }

    public static int count(byte[] content) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (stream == null) {
                return 1;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return 1;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, false, false);
                return Math.max(1, reader.getNumImages(true));
            }
            finally {
                reader.dispose();
            }
        }
        catch (IOException | RuntimeException ex) {
            return 1;
        }
    }
}
