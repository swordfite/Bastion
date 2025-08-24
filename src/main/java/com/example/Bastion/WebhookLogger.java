package com.example.bastion;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class WebhookLogger {

    public static void send(String url, String content) throws Exception {
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        String payload = "{\"content\":\"" + content.replace("\"", "\\\"") + "\"}";
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }

        conn.getInputStream().close();
        conn.disconnect();
    }

    public static void sendTest(String content) {
        try {
            BastionCore core = BastionCore.getInstance();
            if (core.isWebhookEnabled()) {
                send(core.getWebhookUrl(), "[Bastion] " + content);
            }
        } catch (Exception e) {
            BastionLogger.error("Something bad", e);

        }
    }
}
