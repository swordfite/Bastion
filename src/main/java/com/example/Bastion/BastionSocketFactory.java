package com.example.bastion;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.util.concurrent.*;

/**
 * BastionSocketFactory
 * Full-fat version with:
 *  - Approval buffering
 *  - cancelPending cleanup
 *  - Debug logging
 *  - Urgent-data buffering
 *  - Self-webhook bypass
 *  - Fallback if PlainSocketImpl missing
 */
public class BastionSocketFactory implements SocketImplFactory {

    @Override
    public SocketImpl createSocketImpl() {
        return new InterceptingSocketImpl();
    }

    public static class InterceptingSocketImpl extends SocketImpl {

        private final SocketImpl realImpl;
        private final Class<?> realClass;

        private String remoteHost;
        private int remotePort;

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private volatile boolean decided = false;
        private volatile boolean approved = false;

        public InterceptingSocketImpl() {
            try {
                Class<?> clazz;
                try {
                    clazz = Class.forName("java.net.PlainSocketImpl");
                } catch (ClassNotFoundException e) {
                    clazz = Class.forName("java.net.SocksSocketImpl"); // fallback
                }
                this.realClass = clazz;
                this.realImpl = (SocketImpl) realClass.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                throw new RuntimeException("Failed to obtain default SocketImpl", t);
            }
        }

        // === Reflection helper ===
        private Object invoke(String name, Class<?>[] types, Object... args) throws IOException {
            try {
                Method m = realClass.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(realImpl, args);
            } catch (Throwable t) {
                if (t instanceof IOException) throw (IOException) t;
                throw new IOException("Reflection call failed: " + name, t);
            }
        }

        // === Approval pipeline ===
        private void queueApproval(InetSocketAddress target, String action) throws IOException {
            this.remoteHost = target.getHostName();
            this.remotePort = target.getPort();

            BastionCore core = BastionCore.getInstance();
            final String fMod = resolveCallerMod() != null ? resolveCallerMod() : "unknown-mod";
            final String fHost = this.remoteHost;
            final int fPort = this.remotePort;

            String hostKey = fHost + ":" + fPort;
            String reason = "[" + fMod + "] " + action + " -> " + hostKey;

            core.log("[DEBUG][SocketFactory] Request -> Mod=" + fMod + " Host=" + fHost + " Port=" + fPort);

            BastionCore.DecisionState state = core.queryDecision(fMod, hostKey, null);
            core.log("[DEBUG][SocketFactory] Decision lookup -> state=" + state + " for key=" + hostKey);

            if (state == BastionCore.DecisionState.APPROVED) {
                decided = true;
                approved = true;
                invoke("connect", new Class[]{SocketAddress.class, int.class}, target, 0);
                return;
            }
            if (state == BastionCore.DecisionState.DENIED) {
                decided = true;
                approved = false;
                try { invoke("close", new Class[]{}); } catch (IOException ignore) {}
                throw new IOException("[Bastion] Blocked -> " + fMod + " -> " + hostKey);
            }

            // Wait for approval
            CompletableFuture<Boolean> future = core.requestApproval(fMod, hostKey, null, reason);
            boolean allowed;
            try {
                allowed = core.awaitApproval(fMod, hostKey, null, future);
            } catch (Exception e) {
                throw new IOException("[Bastion] Approval wait failed for " + fMod + " -> " + hostKey, e);
            }

            decided = true;
            approved = allowed;

            if (approved) {
                try {
                    invoke("connect", new Class[]{SocketAddress.class, int.class}, target, 0);
                } catch (IOException e) {
                    throw new IOException("[Bastion] Failed to complete connect for " + fMod + " -> " + hostKey, e);
                }
            } else {
                try { invoke("close", new Class[]{}); } catch (IOException ignore) {}
                core.cancelPending(fMod, fHost, null);
                throw new IOException("[Bastion] Blocked -> " + fMod + " -> " + hostKey);
            }
        }

        private void flushBuffer() throws IOException {
            synchronized (buffer) {
                if (buffer.size() > 0) {
                    OutputStream os = (OutputStream) invoke("getOutputStream", new Class[]{});
                    os.write(buffer.toByteArray());
                    os.flush();
                    buffer.reset();
                }
            }
        }

        /** Match caller mod */
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

        // === Overridden SocketImpl methods ===
        @Override protected void create(boolean stream) throws IOException {
            invoke("create", new Class[]{boolean.class}, stream);
        }

        // --- connect overloads with self-webhook bypass ---
        @Override protected void connect(String host, int port) throws IOException {
            if (checkSelfWebhookBypass(host, port)) return;
            queueApproval(new InetSocketAddress(host, port), "Socket connect");
        }

        @Override protected void connect(InetAddress address, int port) throws IOException {
            if (checkSelfWebhookBypass(address.getHostName(), port)) return;
            queueApproval(new InetSocketAddress(address, port), "Socket connect");
        }

        @Override protected void connect(SocketAddress address, int timeout) throws IOException {
            if (address instanceof InetSocketAddress) {
                InetSocketAddress isa = (InetSocketAddress) address;
                if (checkSelfWebhookBypass(isa.getHostName(), isa.getPort())) return;
                queueApproval(isa, "Socket connect");
            } else {
                invoke("connect", new Class[]{SocketAddress.class, int.class}, address, timeout);
            }
        }

        private boolean checkSelfWebhookBypass(String host, int port) throws IOException {
            BastionCore core = BastionCore.getInstance();
            try {
                String webhook = core.getWebhookUrl();
                if (webhook != null && !webhook.isEmpty()) {
                    URL cfg = new URL(webhook);
                    if (host.equalsIgnoreCase(cfg.getHost())) {
                        core.log("[SocketFactory] Self-webhook bypass → " + host + ":" + port);
                        invoke("connect", new Class[]{String.class, int.class}, host, port);
                        return true;
                    }
                }
            } catch (Exception ignored) {}
            return false;
        }

        // --- I/O handling ---
        @Override protected InputStream getInputStream() throws IOException {
            return (InputStream) invoke("getInputStream", new Class[]{});
        }
        @Override protected OutputStream getOutputStream() throws IOException {
            OutputStream realOut = (OutputStream) invoke("getOutputStream", new Class[]{});
            return new OutputStream() {
                @Override public void write(int b) throws IOException {
                    synchronized (buffer) {
                        if (!decided) buffer.write(b);
                        else if (approved) { flushBuffer(); realOut.write(b); }
                        else throw new IOException("[Bastion] Outbound denied -> " + remoteHost + ":" + remotePort);
                    }
                }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    synchronized (buffer) {
                        if (!decided) buffer.write(b, off, len);
                        else if (approved) { flushBuffer(); realOut.write(b, off, len); }
                        else throw new IOException("[Bastion] Outbound denied -> " + remoteHost + ":" + remotePort);
                    }
                }
                @Override public void flush() throws IOException { realOut.flush(); }
                @Override public void close() throws IOException { realOut.close(); }
            };
        }

        // --- Lifecycle / cleanup ---
        @Override protected void close() throws IOException {
            try { invoke("close", new Class[]{}); }
            finally {
                BastionCore.getInstance().cancelPending(resolveCallerMod(), remoteHost, null);
            }
        }

        @Override protected void sendUrgentData(int data) throws IOException {
            synchronized (buffer) {
                if (!decided) buffer.write(data);
                else if (approved) { flushBuffer(); ((OutputStream) invoke("getOutputStream", new Class[]{})).write(data); }
                else throw new IOException("[Bastion] Urgent data denied → " + remoteHost + ":" + remotePort);
            }
        }

        // --- Other pass-throughs ---
        @Override protected void bind(InetAddress host, int port) throws IOException { invoke("bind", new Class[]{InetAddress.class, int.class}, host, port); }
        @Override protected void listen(int backlog) throws IOException { invoke("listen", new Class[]{int.class}, backlog); }
        @Override protected void accept(SocketImpl s) throws IOException { invoke("accept", new Class[]{SocketImpl.class}, s); }
        @Override protected int available() throws IOException { return (int) invoke("available", new Class[]{}); }
        @Override public void setOption(int optID, Object value) throws SocketException { try { invoke("setOption", new Class[]{int.class, Object.class}, optID, value); } catch (IOException e) { throw new SocketException(e.getMessage()); } }
        @Override public Object getOption(int optID) throws SocketException { try { return invoke("getOption", new Class[]{int.class}, optID); } catch (IOException e) { throw new SocketException(e.getMessage()); } }
    }
}
