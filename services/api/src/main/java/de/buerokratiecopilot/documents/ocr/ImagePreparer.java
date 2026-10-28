package de.buerokratiecopilot.documents.ocr;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * Makes a photograph of a page fit for the OCR engine.
 *
 * <p>A phone photo is large, in colour, unevenly lit and sometimes lying on its side. The engine reads a grey picture of
 * about 2000-3000 pixels on the long side best: smaller text is upscaled, huge photos are reduced (which also keeps the
 * time per page bounded), the brightness range is stretched so that grey paper becomes white, and a page can be turned by
 * 90 degrees steps when the first reading found little. A picture that cannot be decoded here is handed to the engine
 * unchanged, which has decoders of its own.</p>
 */
public final class ImagePreparer {

    private static final int LONG_SIDE_MAX = 3000;
    private static final int LONG_SIDE_MIN = 1800;

    private ImagePreparer() {
    }

    /** The picture as grey PNG bytes, scaled and contrast-stretched; the original bytes when it cannot be decoded. */
    public static byte[] prepare(byte[] original) {
        BufferedImage source = decode(original);
        if (source == null) {
            return original;
        }
        BufferedImage grey = scaleToGrey(source);
        stretchContrast(grey);
        return encode(grey, original);
    }

    /** The prepared picture turned clockwise by 90, 180 or 270 degrees. */
    public static byte[] rotated(byte[] original, int degrees) {
        BufferedImage source = decode(original);
        if (source == null || degrees % 360 == 0) {
            return original;
        }
        int turns = ((degrees / 90) % 4 + 4) % 4;
        int w = source.getWidth();
        int h = source.getHeight();
        boolean sideways = turns % 2 == 1;
        BufferedImage out = new BufferedImage(sideways ? h : w, sideways ? w : h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = out.createGraphics();
        AffineTransform transform = new AffineTransform();
        switch (turns) {
            case 1 -> {
                transform.translate(h, 0);
                transform.rotate(Math.PI / 2);
            }
            case 2 -> {
                transform.translate(w, h);
                transform.rotate(Math.PI);
            }
            default -> {
                transform.translate(0, w);
                transform.rotate(-Math.PI / 2);
            }
        }
        g.drawImage(source, transform, null);
        g.dispose();
        return encode(out, original);
    }

    private static BufferedImage decode(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        }
        catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static byte[] encode(BufferedImage image, byte[] fallback) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
        catch (IOException ex) {
            return fallback;
        }
    }

    private static BufferedImage scaleToGrey(BufferedImage source) {
        int longSide = Math.max(source.getWidth(), source.getHeight());
        double scale = 1.0;
        if (longSide > LONG_SIDE_MAX) {
            scale = (double) LONG_SIDE_MAX / longSide;
        }
        else if (longSide < LONG_SIDE_MIN) {
            scale = (double) LONG_SIDE_MIN / longSide;
        }
        int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage grey = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = grey.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(source, 0, 0, w, h, null);
        g.dispose();
        return grey;
    }

    /** Maps the 1st..99th percentile of the brightness to 0..255, so grey paper becomes white and faint ink darker. */
    private static void stretchContrast(BufferedImage grey) {
        int w = grey.getWidth();
        int h = grey.getHeight();
        int[] histogram = new int[256];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            grey.getRaster().getSamples(0, y, w, 1, 0, row);
            for (int v : row) {
                histogram[v]++;
            }
        }
        long total = (long) w * h;
        int low = percentile(histogram, total, 0.01);
        int high = percentile(histogram, total, 0.99);
        if (high - low < 30) {
            return; // a picture without contrast stays as it is: stretching noise helps nobody
        }
        double factor = 255.0 / (high - low);
        for (int y = 0; y < h; y++) {
            grey.getRaster().getSamples(0, y, w, 1, 0, row);
            for (int x = 0; x < w; x++) {
                row[x] = (int) Math.max(0, Math.min(255, Math.round((row[x] - low) * factor)));
            }
            grey.getRaster().setSamples(0, y, w, 1, 0, row);
        }
    }

    private static int percentile(int[] histogram, long total, double fraction) {
        long target = (long) (total * fraction);
        long seen = 0;
        for (int value = 0; value < 256; value++) {
            seen += histogram[value];
            if (seen > target) {
                return value;
            }
        }
        return 255;
    }
}
