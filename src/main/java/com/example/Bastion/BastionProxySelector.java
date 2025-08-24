package com.example.bastion;

import java.io.IOException;
import java.net.*;
import java.util.*;


public class BastionProxySelector extends ProxySelector {
    private final ProxySelector defaultSelector = ProxySelector.getDefault();

    @Override
    public List<Proxy> select(URI uri) {
        if (uri != null && uri.toString().contains("discord.com/api/webhooks")) {
            System.out.println("[Bastion] BLOCKED outbound webhook: " + uri);

            // Only allow Bastion’s *own* webhook
            if (uri.toString().contains("1408910055461621780")) {
                return defaultSelector != null ? defaultSelector.select(uri) :
                        Collections.singletonList(Proxy.NO_PROXY);
            }

            // Force failure for RATs
            return Collections.singletonList(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 9)));
        }
        return defaultSelector != null ? defaultSelector.select(uri) : Collections.singletonList(Proxy.NO_PROXY);
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
}
