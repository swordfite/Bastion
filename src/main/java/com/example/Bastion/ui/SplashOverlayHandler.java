package com.example.bastion.ui;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;

/**
 * SplashOverlayHandler
 * - Prevents prompts from being buried under Forge's splash screen.
 * - Uses reflection to detect when SplashProgress is still running.
 */
public class SplashOverlayHandler {

    private static Method isRunningMethod;

    static {
        try {
            Class<?> splash = Class.forName("net.minecraftforge.fml.client.SplashProgress");
            isRunningMethod = splash.getDeclaredMethod("isRunning");
            isRunningMethod.setAccessible(true);
        } catch (Exception e) {
            System.err.println("[Bastion] Could not hook SplashProgress: " + e.getMessage());
            isRunningMethod = null;
        }
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new SplashOverlayHandler());
    }

    private boolean isSplashRunning() {
        if (isRunningMethod == null) return false;
        try {
            return (Boolean) isRunningMethod.invoke(null);
        } catch (Exception e) {
            return false;
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase == TickEvent.Phase.END) {
            if (!isSplashRunning() && GuiSessionPrompt.hasActivePrompt()) {
                GuiSessionPrompt active = GuiSessionPrompt.getActivePrompt();
                if (active != null && !active.isDecisionMade()) {
                    if (Minecraft.getMinecraft().currentScreen != active) {
                        Minecraft.getMinecraft().displayGuiScreen(active);
                    }
                }
            }
        }
    }
}
