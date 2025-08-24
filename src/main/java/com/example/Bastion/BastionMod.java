// BastionMod.java
package com.example.bastion;

import com.example.bastion.ui.GuiSessionPrompt;
import com.example.bastion.ui.ToastManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.util.HashSet;
import java.util.Set;

@Mod(
        modid = BastionMod.MODID,
        name = BastionMod.NAME,
        version = BastionMod.VERSION,
        acceptedMinecraftVersions = "[1.8.9]"
)
public class BastionMod {
    public static final String MODID = "bastion";
    public static final String NAME = "Bastion";
    public static final String VERSION = "1.0";

    // Replace this with your Bastion webhook URL if you want your own logs allowed
    private static final String BASTION_WEBHOOK = "discord.com/api/webhooks/YOUR_BASTION_WEBHOOK";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        BastionLogger.info("[Bastion] Loaded. Standing guard over your session.");
        MinecraftForge.EVENT_BUS.register(this);

        try {
            Socket.setSocketImplFactory(() -> new InterceptingSocketImpl());
            BastionLogger.info("[Bastion] SocketImplFactory installed.");
        } catch (IOException | Error e) {
            BastionLogger.warn("[Bastion] Could not install SocketImplFactory: " + e.getMessage());
        }

        ModScanner.scanMods();

        BastionCore.getInstance().sendTestWebhook(
                "Bastion started and is monitoring outbound requests."
        );
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase == TickEvent.Phase.END) {
            ToastManager.render(Minecraft.getMinecraft());
        }
    }

    // ---- SOCKET IMPL ----
    public static class InterceptingSocketImpl extends SocketImpl {
        private SocketChannel channel;
        private InetSocketAddress lastAddr;

        @Override
        protected void connect(String host, int port) throws IOException {
            attemptConnect(new InetSocketAddress(host, port));
        }

        @Override
        protected void connect(InetAddress address, int port) throws IOException {
            attemptConnect(new InetSocketAddress(address, port));
        }

        @Override
        protected void connect(SocketAddress address, int timeout) throws IOException {
            if (address instanceof InetSocketAddress) {
                attemptConnect((InetSocketAddress) address);
            }
        }

        private void attemptConnect(InetSocketAddress addr) throws IOException {
            String host = addr.getHostString();
            if (host != null && host.contains("discord.com")) {
                // If this is Bastion's own webhook, allow
                if (addr.toString().contains(BASTION_WEBHOOK)) {
                    channel = SocketChannel.open(addr);
                    return;
                }

                // Otherwise freeze + prompt
                lastAddr = addr;
                GuiSessionPrompt.open("Discord Webhook attempt: " + addr, this);

                PendingConnections.add(this);
                throw new IOException("[Bastion] Connection to " + host + " frozen pending approval.");
            }

            // Default
            channel = SocketChannel.open(addr);
        }

        @Override
        protected void create(boolean stream) { }

        @Override
        protected void bind(InetAddress host, int port) { }

        @Override
        protected void listen(int backlog) { }

        @Override
        protected void accept(SocketImpl s) { }

        @Override
        protected InputStream getInputStream() throws IOException {
            return channel.socket().getInputStream();
        }

        @Override
        protected OutputStream getOutputStream() throws IOException {
            return channel.socket().getOutputStream();
        }

        @Override
        protected int available() throws IOException {
            return channel.socket().getInputStream().available();
        }

        @Override
        protected void close() throws IOException {
            if (channel != null) channel.close();
        }

        @Override
        protected void sendUrgentData(int data) { }

        @Override
        public Object getOption(int optID) { return null; }

        @Override
        public void setOption(int optID, Object value) { }

        public void retry() throws IOException {
            if (lastAddr != null) {
                channel = SocketChannel.open(lastAddr);
                BastionLogger.info("[Bastion] Approved + released: " + lastAddr);
            }
        }
    }

    public static class PendingConnections {
        private static final Set<InterceptingSocketImpl> pending = new HashSet<>();

        public static void add(InterceptingSocketImpl impl) {
            pending.add(impl);
        }
        public static void remove(InterceptingSocketImpl impl) {
            pending.remove(impl);
        }

        public static void approveAll() {
            for (InterceptingSocketImpl impl : new HashSet<>(pending)) {
                try {
                    impl.retry();
                } catch (IOException ignored) { }
            }
            pending.clear();
        }

        public static void denyAll() {
            pending.clear();
        }
    }
}
