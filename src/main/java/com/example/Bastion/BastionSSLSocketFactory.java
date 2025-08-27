package com.example.bastion;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * BastionSSLSocketFactory (Paranoid Rewrite)
 * Replaces the default SSLSocketFactory with Bastion enforcement.
 * Bastion’s own webhook traffic is NEVER intercepted.
 * All other SSL sockets are delayed until explicit user approval.
 *
 * This version is intentionally verbose, redundant, and over-engineered
 * to avoid edge-case bugs at the cost of line count.
 */
public class BastionSSLSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;

    public BastionSSLSocketFactory() {
        // Always wrap the JVM default factory, never recurse
        this.delegate = (SSLSocketFactory) SSLSocketFactory.getDefault();
    }

    // === Main interception hook ===
    private Socket intercept(SSLSocket s, String host, int port) throws IOException {
        BastionCore core = BastionCore.getInstance();
        core.log("[SSL][DEBUG] Intercepting new socket -> " + host + ":" + port);

        // 1. Bypass Bastion webhook
        if (isBastionWebhook(core, host, port)) {
            core.log("[SSL][Bypass] Recognized Bastion webhook target -> " + host + ":" + port);
            return s;
        }

        // 2. Bypass internal Bastion calls
        if (isBastionCaller()) {
            core.log("[SSL][Bypass] Detected Bastion-internal caller -> " + host + ":" + port);
            return s;
        }

        // 3. Identify mod responsible
        String modId = resolveCallerMod();
        if (modId == null) modId = "unknown-mod";
        core.log("[SSL][DEBUG] Socket belongs to modId=" + modId);

        String reason = "[" + modId + "] [SSL] Socket connect -> " + host + ":" + port;

        // 4. Decision pipeline
        BastionCore.DecisionState state = core.queryDecision(modId, host, null);
        if (state == BastionCore.DecisionState.DENIED) {
            core.log("[SSL][BLOCK] Auto-blocked by remembered decision -> " + host + ":" + port);
            s.close();
            throw new IOException("[Bastion] Blocked (SSL) -> " + modId + " -> " + host + ":" + port);
        }
        if (state == BastionCore.DecisionState.APPROVED) {
            core.log("[SSL][ALLOW] Auto-approved by remembered decision -> " + host + ":" + port);
            return s;
        }

        // 5. Guarded socket (buffers writes until decision)
        core.log("[SSL][WAIT] No decision yet -> Wrapping with GuardedSSLSocket");
        return new GuardedSSLSocket(s, modId, host, port, reason, core);
    }

    // === Helper: Bastion webhook detection ===
    private boolean isBastionWebhook(BastionCore core, String host, int port) {
        try {
            String webhook = core.getWebhookUrl();
            if (webhook == null || webhook.trim().isEmpty()) return false;

            URL u = new URL(webhook);
            String whHost = u.getHost();
            int whPort = (u.getPort() == -1 ? 443 : u.getPort());

            // Match host + port
            if (whHost.equalsIgnoreCase(host) && whPort == port) return true;

            // Resolve IPs and match them (extra safety)
            try {
                InetAddress[] targetIps = InetAddress.getAllByName(host);
                InetAddress[] webhookIps = InetAddress.getAllByName(whHost);
                for (InetAddress t : targetIps) {
                    for (InetAddress w : webhookIps) {
                        if (t.equals(w) && whPort == port) {
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {}

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // === Helper: Detect if call came from Bastion itself ===
    private boolean isBastionCaller() {
        for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
            if (ste.getClassName().startsWith("com.example.bastion")) {
                return true;
            }
        }
        return false;
    }

    // === Helper: Resolve the mod responsible ===
    private String resolveCallerMod() {
        try {
            for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
                String clsName = ste.getClassName();
                if (clsName.startsWith("java.") ||
                        clsName.startsWith("sun.") ||
                        clsName.startsWith("com.example.bastion")) continue;

                Class<?> cls = Class.forName(clsName, false,
                        Thread.currentThread().getContextClassLoader());

                URL location = cls.getProtectionDomain().getCodeSource().getLocation();
                if (location == null) continue;

                File jar = new File(location.toURI());

                for (ModContainer mod : Loader.instance().getActiveModList()) {
                    File modFile = mod.getSource();
                    if (modFile != null && modFile.equals(jar)) {
                        return mod.getModId();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // === Delegated methods ===
    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return intercept((SSLSocket) delegate.createSocket(s, host, port, autoClose), host, port);
    }
    @Override public Socket createSocket(String host, int port) throws IOException {
        return intercept((SSLSocket) delegate.createSocket(host, port), host, port);
    }
    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return intercept((SSLSocket) delegate.createSocket(host, port, localHost, localPort), host, port);
    }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
        return intercept((SSLSocket) delegate.createSocket(host, port), host.getHostAddress(), port);
    }
    @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return intercept((SSLSocket) delegate.createSocket(address, port, localAddress, localPort),
                address.getHostAddress(), port);
    }

    // === Guarded SSL socket (buffers until decision) ===
    private static class GuardedSSLSocket extends SSLSocket {
        private final SSLSocket delegate;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final String host;
        private final int port;
        private final BastionCore core;
        private final String reason;

        private volatile boolean decided = false;
        private volatile boolean approved = false;

        GuardedSSLSocket(SSLSocket delegate, String modId, String host, int port,
                         String reason, BastionCore core) throws IOException {
            this.delegate = delegate;
            this.host = host;
            this.port = port;
            this.core = core;
            this.reason = reason;

            // Block until approval synchronously (no async stall)
            try {
                CompletableFuture<Boolean> future = core.requestApproval(modId, host, null, reason);
                boolean allowed = core.awaitApproval(modId, host, null, future);

                decided = true;
                approved = allowed;

                if (!approved) {
                    core.log("[SSL][BLOCK] User denied -> closing socket " + host + ":" + port);
                    delegate.close();
                    throw new IOException("[Bastion] Blocked SSL -> " + modId + " -> " + host + ":" + port);
                }

                core.log("[SSL][ALLOW] User approved -> performing handshake " + host + ":" + port);
                delegate.startHandshake();

            } catch (Exception e) {
                core.log("[SSL][ERROR] Approval/handshake failed: " + e.getMessage());
                try { delegate.close(); } catch (IOException ignored) {}
                throw new IOException("[Bastion] SSL approval/handshake failure for " + host + ":" + port, e);
            }
        }

        @Override public OutputStream getOutputStream() throws IOException { return delegate.getOutputStream(); }
        @Override public InputStream getInputStream() throws IOException { return delegate.getInputStream(); }

        // === Full delegate for all SSLSocket methods ===
        @Override public void startHandshake() throws IOException { delegate.startHandshake(); }
        @Override public void setUseClientMode(boolean mode) { delegate.setUseClientMode(mode); }
        @Override public boolean getUseClientMode() { return delegate.getUseClientMode(); }
        @Override public void setNeedClientAuth(boolean need) { delegate.setNeedClientAuth(need); }
        @Override public boolean getNeedClientAuth() { return delegate.getNeedClientAuth(); }
        @Override public void setWantClientAuth(boolean want) { delegate.setWantClientAuth(want); }
        @Override public boolean getWantClientAuth() { return delegate.getWantClientAuth(); }
        @Override public void setEnableSessionCreation(boolean flag) { delegate.setEnableSessionCreation(flag); }
        @Override public boolean getEnableSessionCreation() { return delegate.getEnableSessionCreation(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public String[] getEnabledCipherSuites() { return delegate.getEnabledCipherSuites(); }
        @Override public void setEnabledCipherSuites(String[] suites) { delegate.setEnabledCipherSuites(suites); }
        @Override public String[] getSupportedProtocols() { return delegate.getSupportedProtocols(); }
        @Override public String[] getEnabledProtocols() { return delegate.getEnabledProtocols(); }
        @Override public void setEnabledProtocols(String[] protocols) { delegate.setEnabledProtocols(protocols); }
        @Override public SSLSession getSession() { return delegate.getSession(); }
        @Override public void addHandshakeCompletedListener(HandshakeCompletedListener l) { delegate.addHandshakeCompletedListener(l); }
        @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener l) { delegate.removeHandshakeCompletedListener(l); }
        @Override public void close() throws IOException { delegate.close(); }
        @Override public void connect(SocketAddress endpoint) throws IOException { delegate.connect(endpoint); }
        @Override public void connect(SocketAddress endpoint, int timeout) throws IOException { delegate.connect(endpoint, timeout); }
        @Override public void bind(SocketAddress bindpoint) throws IOException { delegate.bind(bindpoint); }
        @Override public InetAddress getInetAddress() { return delegate.getInetAddress(); }
        @Override public InetAddress getLocalAddress() { return delegate.getLocalAddress(); }
        @Override public int getPort() { return delegate.getPort(); }
        @Override public int getLocalPort() { return delegate.getLocalPort(); }
        @Override public SocketAddress getRemoteSocketAddress() { return delegate.getRemoteSocketAddress(); }
        @Override public SocketAddress getLocalSocketAddress() { return delegate.getLocalSocketAddress(); }
        @Override public void setTcpNoDelay(boolean on) throws SocketException { delegate.setTcpNoDelay(on); }
        @Override public boolean getTcpNoDelay() throws SocketException { return delegate.getTcpNoDelay(); }
        @Override public void setSoLinger(boolean on, int linger) throws SocketException { delegate.setSoLinger(on, linger); }
        @Override public int getSoLinger() throws SocketException { return delegate.getSoLinger(); }
        @Override public void sendUrgentData(int data) throws IOException { delegate.sendUrgentData(data); }
        @Override public void setOOBInline(boolean on) throws SocketException { delegate.setOOBInline(on); }
        @Override public boolean getOOBInline() throws SocketException { return delegate.getOOBInline(); }
        @Override public void setSoTimeout(int timeout) throws SocketException { delegate.setSoTimeout(timeout); }
        @Override public int getSoTimeout() throws SocketException { return delegate.getSoTimeout(); }
        @Override public void setSendBufferSize(int size) throws SocketException { delegate.setSendBufferSize(size); }
        @Override public int getSendBufferSize() throws SocketException { return delegate.getSendBufferSize(); }
        @Override public void setReceiveBufferSize(int size) throws SocketException { delegate.setReceiveBufferSize(size); }
        @Override public int getReceiveBufferSize() throws SocketException { return delegate.getReceiveBufferSize(); }
        @Override public void setKeepAlive(boolean on) throws SocketException { delegate.setKeepAlive(on); }
        @Override public boolean getKeepAlive() throws SocketException { return delegate.getKeepAlive(); }
        @Override public void setTrafficClass(int tc) throws SocketException { delegate.setTrafficClass(tc); }
        @Override public int getTrafficClass() throws SocketException { return delegate.getTrafficClass(); }
        @Override public void setReuseAddress(boolean on) throws SocketException { delegate.setReuseAddress(on); }
        @Override public boolean getReuseAddress() throws SocketException { return delegate.getReuseAddress(); }
        @Override public void shutdownInput() throws IOException { delegate.shutdownInput(); }
        @Override public void shutdownOutput() throws IOException { delegate.shutdownOutput(); }
        @Override public String toString() { return delegate.toString(); }
        @Override public boolean isConnected() { return delegate.isConnected(); }
        @Override public boolean isBound() { return delegate.isBound(); }
        @Override public boolean isClosed() { return delegate.isClosed(); }
        @Override public boolean isInputShutdown() { return delegate.isInputShutdown(); }
        @Override public boolean isOutputShutdown() { return delegate.isOutputShutdown(); }
        @Override public void setPerformancePreferences(int c, int l, int b) {
            delegate.setPerformancePreferences(c, l, b);
        }
    }
}
