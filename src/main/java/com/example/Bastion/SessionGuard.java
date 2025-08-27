package com.example.bastion;

import com.example.bastion.ui.ToastManager;
import com.example.bastion.ui.GuiSessionPrompt;
import net.minecraft.client.Minecraft;

/**
 * SessionGuard
 * Central handler for token and session abuse.
 * - Called whenever a mod touches or transmits the session token.
 * - Always shows both modid and actual JAR filename (via ModCaller.resolveModName).
 */
public class SessionGuard {

    /**
     * Called whenever a mod touches Minecraft's session ID or token.
     * This alerts the player immediately, even if it doesn't leave the JVM yet.
     */
    public static void onTokenAccess(String token, String method, String modName) {
        String msg = "[Bastion] " + modName + " accessed session token via " + method;
        System.out.println(msg);

        // UI feedback
        ToastManager.addToast(msg, 0xFFAA00); // yellow = warning
        Minecraft.getMinecraft().addScheduledTask(() ->
                GuiSessionPrompt.open(modName, "Token access via " + method)
        );

        BastionCore.getInstance().sendWebhook(msg);
    }

    /**
     * Called when Bastion detects that a mod is attempting
     * to transmit the token over the network.
     */
    public static void handleSuspiciousSessionUse(String modName, String host) {
        String msg = "[Bastion] " + modName + " tried to send your session ID → " + host;
        System.out.println(msg);

        // UI feedback
        ToastManager.addToast(msg, 0xFF5555); // red = danger
        Minecraft.getMinecraft().addScheduledTask(() ->
                GuiSessionPrompt.open(modName, "Session exfil attempt → " + host)
        );

        // Remote log
        BastionCore.getInstance().sendWebhook(msg);
    }

    // === Backward compatibility (fallback for callers that didn’t pass modName) ===
    public static void onTokenAccess(String token, String method) {
        onTokenAccess(token, method, "UnknownMod (UnknownFile.jar)");
    }

    public static void handleSuspiciousSessionUse(String host) {
        handleSuspiciousSessionUse("UnknownMod (UnknownFile.jar)", host);
    }
}
