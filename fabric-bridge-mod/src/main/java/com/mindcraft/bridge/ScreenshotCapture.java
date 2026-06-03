package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Captures the live Fabric client framebuffer for bridge-side vision prompts.
 */
public final class ScreenshotCapture {
    private ScreenshotCapture() {}

    public record Capture(byte[] bytes, int width, int height, float quality, int downscaleFactor) {}

    public static Capture captureJpeg(int requestedDownscale, float requestedQuality) throws Exception {
        int downscale = Math.max(1, Math.min(8, requestedDownscale));
        float quality = Math.max(0.1f, Math.min(1.0f, requestedQuality));

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            throw new IllegalStateException("client_not_available");
        }

        CompletableFuture<Capture> future = new CompletableFuture<>();
        Runnable capture = () -> {
            try {
                if (client.player == null || client.world == null || client.getFramebuffer() == null) {
                    future.completeExceptionally(new IllegalStateException("client_not_connected"));
                    return;
                }
                ScreenshotRecorder.takeScreenshot(client.getFramebuffer(), downscale, image -> {
                    try {
                        future.complete(encodeJpeg(image, quality, downscale));
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    } finally {
                        try {
                            image.close();
                        } catch (Throwable ignored) {
                            // NativeImage ownership is best-effort across Minecraft versions.
                        }
                    }
                });
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };

        if (client.isOnThread()) {
            capture.run();
        } else {
            client.execute(capture);
        }
        return future.get(5, TimeUnit.SECONDS);
    }

    private static Capture encodeJpeg(NativeImage image, float quality, int downscale) throws Exception {
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                rgb.setRGB(x, y, image.getColorArgb(x, y));
            }
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("jpeg_writer_unavailable");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(rgb, null, null), params);
        } finally {
            writer.dispose();
        }
        return new Capture(out.toByteArray(), width, height, quality, downscale);
    }
}
