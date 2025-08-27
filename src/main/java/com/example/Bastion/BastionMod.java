package com.example.bastion;

import com.example.bastion.ui.GuiSessionPrompt;
import com.example.bastion.ui.ToastManager;
import com.example.bastion.ui.SplashOverlayHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.net.Socket;

/**
 * BastionMod
 * - Core entrypoint for Bastion security.
 * - Installs socket interception, registers handlers, and ensures
 *   prompts render even if other subsystems misbehave.
 */
@Mod(
        modid = BastionMod.MODID,
        name = BastionMod.NAME,
        version = BastionMod.VERSION,
        acceptedMinecraftVersions = "[1.8.9]"
)
public class BastionMod {
    public static final String MODID = "bastion";
    public static final String NAME = "Bastion";
    public static final String VERSION = "1.0";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        BastionLogger.info("[Bastion] Loaded. Standing guard over your session.");
        MinecraftForge.EVENT_BUS.register(this);

        // Register splash handler (prevents prompts from being hidden during loading screen)
        try {
            SplashOverlayHandler.register();
        } catch (Throwable t) {
            BastionLogger.warn("[Bastion] Failed to register splash overlay handler: " + t.getMessage());
        }

        // Install our socket interception layer
        try {
            Socket.setSocketImplFactory(new BastionSocketFactory()); // now valid, BastionSocketFactory implements SocketImplFactory
            BastionLogger.info("[Bastion] SocketImplFactory installed.");
        } catch (IOException | Error e) {
            BastionLogger.error("[Bastion] Could not install SocketImplFactory! Socket monitoring is DISABLED.");
            ToastManager.addToast(
                    "[Bastion] Socket monitoring could not be installed — another coremod may be interfering.",
                    0xFF0000
            );
        }

        // Run a mod scan on startup (inventory all JARs)
        try {
            ModScanner.scanMods();
        } catch (Throwable t) {
            BastionLogger.warn("[Bastion] Mod scan failed: " + t.getMessage());
        }

        // Startup webhook/log
        BastionCore core = BastionCore.getInstance();
        core.log("[Bastion] Startup complete — monitoring active.");
        core.sendWebhook("[Bastion] Startup complete. Monitoring outbound requests and session usage.");

        // Register prompt retry handler
        GuiSessionPrompt.registerRetryHandler();
    }

    // === HARD OVERRIDE to force prompt visibility ===
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase == TickEvent.Phase.END) {
            if (GuiSessionPrompt.hasActivePrompt()) {
                GuiSessionPrompt active = GuiSessionPrompt.getActivePrompt();
                if (active != null && !active.isDecisionMade()) {
                    if (Minecraft.getMinecraft().currentScreen != active) {
                        Minecraft.getMinecraft().displayGuiScreen(active);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase == TickEvent.Phase.END) {
            ToastManager.render(Minecraft.getMinecraft());
        }
    }
}
