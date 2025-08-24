package com.example.bastion.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ToastManager {

    private static final ResourceLocation DIRT_BG = new ResourceLocation("textures/blocks/dirt.png");
    private static final List<Toast> toasts = new ArrayList<>();

    public static void addToast(String message) {
        toasts.add(new Toast(message));
    }

    public static void render(Minecraft mc) {
        if (toasts.isEmpty()) return;

        ScaledResolution sr = new ScaledResolution(mc);
        int baseX = sr.getScaledWidth() - 170;
        int y = 10;

        Iterator<Toast> it = toasts.iterator();
        while (it.hasNext()) {
            Toast toast = it.next();
            if (toast.isFullyExpired()) {
                it.remove();
                continue;
            }
            toast.draw(mc, baseX, y);
            y += 42;
        }
    }

    private static class Toast {
        private final String message;
        private final long start;
        private final long duration = 15000; // visible
        private final long fadeTime = 1000; // animation after

        private Toast(String message) {
            this.message = message;
            this.start = System.currentTimeMillis();
        }

        private boolean isFullyExpired() {
            return System.currentTimeMillis() - start > duration + fadeTime;
        }

        private void draw(Minecraft mc, int x, int y) {
            long elapsed = System.currentTimeMillis() - start;
            float alpha = 1.0f;
            int yOffset = 0;

            // Fade/retract when in the last second
            if (elapsed > duration) {
                float progress = (float)(elapsed - duration) / fadeTime;
                progress = Math.min(progress, 1.0f);

                alpha = 1.0f - progress;       // fade out
                yOffset = -(int)(progress * 20); // slide up 20px
            }

            GlStateManager.enableBlend();
            GlStateManager.color(1f, 1f, 1f, alpha);

            // background
            mc.getTextureManager().bindTexture(DIRT_BG);
            mc.ingameGUI.drawTexturedModalRect(x, y + yOffset, 0, 0, 160, 32);

            // title
            mc.fontRendererObj.drawStringWithShadow("§6[Bastion]", x + 6, y + 6 + yOffset, 0xFFD700);

            // message
            mc.fontRendererObj.drawSplitString(message, x + 6, y + 18 + yOffset, 148, 0xFFFF55);

            GlStateManager.disableBlend();
            GlStateManager.color(1f, 1f, 1f, 1f); // reset alpha
        }
    }
}
