package com.example.bastion;

import com.example.bastion.ui.GuiSessionPrompt;
import com.example.bastion.ui.ToastManager;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BastionNetworkMonitor
 * Legacy-wide HttpURLConnection wrapper.
 * Now uses BastionCore.queryDecision / recordDecision instead of old isApproved/isBlocked/addApproved.
 */
public class BastionNetworkMonitor {
    private static final Map<String, List<BastionHttpURLConnection>> pending = new ConcurrentHashMap<>();

    public static void install() {
        System.setProperty("java.net.useSystemProxies", "true");
        System.out.println("[Bastion] Network monitor active.");
    }

    public static HttpURLConnection wrap(HttpURLConnection conn) {
        return new BastionHttpURLConnection(conn);
    }

    private static class BastionHttpURLConnection extends HttpURLConnection {
        private final HttpURLConnection delegate;
        private final String modName;

        protected BastionHttpURLConnection(HttpURLConnection delegate) {
            super(delegate.getURL());
            this.delegate = delegate;
            this.modName = findCallingMod();
        }

        @Override
        public void connect() throws IOException {
            if (!checkConnection()) return;
            delegate.connect();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return new BastionOutputStream(delegate.getOutputStream(), delegate.getURL(), modName);
        }

        @Override
        public int getResponseCode() throws IOException {
            if (!checkConnection()) return 403;
            return delegate.getResponseCode();
        }

        private boolean checkConnection() throws IOException {
            String host = delegate.getURL().getHost();
            String fullUrl = delegate.getURL().toString();
            BastionCore core = BastionCore.getInstance();

            // vanilla endpoints always allowed
            if (host.endsWith("mojang.com") || host.endsWith("minecraft.net")) {
                return true;
            }

            BastionCore.DecisionState state = core.queryDecision(modName, host, fullUrl);

            // webhook-specific tightening
            if (fullUrl.contains("discord.com/api/webhooks") || fullUrl.contains("discordapp.com/api/webhooks")) {
                if (state == BastionCore.DecisionState.DENIED) {
                    throw new IOException("[Bastion] BLOCKED webhook from " + modName + " → " + fullUrl);
                }
                if (state == BastionCore.DecisionState.UNDECIDED) {
                    queueAndPrompt(fullUrl);
                    throw new IOException("[Bastion] Suspended webhook from " + modName);
                }
            }

            if (state == BastionCore.DecisionState.DENIED) {
                throw new IOException("[Bastion] Blocked connection from " + modName + " → " + host);
            }
            if (state == BastionCore.DecisionState.UNDECIDED) {
                queueAndPrompt(fullUrl);
                throw new IOException("[Bastion] Connection frozen pending approval");
            }
            return true; // APPROVED
        }

        private void queueAndPrompt(String target) {
            pending.computeIfAbsent(modName, k -> new ArrayList<>()).add(this);
            Minecraft.getMinecraft().addScheduledTask(() ->
                    GuiSessionPrompt.open(modName, "→ " + target,
                            () -> BastionCore.getInstance().recordDecision(modName, target, target,
                                    BastionCore.DecisionState.APPROVED, true),
                            () -> BastionCore.getInstance().recordDecision(modName, target, target,
                                    BastionCore.DecisionState.DENIED, true))
            );
            ToastManager.addToast("[Bastion] Suspicious connection from " + modName + " → " + target, 0xFFFF55);
            BastionCore.getInstance().sendWebhook("[Bastion] Suspicious connection from " + modName + " → " + target);
        }

        @Override public void disconnect() { delegate.disconnect(); }
        @Override public boolean usingProxy() { return delegate.usingProxy(); }
    }

    private static class BastionOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final URL url;
        private final String modName;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        BastionOutputStream(OutputStream delegate, URL url, String modName) {
            this.delegate = delegate;
            this.url = url;
            this.modName = modName;
        }

        @Override public void write(int b) throws IOException {
            buffer.write(b);
            delegate.write(b);
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            buffer.write(b, off, len);
            delegate.write(b, off, len);
        }

        @Override public void flush() throws IOException { delegate.flush(); }

        @Override public void close() throws IOException {
            delegate.close();
            String data = new String(buffer.toByteArray(), StandardCharsets.UTF_8);

            if (looksSensitive(data)) {
                String msg = "[Bastion] " + modName + " tried sending sensitive data → " + url;
                ToastManager.addToast(msg, 0xFF5555);
                BastionCore.getInstance().sendWebhook(msg);
                Minecraft.getMinecraft().addScheduledTask(() ->
                        GuiSessionPrompt.open(modName, "sending sensitive data to " + url.getHost())
                );
            }
        }

        private boolean looksSensitive(String data) {
            if (data == null || data.isEmpty()) return false;
            String lower = data.toLowerCase(Locale.ROOT);
            if (lower.contains("session") || lower.contains("token") || lower.contains("uuid")) return true;
            if (lower.contains("discord.com/api/webhooks") || lower.contains("discordapp.com/api/webhooks")) return true;
            if (lower.matches(".*[A-Za-z0-9+/=]{40,}.*")) {
                try {
                    byte[] decoded = Base64.getDecoder().decode(lower.replaceAll("[^A-Za-z0-9+/=]", ""));
                    String decodedStr = new String(decoded, StandardCharsets.UTF_8);
                    if (decodedStr.contains("discord.com/api/webhooks")) return true;
                } catch (Exception ignored) {}
            }
            return false;
        }
    }

    public static void resolvePending(String modName, BastionCore.DecisionState state, boolean remember) {
        BastionCore core = BastionCore.getInstance();
        if (state == BastionCore.DecisionState.APPROVED) {
            if (remember) core.recordDecision(modName, null, null, BastionCore.DecisionState.APPROVED, true);
            List<BastionHttpURLConnection> list = pending.remove(modName);
            if (list != null) {
                for (BastionHttpURLConnection conn : list) {
                    try {
                        conn.delegate.connect();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        } else {
            if (remember) core.recordDecision(modName, null, null, BastionCore.DecisionState.DENIED, true);
            pending.remove(modName);
        }
    }

    private static String findCallingMod() {
        for (StackTraceElement el : Thread.currentThread().getStackTrace()) {
            String cls = el.getClassName();
            if (cls.startsWith("net.minecraft") || cls.startsWith("java.") || cls.startsWith("sun.")) continue;
            return cls;
        }
        return "Unknown Mod";
    }
}
