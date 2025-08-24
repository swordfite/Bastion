package com.example.bastion;

import com.example.bastion.ui.ToastManager;
import com.example.bastion.ui.GuiSessionPrompt;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.io.File;

public class ModScanner {

    public static void scanMods() {
        File modsDir = new File("mods");
        File[] files = modsDir.listFiles((dir, name) -> name.endsWith(".jar"));

        if (files == null) return;

        for (File mod : files) {
            scanSingleMod(mod);
        }
    }

    private static void scanSingleMod(File mod) {
        try {
            ScanResult result = analyzeJar(mod);

            switch (result) {
                case WEBHOOK:
                    ToastManager.addToast("[Bastion] " + mod.getName() + " may use Discord webhooks.");
                    break;

                case USERINFO:
                    ToastManager.addToast("[Bastion] " + mod.getName() + " accesses username/UUID.");
                    break;

                case SESSIONID:
                    // Flag and block until the user explicitly decides
                    BastionCore.getInstance().addBlocked(mod.getName());
                    Minecraft.getMinecraft().addScheduledTask(() ->
                            Minecraft.getMinecraft().displayGuiScreen(new GuiSessionPrompt(mod.getName())));
                    break;

                case NONE:
                default:
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static ScanResult analyzeJar(File jar) {
        boolean touchesSession = false;
        boolean hasWebhook = false;
        boolean touchesUserInfo = false;

        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;

                try (InputStream is = zip.getInputStream(entry)) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] tmp = new byte[4096];
                    int n;
                    while ((n = is.read(tmp)) != -1) {
                        buffer.write(tmp, 0, n);
                    }
                    String text = new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);

                    // Check for session usage
                    if (text.contains("func_148254_d") || text.contains("field_148257_b") ||
                            text.contains("sessionID") || text.contains("net.minecraft.util.Session") ||
                            text.contains("YggdrasilAuthenticationService") ||
                            text.contains("MinecraftSessionService")) {
                        touchesSession = true;
                    }

                    // Check for user info
                    if (text.contains("func_111285_a") || text.contains("func_148255_b")) {
                        touchesUserInfo = true;
                    }

                    // Check for webhook calls
                    if (text.contains("discord.com/api/webhooks") ||
                            text.contains("discordapp.com/api/webhooks")) {
                        hasWebhook = true;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Decision logic
        if (touchesSession && hasWebhook) {
            return ScanResult.SESSIONID; // only suspicious when BOTH appear
        }
        if (hasWebhook) {
            return ScanResult.WEBHOOK;
        }
        if (touchesUserInfo) {
            return ScanResult.USERINFO;
        }
        return ScanResult.NONE;
    }

    private enum ScanResult {
        NONE, WEBHOOK, USERINFO, SESSIONID
    }
}
