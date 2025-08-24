package com.example.bastion;

import com.example.bastion.ui.ToastManager;
import com.example.bastion.ui.GuiSessionPrompt;
import net.minecraft.client.Minecraft;

public class SessionGuard {

    // Called by BastionNetworkMonitor whenever session-like data is suspected
    public static void handleSuspiciousSessionUse(String source, String host) {
        ToastManager.addToast("⚠ [Bastion] " + source + " tried to use your session ID → " + host);

        Minecraft.getMinecraft().addScheduledTask(() ->
                Minecraft.getMinecraft().displayGuiScreen(
                        new GuiSessionPrompt(source + " requesting session data for " + host)
                )
        );
    }
}
