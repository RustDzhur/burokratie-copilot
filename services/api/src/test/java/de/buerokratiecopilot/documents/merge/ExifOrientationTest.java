package de.buerokratiecopilot.documents.merge;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExifOrientationTest {

    private static byte[] jpeg(int w, int h) throws Exception {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /** A JPEG with an EXIF block (little endian) that says the given orientation, inserted right after the start marker. */
    private static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] exif = {
            (byte) 0xFF, (byte) 0xE1, 0, 34, 'E', 'x', 'i', 'f', 0, 0,
            'I', 'I', 42, 0, 8, 0, 0, 0,          // TIFF header, first directory at offset 8
            1, 0,                                  // one entry
            0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0, // tag 0x0112, SHORT, one value
            0, 0, 0, 0                             // no next directory
        };
        byte[] result = new byte[jpeg.length + exif.length];
        result[0] = jpeg[0];
        result[1] = jpeg[1];
        System.arraycopy(exif, 0, result, 2, exif.length);
        System.arraycopy(jpeg, 2, result, 2 + exif.length, jpeg.length - 2);
        return result;
    }

    @Test
    @DisplayName("the orientation a phone wrote into the photo is read, and a photo without one counts as upright")
    void readsTheOrientation() throws Exception {
        byte[] plain = jpeg(40, 20);
        assertThat(ExifOrientation.of(plain)).isEqualTo(1);
        assertThat(ExifOrientation.of(withOrientation(plain, 6))).isEqualTo(6);
        assertThat(ExifOrientation.of(withOrientation(plain, 8))).isEqualTo(8);
        assertThat(ExifOrientation.of(withOrientation(plain, 3))).isEqualTo(3);
        assertThat(ExifOrientation.of(new byte[] {1, 2, 3})).isEqualTo(1);
        assertThat(ExifOrientation.of(null)).isEqualTo(1);
    }

    @Test
    @DisplayName("a photo lying on its side is turned upright: width and height swap for a quarter turn, stay for a half turn")
    void turnsThePicture() {
        BufferedImage landscape = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        assertThat(ExifOrientation.upright(landscape, 6).getWidth()).isEqualTo(20);
        assertThat(ExifOrientation.upright(landscape, 6).getHeight()).isEqualTo(40);
        assertThat(ExifOrientation.upright(landscape, 8).getWidth()).isEqualTo(20);
        assertThat(ExifOrientation.upright(landscape, 3).getWidth()).isEqualTo(40);
        assertThat(ExifOrientation.upright(landscape, 1)).isSameAs(landscape);
    }
}
