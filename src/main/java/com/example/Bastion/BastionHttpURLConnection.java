package com.example.bastion;

import com.example.bastion.ui.GuiSessionPrompt;
import com.example.bastion.ui.ToastManager;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wrapper around HttpURLConnection with Bastion interception logic.
 */
public class BastionHttpURLConnection extends HttpURLConnection {
    private final HttpURLConnection delegate;

    // pending approvals map: modName -> list of blocked connections
    private static final Map<String, List<BastionHttpURLConnection>> pending = new ConcurrentHashMap<>();

    private final String modName;

    protected BastionHttpURLConnection(HttpURLConnection delegate, URL url) {
        super(url);
        this.delegate = delegate;

        // TODO: replace with real mod detection, right now just UNKNOWN
        this.modName = "UNKNOWN_MOD";
    }

    @Override
    public void connect() throws IOException {
        String target = url.toString();

        if (target.contains("discord.com/api/webhooks")) {
            BastionCore core = BastionCore.getInstance();

            // If denied → hard block
            if (core.isDenied(modName)) {
                throw new IOException("Bastion: Denied webhook request " + target);
            }

            // If not yet approved → queue + prompt
            if (!core.isApproved(modName)) {
                pending.computeIfAbsent(modName, k -> new ArrayList<>()).add(this);

                Minecraft.getMinecraft().addScheduledTask(() -> {
                    Minecraft.getMinecraft().displayGuiScreen(new GuiSessionPrompt(modName));
                    ToastManager.addToast("[Bastion] Suspended webhook request from " + modName);
                });

                // Prevent immediate network call
                throw new IOException("Bastion: Webhook request frozen pending approval");
            }
        }

        // Normal flow
        delegate.connect();
    }

    /**
     * Called from GuiSessionPrompt when user clicks Allow/Deny.
     */
    public static void resolvePending(String modName, boolean approved) {
        BastionCore core = BastionCore.getInstance();

        if (approved) {
            core.addApproved(modName);
            List<BastionHttpURLConnection> list = pending.remove(modName);
            if (list != null) {
                for (BastionHttpURLConnection conn : list) {
                    try {
                        System.out.println("[Bastion] Releasing queued webhook: " + conn.url);
                        conn.delegate.connect(); // retry underlying connection
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        } else {
            core.addBlocked(modName);
            pending.remove(modName);
        }
    }

    // Forward everything else
    @Override public void disconnect() { delegate.disconnect(); }
    @Override public boolean usingProxy() { return delegate.usingProxy(); }
}
