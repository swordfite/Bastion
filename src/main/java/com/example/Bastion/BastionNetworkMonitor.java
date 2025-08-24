package com.example.bastion;

import com.example.bastion.ui.ToastManager;
import com.example.bastion.ui.GuiSessionPrompt;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class BastionNetworkMonitor {

    public static void install() {
        System.setProperty("java.net.useSystemProxies", "true");
        System.out.println("[Bastion] Network monitor active.");
    }

    // Wrap any HttpURLConnection with our guard
    public static HttpURLConnection wrap(HttpURLConnection conn) {
        return new BastionHttpURLConnection(conn);
    }

    private static class BastionHttpURLConnection extends HttpURLConnection {
        private final HttpURLConnection delegate;

        protected BastionHttpURLConnection(HttpURLConnection delegate) {
            super(delegate.getURL());
            this.delegate = delegate;
        }

        @Override
        public void connect() throws IOException {
            checkConnection();
            delegate.connect();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return new BastionOutputStream(delegate.getOutputStream(), delegate.getURL());
        }

        @Override
        public int getResponseCode() throws IOException {
            checkConnection();
            return delegate.getResponseCode();
        }

        private void checkConnection() {
            String host = delegate.getURL().getHost();

            if (!isWhitelisted(host)) {
                BastionCore core = BastionCore.getInstance();

                if (core.isBlocked(host)) {
                    throw new SecurityException("[Bastion] Blocked connection to: " + host);
                } else if (!core.isApproved(host)) {
                    String modName = findCallingMod();

                    ToastManager.addToast("[Bastion] " + modName + " → " + host);

                    Minecraft.getMinecraft().addScheduledTask(() ->
                            Minecraft.getMinecraft().displayGuiScreen(
                                    new GuiSessionPrompt(modName + " contacting " + host)
                            )
                    );
                }
            }
        }

        private boolean isWhitelisted(String host) {
            return host.endsWith("mojang.com") || host.endsWith("minecraft.net");
        }

        @Override public void disconnect() { delegate.disconnect(); }
        @Override public boolean usingProxy() { return delegate.usingProxy(); }
    }

    private static class BastionOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final URL url;
        private final StringBuilder buffer = new StringBuilder();

        BastionOutputStream(OutputStream delegate, URL url) {
            this.delegate = delegate;
            this.url = url;
        }

        @Override
        public void write(int b) throws IOException {
            buffer.append((char) b);
            delegate.write(b);
        }

        @Override public void flush() throws IOException { delegate.flush(); }

        @Override public void close() throws IOException {
            delegate.close();
            String data = buffer.toString();
            if (data.contains("session") || data.contains("token")) {
                String host = url.getHost();
                String modName = findCallingMod();

                ToastManager.addToast("[Bastion] " + modName + " tried sending session-like data → " + host);

                Minecraft.getMinecraft().addScheduledTask(() ->
                        Minecraft.getMinecraft().displayGuiScreen(
                                new GuiSessionPrompt(modName + " sending session-like data to " + host)
                        )
                );
            }
        }
    }

    // Identify mod based on first non-vanilla class in stack trace
    private static String findCallingMod() {
        for (StackTraceElement el : Thread.currentThread().getStackTrace()) {
            String cls = el.getClassName();
            if (cls.startsWith("net.minecraft") || cls.startsWith("java.")) continue;
            return cls;
        }
        return "Unknown Mod";
    }
}
