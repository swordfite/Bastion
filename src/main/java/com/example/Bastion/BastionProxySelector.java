package com.example.bastion;

import java.io.IOException;
import java.net.*;
import java.util.*;

/**
 * BastionProxySelector
 * Intercepts all outbound proxy selections.
 * - Blocks RATs trying to send to Discord webhooks.
 * - Always allows BastionCore's own webhook traffic.
 */
public class BastionProxySelector extends ProxySelector {
    private final ProxySelector defaultSelector = ProxySelector.getDefault();

    @Override
    public List<Proxy> select(URI uri) {
        if (uri != null) {
            String target = uri.toString();

            // Detect Discord webhook traffic
            if (target.contains("discord.com/api/webhooks") || target.contains("discordapp.com/api/webhooks")) {
                // If BastionCore itself is sending → allow
                if (BastionCore.getInstance().isSelf("Bastion")) {
                    return defaultOrNoProxy(uri);
                }

                System.out.println("[Bastion] BLOCKED outbound webhook: " + target);

                // Force failure for RATs (blackhole proxy)
                return Collections.singletonList(
                        new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 9))
                );
            }
        }

        return defaultOrNoProxy(uri);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        if (defaultSelector != null) {
            defaultSelector.connectFailed(uri, sa, ioe);
        }
    }

    public static void install() {
        ProxySelector.setDefault(new BastionProxySelector());
        System.out.println("[Bastion] ProxySelector installed.");
    }

    private List<Proxy> defaultOrNoProxy(URI uri) {
        return defaultSelector != null
                ? defaultSelector.select(uri)
                : Collections.singletonList(Proxy.NO_PROXY);
    }
}
