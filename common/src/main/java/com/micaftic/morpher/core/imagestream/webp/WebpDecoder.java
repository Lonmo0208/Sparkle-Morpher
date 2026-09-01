package com.micaftic.morpher.core.imagestream.webp;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * WebP decoder delegating to ImageIO with the bundled webp-imageio SPI plugin.
 */
public class WebpDecoder {

    public static byte[] decode(byte[] data, int[] width, int[] height) {
        BufferedImage img = read(data);
        if (img == null) return null;
        width[0] = img.getWidth();
        height[0] = img.getHeight();
        return toRgbaBytes(img);
    }

    public static BufferedImage read(byte[] data) {
        if (data == null || data.length < 12) return null;
        // Quick signature check: "RIFF" + 4 bytes + "WEBP"
        if (data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F'
                || data[8] != 'W' || data[9] != 'E' || data[10] != 'B' || data[11] != 'P') {
            return null;
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
            return ImageIO.read(bais);
        } catch (IOException e) {

            return null;
        }
    }

    private static byte[] toRgbaBytes(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] out = new byte[w * h * 4];
        int[] pixels = new int[w * h];
        img.getRGB(0, 0, w, h, pixels, 0, w);
        for (int i = 0, j = 0; i < pixels.length; i++, j += 4) {
            int rgba = pixels[i];
            out[j]     = (byte) ((rgba >> 16) & 0xFF); // R
            out[j + 1] = (byte) ((rgba >> 8)  & 0xFF); // G
            out[j + 2] = (byte) ( rgba        & 0xFF); // B
            out[j + 3] = (byte) ((rgba >> 24) & 0xFF); // A
        }
        return out;
    }
}
