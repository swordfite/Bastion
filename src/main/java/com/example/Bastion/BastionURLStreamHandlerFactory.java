package com.example.bastion;

import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;

/**
 * BastionURLStreamHandlerFactory
 * - Multi-use: wraps existing factory instead of failing if already set
 * - Ensures all http/https traffic passes through BastionURLStreamHandler
 */
public class BastionURLStreamHandlerFactory implements URLStreamHandlerFactory {

    private final URLStreamHandlerFactory previous;

    public BastionURLStreamHandlerFactory(URLStreamHandlerFactory previous) {
        this.previous = previous;
    }

    @Override
    public URLStreamHandler createURLStreamHandler(String protocol) {
        // Bastion intercepts HTTP and HTTPS
        if ("http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol)) {
            return new BastionURLStreamHandler(protocol);
        }

        // Fallback to any previously installed factory
        if (previous != null) {
            return previous.createURLStreamHandler(protocol);
        }

        return null; // let JVM handle everything else
    }

    // === Installer ===
    public static void install() {
        try {
            // Reflection hack: grab existing factory if present
            java.lang.reflect.Field f = java.net.URL.class.getDeclaredField("factory");
            f.setAccessible(true);
            URLStreamHandlerFactory current = (URLStreamHandlerFactory) f.get(null);

            // Install Bastion, wrapping previous if necessary
            BastionURLStreamHandlerFactory bastion = new BastionURLStreamHandlerFactory(current);

            if (current == null) {
                // No factory yet → we can register normally
                java.net.URL.setURLStreamHandlerFactory(bastion);
                BastionCore.getInstance().log("[Init] Installed BastionURLStreamHandlerFactory as primary.");
            } else if (!(current instanceof BastionURLStreamHandlerFactory)) {
                // Already a factory, but not ours → wrap it
                f.set(null, bastion);
                BastionCore.getInstance().log("[Init] Wrapped existing URLStreamHandlerFactory with Bastion.");
            } else {
                BastionCore.getInstance().log("[Init] BastionURLStreamHandlerFactory already active.");
            }
        } catch (Throwable t) {
            BastionCore.getInstance().log("[Error] Could not install BastionURLStreamHandlerFactory: " + t);
        }
    }
}
