package de.buerokratiecopilot.documents.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A TIFF can hold a whole letter, and every frame is a page (spec §7.3).
 *
 * <p><strong>What this covers.</strong> The platform treated every image as one page, so a five-page scan
 * became a one-page document: the remaining pages were never read, recognised or searched, and nothing said
 * they had existed. The count comes from the file rather than from the MIME type.</p>
 */
class ImageFramesTest {

    /** A TIFF with {@code frames} images of different solid colours, so they are distinguishable. */
    private static byte[] multiFrameTiff(int frames) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int i = 1; i <= frames; i++) {
                BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
                for (int x = 0; x < 20; x++) {
                    for (int y = 0; y < 20; y++) {
                        image.setRGB(x, y, 0x101010 * i);
                    }
                }
                writer.writeToSequence(new javax.imageio.IIOImage(image, null, null), null);
            }
            writer.endWriteSequence();
        }
        finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    @Test
    @DisplayName("a multi-page TIFF reports every frame as a page")
    void aMultiPageTiffReportsEveryFrame() throws Exception {
        byte[] tiff = multiFrameTiff(3);

        assertThat(ImageFrames.count(tiff))
                .as("three scans in one file are three pages, not one")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("each frame is a different picture")
    void eachFrameIsADifferentPicture() throws Exception {
        byte[] tiff = multiFrameTiff(2);

        byte[] first = ImageFrames.frame(tiff, 1);
        byte[] second = ImageFrames.frame(tiff, 2);

        assertThat(first).as("the second page must not be a copy of the first").isNotEqualTo(second);
    }

    @Test
    @DisplayName("a single-frame image is handed on unchanged")
    void aSingleFrameIsUnchanged() throws Exception {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        byte[] png = bytes.toByteArray();

        assertThat(ImageFrames.count(png)).isEqualTo(1);
        // Re-encoding would cost quality and could drop metadata; a single page is already what is needed.
        assertThat(ImageFrames.frame(png, 1)).isEqualTo(png);
    }

    @Test
    @DisplayName("a file that cannot be read is still one page, never zero")
    void anUnreadableFileIsOnePage() {
        // Reporting zero pages would turn a readable picture into an unreadable document.
        assertThat(ImageFrames.count("not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(1);
        assertThat(ImageFrames.frame("not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8), 1))
                .isEqualTo("not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
