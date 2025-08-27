package com.example.bastion.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ToastManager {

    private static final List<Toast> toasts = new ArrayList<>();

    public static void addToast(String message) {
        addToast(message, 0xFFFF55); // default yellow text
    }

    public static void addToast(String message, int color) {
        toasts.add(new Toast(message, color));
    }

    public static void render(Minecraft mc) {
        if (toasts.isEmpty()) return;

        ScaledResolution sr = new ScaledResolution(mc);
        int baseX = sr.getScaledWidth() - 220; // wide enough for wrapped text
        int y = 10;

        Iterator<Toast> it = toasts.iterator();
        while (it.hasNext()) {
            Toast toast = it.next();
            if (toast.isFullyExpired()) {
                it.remove();
                continue;
            }
            toast.draw(mc, baseX, y);
            y += toast.getHeight() + 10; // dynamic stacking
        }
    }

    // === Inner toast class ===
    private static class Toast {
        private final String message;
        private final int color;
        private final long start;
        private final long duration = 15000; // visible
        private final long fadeTime = 1000;  // fade out

        private Toast(String message, int color) {
            this.message = message;
            this.color = color;
            this.start = System.currentTimeMillis();
        }

        private boolean isFullyExpired() {
            return System.currentTimeMillis() - start > duration + fadeTime;
        }

        private int getHeight() {
            Minecraft mc = Minecraft.getMinecraft();
            int maxWidth = 200;
            List<String> lines = mc.fontRendererObj.listFormattedStringToWidth(message, maxWidth);
            return 30 + (lines.size() * mc.fontRendererObj.FONT_HEIGHT);
        }

        private void draw(Minecraft mc, int x, int y) {
            long elapsed = System.currentTimeMillis() - start;
            float alpha = 1.0f;
            int yOffset = 0;

            if (elapsed > duration) {
                float progress = (float)(elapsed - duration) / fadeTime;
                progress = Math.min(progress, 1.0f);
                alpha = 1.0f - progress;
                yOffset = -(int)(progress * 20);
            }

            GlStateManager.enableBlend();
            GlStateManager.disableTexture2D();

            // === Background ===
            int maxWidth = 200;
            List<String> lines = mc.fontRendererObj.listFormattedStringToWidth(message, maxWidth);
            int textHeight = lines.size() * mc.fontRendererObj.FONT_HEIGHT;
            int toastHeight = 20 + textHeight;
            int toastWidth = maxWidth + 12;

            // background rectangle (semi-transparent black)
            drawRect(x, y + yOffset, x + toastWidth, y + toastHeight + yOffset, 0xAA000000);

            GlStateManager.enableTexture2D();

            // title always gold
            mc.fontRendererObj.drawStringWithShadow("§6[Bastion]", x + 6, y + 6 + yOffset, 0xFFD700);

            // message lines with toast color
            int lineY = y + 20 + yOffset;
            for (String line : lines) {
                mc.fontRendererObj.drawString(line, x + 6, lineY, color);
                lineY += mc.fontRendererObj.FONT_HEIGHT;
            }

            GlStateManager.disableBlend();
            GlStateManager.color(1f, 1f, 1f, 1f);
        }

        // Gui.drawRect clone
        private void drawRect(int left, int top, int right, int bottom, int color) {
            int j;
            if (left < right) { j = left; left = right; right = j; }
            if (top < bottom) { j = top; top = bottom; bottom = j; }

            float a = (float)(color >> 24 & 255) / 255.0F;
            float r = (float)(color >> 16 & 255) / 255.0F;
            float g = (float)(color >> 8 & 255) / 255.0F;
            float b = (float)(color & 255) / 255.0F;

            GlStateManager.color(r, g, b, a);
            net.minecraft.client.renderer.Tessellator tess = net.minecraft.client.renderer.Tessellator.getInstance();
            net.minecraft.client.renderer.WorldRenderer wr = tess.getWorldRenderer();
            wr.begin(7, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION);
            wr.pos(left, bottom, 0.0D).endVertex();
            wr.pos(right, bottom, 0.0D).endVertex();
            wr.pos(right, top, 0.0D).endVertex();
            wr.pos(left, top, 0.0D).endVertex();
            tess.draw();
        }
    }
}
