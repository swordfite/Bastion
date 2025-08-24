package com.example.bastion;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Central allow/deny manager + webhook logger for Bastion.
 */
public class BastionCore {
    private static final BastionCore INSTANCE = new BastionCore();

    private final Set<String> approved = new HashSet<>();
    private final Set<String> blocked = new HashSet<>();

    // --- Webhook config ---
    private boolean webhookEnabled = false; // set to false if you don’t want reporting
    private String webhookUrl =
            "WEBHOOKLINKHERE";

    // throttle: only 1 webhook send every 5 seconds
    private long lastWebhookAttempt = 0;

    private BastionCore() {}

    public static BastionCore getInstance() {
        return INSTANCE;
    }

    // === Access control ===
    public boolean isApproved(String key) {
        return approved.contains(key);
    }

    public boolean isBlocked(String key) {
        return blocked.contains(key);
    }

    public void approve(String key) {
        approved.add(key);
    }

    public void block(String key) {
        blocked.add(key);
    }

    // Legacy aliases
    public void addApproved(String key) { approve(key); }
    public void addBlocked(String key) { block(key); }
    public boolean isDenied(String key) { return isBlocked(key); }

    // === Webhook config ===
    public boolean isWebhookEnabled() { return webhookEnabled; }
    public void setWebhookEnabled(boolean enabled) { this.webhookEnabled = enabled; }
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String url) { this.webhookUrl = url; }

    // === Webhook sending ===
    public void sendWebhook(String message) {
        if (!webhookEnabled || webhookUrl == null || webhookUrl.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (now - lastWebhookAttempt < 5000) {
            // Too soon since last webhook -> skip
            return;
        }
        lastWebhookAttempt = now;

        new Thread(() -> {
            try {
                URL u = new URL(webhookUrl);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                String safeMessage = (message == null || message.trim().isEmpty())
                        ? "[Bastion] Ping."
                        : message;

                String payload = "{\"content\":\"" + safeMessage.replace("\"", "\\\"") + "\"}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    BastionLogger.warn("[Bastion] Webhook rejected with HTTP " + code);
                } else {
                    BastionLogger.info("[Bastion] Webhook delivered successfully.");
                }

                conn.getInputStream().close();
                conn.disconnect();
            } catch (Exception e) {
                BastionLogger.error("[Bastion] Failed to send webhook", e);
            }
        }, "Bastion-WebhookThread").start();
    }

    // Convenience wrapper for startup
    public void sendTestWebhook(String msg) {
        sendWebhook("[Bastion] " + msg);
    }
}
