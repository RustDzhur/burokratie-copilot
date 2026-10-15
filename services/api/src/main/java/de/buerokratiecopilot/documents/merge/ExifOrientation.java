package de.buerokratiecopilot.documents.merge;

import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;

/**
 * A phone stores a photo as the sensor saw it and writes into the file how it has to be turned to be upright (EXIF orientation).
 * Java's image reader ignores that, so a page photographed in portrait would land sideways in a PDF. This reads the value from a
 * JPEG and turns the picture accordingly.
 */
final class ExifOrientation {

    private ExifOrientation() {
    }

    /** The EXIF orientation (1 = upright, 3 = upside down, 6 = turn right, 8 = turn left), 1 when there is none. */
    static int of(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 12 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return 1;
        }
        int offset = 2;
        while (offset + 4 < jpeg.length) {
            if ((jpeg[offset] & 0xFF) != 0xFF) {
                return 1;
            }
            int marker = jpeg[offset + 1] & 0xFF;
            int length = ((jpeg[offset + 2] & 0xFF) << 8) | (jpeg[offset + 3] & 0xFF);
            if (marker == 0xE1 && offset + 4 + 6 <= jpeg.length && jpeg[offset + 4] == 'E' && jpeg[offset + 5] == 'x'
                    && jpeg[offset + 6] == 'i' && jpeg[offset + 7] == 'f') {
                return orientationIn(jpeg, offset + 10, Math.min(jpeg.length, offset + 2 + length));
            }
            if (marker == 0xDA || length < 2) {
                return 1;
            }
            offset += 2 + length;
        }
        return 1;
    }

    private static int orientationIn(byte[] d, int tiff, int end) {
        if (tiff + 8 > end) {
            return 1;
        }
        boolean little = d[tiff] == 'I';
        int ifd = tiff + (int) readInt(d, tiff + 4, 4, little);
        if (ifd + 2 > end) {
            return 1;
        }
        int entries = (int) readInt(d, ifd, 2, little);
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > end) {
                return 1;
            }
            if (readInt(d, entry, 2, little) == 0x0112) {
                int value = (int) readInt(d, entry + 8, 2, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static long readInt(byte[] d, int at, int bytes, boolean little) {
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            int b = d[at + (little ? bytes - 1 - i : i)] & 0xFF;
            value = (value << 8) | b;
        }
        return value;
    }

    /** The picture turned upright for the orientation (only the turns phones really write: 3, 6 and 8). */
    static BufferedImage upright(BufferedImage image, int orientation) {
        int w = image.getWidth();
        int h = image.getHeight();
        AffineTransform transform = new AffineTransform();
        BufferedImage target;
        switch (orientation) {
            case 3 -> {
                transform.translate(w, h);
                transform.rotate(Math.PI);
                target = new BufferedImage(w, h, image.getType() == 0 ? BufferedImage.TYPE_INT_RGB : image.getType());
            }
            case 6 -> {
                transform.translate(h, 0);
                transform.rotate(Math.PI / 2);
                target = new BufferedImage(h, w, image.getType() == 0 ? BufferedImage.TYPE_INT_RGB : image.getType());
            }
            case 8 -> {
                transform.translate(0, w);
                transform.rotate(-Math.PI / 2);
                target = new BufferedImage(h, w, image.getType() == 0 ? BufferedImage.TYPE_INT_RGB : image.getType());
            }
            default -> {
                return image;
            }
        }
        return new AffineTransformOp(transform, AffineTransformOp.TYPE_BILINEAR).filter(image, target);
    }
}
