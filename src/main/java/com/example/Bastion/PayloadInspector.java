package com.example.bastion;

import com.example.bastion.ui.GuiSessionPrompt;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * PayloadInspector
 * - Wraps OutputStreams and inspects payloads for sensitive data.
 * - CRITICAL matches (session, tokens, webhooks) freeze until user override.
 * - Uses GuiSessionPrompt.queue(...) for FORCE decision UI.
 */
public class PayloadInspector extends OutputStream {
    private final OutputStream delegate;
    private final URL targetUrl;
    private final String modName;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    public PayloadInspector(OutputStream delegate, URL url, String modName) {
        this.delegate = delegate;
        this.targetUrl = url;
        this.modName = modName;
    }

    @Override public void write(int b) throws IOException { buffer.write(b); delegate.write(b); }
    @Override public void write(byte[] b, int off, int len) throws IOException { buffer.write(b, off, len); delegate.write(b, off, len); }
    @Override public void flush() throws IOException { delegate.flush(); }
    @Override public void close() throws IOException {
        delegate.close();
        inspectPayload();
    }

    private void inspectPayload() {
        String data = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        if (data == null || data.isEmpty()) return;

        String lower = data.toLowerCase(Locale.ROOT);
        boolean sensitive = false;

        // === direct sensitive keywords ===
        if (lower.contains("session") || lower.contains("token") || lower.contains("uuid")
                || lower.contains("accesstoken") || lower.contains("sessiontoken")
                || lower.contains("password") || lower.contains("auth")
                || lower.contains("clientsecret")
                || lower.contains("discord.com/api/webhooks")
                || lower.contains("discordapp.com/api/webhooks")) {
            sensitive = true;
        }

        // === JWT detection ===
        if (!sensitive && lower.matches(".*eyj[a-z0-9._-]{10,}.*")) {
            sensitive = true;
        }

        // === Base64 decode check ===
        if (!sensitive && lower.matches(".*[A-Za-z0-9+/=]{40,}.*")) {
            try {
                byte[] decoded = Base64.getDecoder().decode(lower.replaceAll("[^A-Za-z0-9+/=]", ""));
                String decodedStr = new String(decoded, StandardCharsets.UTF_8);
                if (decodedStr.contains("discord.com/api/webhooks") || decodedStr.contains("discordapp.com/api/webhooks")) {
                    sensitive = true;
                }
            } catch (Exception ignored) {}
        }

        if (sensitive) {
            final String reason = "[Bastion] " + modName + " attempted to send sensitive data → " + targetUrl;

            // freeze until decision
            final CountDownLatch latch = new CountDownLatch(1);

            GuiSessionPrompt.queue(modName, reason,
                    () -> { BastionCore.getInstance().recordDecision(modName, null, null, BastionCore.DecisionState.APPROVED, false); latch.countDown(); },
                    () -> { BastionCore.getInstance().recordDecision(modName, null, null, BastionCore.DecisionState.DENIED, false); latch.countDown(); }
            );

            try {
                if (!latch.await(15, TimeUnit.SECONDS)) {
                    BastionCore.getInstance().setDecision(modName, BastionCore.DecisionState.DENIED, false);
                    throw new RuntimeException("[Bastion] Timed out waiting for override decision.");
                }
            } catch (InterruptedException e) {
                throw new RuntimeException("[Bastion] Interrupted while waiting for override decision.", e);
            }

            if (BastionCore.getInstance().queryDecision(modName, null, null) != BastionCore.DecisionState.APPROVED) {
                throw new RuntimeException("[Bastion] Sensitive payload blocked for " + modName);
            }
        }
    }
}
