package com.example.bastion;

import com.example.bastion.ui.GuiSessionPrompt;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BastionSocketFactory implements SocketImplFactory {

    // Pending approvals: modName -> list of blocked endpoints
    private static final Map<String, List<SocketAddress>> pending = new ConcurrentHashMap<>();

    @Override
    public SocketImpl createSocketImpl() {
        return new BastionSocketImpl();
    }

    // === Inner class ===
    public static class BastionSocketImpl extends SocketImpl {
        private final Socket delegate = new Socket();

        @Override
        protected void create(boolean stream) throws IOException {
            // no-op (delegate handles real creation)
        }

        @Override
        protected void connect(String host, int port) throws IOException {
            checkBlocked(host);
            delegate.connect(new InetSocketAddress(host, port));
        }

        @Override
        protected void connect(InetAddress address, int port) throws IOException {
            checkBlocked(address.getHostName());
            delegate.connect(new InetSocketAddress(address, port));
        }

        @Override
        protected void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (endpoint instanceof InetSocketAddress) {
                InetSocketAddress isa = (InetSocketAddress) endpoint;
                checkBlocked(isa.getHostString());
            }
            delegate.connect(endpoint, timeout);
        }

        @Override
        protected void bind(InetAddress host, int port) throws IOException {
            delegate.bind(new InetSocketAddress(host, port));
        }

        @Override
        protected void listen(int backlog) throws IOException {
            // not needed for client sockets
        }

        @Override
        protected void accept(SocketImpl s) throws IOException {
            throw new IOException("Bastion: accept not supported");
        }

        @Override
        protected InputStream getInputStream() throws IOException {
            return delegate.getInputStream();
        }

        @Override
        protected OutputStream getOutputStream() throws IOException {
            return delegate.getOutputStream();
        }

        @Override
        protected int available() throws IOException {
            return delegate.getInputStream().available();
        }

        @Override
        protected void close() throws IOException {
            delegate.close();
        }

        @Override
        protected void sendUrgentData(int data) throws IOException {
            delegate.sendUrgentData(data);
        }

        @Override
        public Object getOption(int optID) throws SocketException {
            // Simplified: not forwarding socket options
            return null;
        }

        @Override
        public void setOption(int optID, Object value) throws SocketException {
            // Simplified: ignore socket option changes
        }

        // === Bastion interception ===
        private void checkBlocked(String host) throws IOException {
            if (host != null && host.contains("discord.com")) {
                String modName = "UNKNOWN_MOD";

                BastionCore core = BastionCore.getInstance();

                if (core.isDenied(modName)) {
                    throw new IOException("Bastion: Blocked Discord webhook from " + modName);
                }

                if (!core.isApproved(modName)) {
                    pending.computeIfAbsent(modName, k -> new ArrayList<>())
                            .add(new InetSocketAddress(host, 443));

                    Minecraft.getMinecraft().addScheduledTask(() -> {
                        GuiSessionPrompt.open("Discord Webhook");
                    });

                    throw new IOException("Bastion: Suspended Discord webhook from " + modName);
                }
            }
        }

        // === Resolve from GUI ===
        public static void resolvePending(String modName, boolean approved) {
            if (approved) {
                BastionCore.getInstance().addApproved(modName);
                List<SocketAddress> addrs = pending.remove(modName);
                if (addrs != null) {
                    for (SocketAddress a : addrs) {
                        System.out.println("[Bastion] Approved + would release " + a);
                        // optional: retry logic
                    }
                }
            } else {
                BastionCore.getInstance().addBlocked(modName);
                pending.remove(modName);
            }
        }
    }
}
