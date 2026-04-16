package com.mcaiassistant.visionaddon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;

/**
 * 客户端截图处理器。
 *
 * 流程：
 * 1. 在客户端主线程临时隐藏 HUD；
 * 2. 等待一帧后读取 framebuffer 像素；
 * 3. 立刻恢复 HUD；
 * 4. 在异步线程压缩为 JPEG 并通过 Plugin Message 回传。
 */
public final class ScreenshotHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("mcai-vision");

    private static final int TARGET_LONG_EDGE = 1280;
    private static final int TARGET_SHORT_EDGE = 720;
    private static final float JPEG_QUALITY = 0.78f;
    private static final int MAX_BASE64_BYTES = 4 * 1024 * 1024;

    private ScreenshotHandler() {
    }

    public static void captureAndSend() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null || client.getWindow() == null) {
            LOGGER.warn("[mcai-vision] 客户端未就绪，跳过截图");
            return;
        }

        boolean originalHudHidden = client.options.hudHidden;
        client.options.hudHidden = true;

        client.execute(() -> captureFramebufferWithoutHud(client, originalHudHidden));
    }

    private static void captureFramebufferWithoutHud(MinecraftClient client, boolean originalHudHidden) {
        try {
            Framebuffer framebuffer = client.getFramebuffer();
            int fbWidth = framebuffer.textureWidth;
            int fbHeight = framebuffer.textureHeight;

            if (fbWidth <= 0 || fbHeight <= 0) {
                LOGGER.warn("[mcai-vision] 帧缓冲尺寸无效: {}x{}", fbWidth, fbHeight);
                return;
            }

            ByteBuffer pixelBuffer = BufferUtils.createByteBuffer(fbWidth * fbHeight * 4);
            GL11.glReadPixels(0, 0, fbWidth, fbHeight, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixelBuffer);

            CompletableFuture.runAsync(() -> compressAndSend(pixelBuffer, fbWidth, fbHeight));
        } catch (Exception e) {
            LOGGER.error("[mcai-vision] 截图读取异常: {}", e.getMessage(), e);
        } finally {
            client.options.hudHidden = originalHudHidden;
        }
    }

    private static void compressAndSend(ByteBuffer pixelBuffer, int width, int height) {
        try {
            String base64 = compressToBase64(pixelBuffer, width, height);
            if (base64 == null || base64.isEmpty()) {
                LOGGER.warn("[mcai-vision] 压缩失败，跳过发包");
                return;
            }
            if (base64.length() > MAX_BASE64_BYTES) {
                LOGGER.warn("[mcai-vision] 截图 base64 过大 ({} bytes)，跳过发包", base64.length());
                return;
            }

            ClientPlayNetworking.send(new VisionPackets.ResponsePayload(base64));
            LOGGER.info("[mcai-vision] 截图已发送，base64 大小: {} bytes", base64.length());
        } catch (Exception e) {
            LOGGER.error("[mcai-vision] 截图压缩/发包异常: {}", e.getMessage(), e);
        }
    }

    private static String compressToBase64(ByteBuffer pixelBuffer, int width, int height) {
        try {
            BufferedImage raw = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            pixelBuffer.rewind();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int r = pixelBuffer.get() & 0xFF;
                    int g = pixelBuffer.get() & 0xFF;
                    int b = pixelBuffer.get() & 0xFF;
                    pixelBuffer.get();
                    raw.setRGB(x, height - 1 - y, (r << 16) | (g << 8) | b);
                }
            }

            BufferedImage scaled = scaleImage(raw);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                LOGGER.error("[mcai-vision] 找不到 JPEG ImageWriter");
                return null;
            }

            ImageWriter writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(new MemoryCacheImageOutputStream(baos));
            writer.write(null, new IIOImage(scaled, null, null), param);
            writer.dispose();

            LOGGER.info("[mcai-vision] 截图压缩完成: 原始={}x{}, 输出={}x{}, JPEG质量={}",
                    width, height, scaled.getWidth(), scaled.getHeight(), JPEG_QUALITY);

            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            LOGGER.error("[mcai-vision] 图像压缩失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private static BufferedImage scaleImage(BufferedImage src) {
        int srcWidth = src.getWidth();
        int srcHeight = src.getHeight();
        if (srcWidth <= 0 || srcHeight <= 0) {
            return src;
        }

        int longEdge = Math.max(srcWidth, srcHeight);
        int shortEdge = Math.min(srcWidth, srcHeight);
        if (longEdge <= TARGET_LONG_EDGE && shortEdge <= TARGET_SHORT_EDGE) {
            return src;
        }

        double scale = Math.min((double) TARGET_LONG_EDGE / longEdge, (double) TARGET_SHORT_EDGE / shortEdge);
        int targetWidth = Math.max(1, (int) Math.round(srcWidth * scale));
        int targetHeight = Math.max(1, (int) Math.round(srcHeight * scale));

        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = scaled.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(src, 0, 0, targetWidth, targetHeight, null);
        g2d.dispose();
        return scaled;
    }
}
