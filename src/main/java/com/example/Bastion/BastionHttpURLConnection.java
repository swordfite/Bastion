package com.example.bastion;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

public class BastionHttpURLConnection extends HttpURLConnection {

    private final HttpURLConnection delegate;
    private final String modName;

    protected BastionHttpURLConnection(HttpURLConnection delegate, String modName) {
        super(delegate.getURL());
        this.delegate = delegate;
        this.modName = modName;
    }

    public static BastionHttpURLConnection wrap(HttpURLConnection conn) {
        return new BastionHttpURLConnection(conn, identifyCaller());
    }

    @Override
    public void connect() throws IOException {
        enforceDecision("HTTP → connect");
        delegate.connect();
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        enforceDecision("HTTP → output");
        return new InterceptingOutputStream(delegate.getOutputStream(), modName, delegate.getURL());
    }

    @Override
    public int getResponseCode() throws IOException {
        enforceDecision("HTTP → responseCode");
        return delegate.getResponseCode();
    }

    @Override public void disconnect() { delegate.disconnect(); }
    @Override public boolean usingProxy() { return delegate.usingProxy(); }

    private void enforceDecision(String reason) throws IOException {
        BastionCore core = BastionCore.getInstance();
        URL url = delegate.getURL();
        String hostKey = BastionCore.normalizeHostPort(
                url.getHost(),
                url.getPort() > 0 ? url.getPort() : url.getDefaultPort()
        );

        // Request approval asynchronously
        CompletableFuture<Boolean> future =
                core.requestApproval(modName, hostKey, url.toString(), reason);

        try {
            boolean allowed = core.awaitApproval(modName, hostKey, url.toString(), future);
            if (!allowed) {
                throw new IOException("[Bastion] Blocked → " + modName + " → " + url);
            }
        } catch (Exception e) {
            throw new IOException("[Bastion] Decision error → " + modName + " → " + url, e);
        }
    }


    public static class InterceptingOutputStream extends FilterOutputStream {
        private final String modName;
        private final URL url;

        InterceptingOutputStream(OutputStream delegate, String modName, URL url) {
            super(delegate);
            this.modName = modName;
            this.url = url;
        }

        private void enforce(String action) throws IOException {
            BastionCore core = BastionCore.getInstance();
            String hostKey = BastionCore.normalizeHostPort(
                    url.getHost(),
                    url.getPort() > 0 ? url.getPort() : url.getDefaultPort()
            );
            core.enforceDecision(modName, hostKey, url.toString(), action);
        }

        @Override
        public void write(int b) throws IOException {
            enforce("HTTP → write");
            super.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            enforce("HTTP → write");
            super.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            enforce("HTTP → flush");
            super.flush();
        }
    }

    public static String identifyCaller() {
        for (StackTraceElement el : Thread.currentThread().getStackTrace()) {
            String cls = el.getClassName();
            if (cls.startsWith("com.example.bastion")) continue;
            if (cls.startsWith("java.") || cls.startsWith("sun.") || cls.startsWith("javax.")) continue;
            return ModCaller.resolveModName(cls);
        }
        return "UnknownMod (UnknownFile.jar)";
    }
}
