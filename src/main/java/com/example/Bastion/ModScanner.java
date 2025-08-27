package com.example.bastion;

import com.example.bastion.ui.ToastManager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ModScanner
 * Scans all mods in /mods at startup.
 * - Reports ALL possible exfiltration indicators (UUID, session, tokens, webhooks).
 * - Always inventories ALL .jar files in mods folder.
 * - Enforcement/approval is handled by BastionCore.
 */
public class ModScanner {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[a-zA-Z0-9./?=_-]+");

    // === Entry point ===
    public static void scanMods() {
        File modsDir = new File("mods");
        File[] files = modsDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files == null || files.length == 0) {
            BastionCore.getInstance().log("[BastionScan] No mods found.");
            return;
        }

        BastionCore core = BastionCore.getInstance();
        core.log("[BastionScan] Starting mod scan...");
        core.sendWebhook("[BastionScan] Starting mod scan...");

        // First, run analysis on each mod
        for (File mod : files) {
            scanSingleMod(mod);
        }

        // Then always inventory dump ALL mods
        core.sendWebhook("[BastionScan] Inventory complete:");
        for (File mod : files) {
            String id = getModId(mod);
            String name = mod.getName();
            if (id == null || id.isEmpty()) id = name.replace(".jar", "");
            core.log("[BastionScan]  - " + id + " (file: " + name + ")");
            core.sendWebhook("[BastionScan]  - " + id + " (file: " + name + ")");
        }
    }

    private static void scanSingleMod(final File mod) {
        try {
            final String modId = getModId(mod);
            final String modName = mod.getName();

            // Skip Bastion itself
            if (BastionCore.getInstance().isSelf(modId) || modName.toLowerCase(Locale.ROOT).contains("bastion")) {
                return;
            }

            BastionCore core = BastionCore.getInstance();
            List<String> findings = analyzeJar(mod);
            if (findings.isEmpty()) return;

            final String label = (modId != null ? modId : modName) + " (file: " + modName + ")";
            final String msg = "[StaticScan] Suspicious indicators in " + label + ": " + findings;

            core.fireSuspicious(modId, null, null, msg);
            if (findings.toString().toLowerCase(Locale.ROOT).contains("session") ||
                    findings.toString().toLowerCase(Locale.ROOT).contains("token")) {
                core.fireSession(modId, msg);
            }

            core.requestStaticScanApproval(modId, mod, findings)
                    .thenAccept(allowed -> {
                        if (allowed) {
                            core.log("[BastionScan] User approved " + label);
                        } else {
                            core.log("[BastionScan] User denied " + label);
                        }
                    });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // === JAR Analysis ===
    private static List<String> analyzeJar(File jar) {
        List<String> findings = new ArrayList<>();

        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;

                String text = readEntry(zip, entry);

                if (detectWebhook(text)) findings.add("Discord webhook endpoint");
                if (BastionCore.containsTokenData(text)) findings.add("Session/token markers");

                if (text.contains("uuid") || text.contains("userid")) {
                    if (text.contains("discord.com/api/webhooks") || text.contains("discordapp.com/api/webhooks")) {
                        findings.add("Potential ID exfiltration");
                    } else if ((text.contains("http") || text.contains("https")) &&
                            (text.contains("post") || text.contains("send") || text.contains("upload"))) {
                        findings.add("Potential ID exfiltration");
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return findings;
    }

    private static String readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream is = zip.getInputStream(entry)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = is.read(tmp)) != -1) buffer.write(tmp, 0, n);
            return new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        }
    }

    private static boolean detectWebhook(String text) {
        if (text.contains("discord.com/api/webhooks") || text.contains("discordapp.com/api/webhooks"))
            return true;

        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            String found = m.group();
            if (found.contains("discord.com/api/webhooks") || found.contains("discordapp.com/api/webhooks"))
                return true;
        }
        return false;
    }

    private static String getModId(File mod) {
        try (ZipFile zip = new ZipFile(mod)) {
            ZipEntry entry = zip.getEntry("mcmod.info");
            if (entry != null) {
                try (InputStream is = zip.getInputStream(entry)) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                    String json = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                    if (json.contains("\"modid\"")) {
                        int idx = json.indexOf("\"modid\"");
                        int start = json.indexOf(":", idx) + 1;
                        int end = json.indexOf(",", start);
                        if (end == -1) end = json.length();
                        return json.substring(start, end).replaceAll("[^a-zA-Z0-9_.-]", "").trim();
                    }
                }
            }
        } catch (Exception ignored) {}
        return mod.getName().replace(".jar", "");
    }
}
