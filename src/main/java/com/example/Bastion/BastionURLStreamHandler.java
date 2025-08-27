package com.example.bastion;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/**
 * BastionURLStreamHandler
 * - Delegates to JVM’s built-in protocol handlers
 * - Wraps HttpURLConnection into BastionHttpURLConnection
 * - Supports chaining (when used with BastionURLStreamHandlerFactory)
 */
public class BastionURLStreamHandler extends URLStreamHandler {

    private final URLStreamHandler builtin;
    private final String protocol;
    private final Method openConnectionWithProxy;

    public BastionURLStreamHandler(String protocol) {
        this.protocol = protocol.toLowerCase();

        try {
            // Grab the JVM’s default handler (sun.net.www.protocol.http/https)
            String className = "sun.net.www.protocol." + this.protocol + ".Handler";
            Class<?> clazz = Class.forName(className);
            this.builtin = (URLStreamHandler) clazz.newInstance();

            // Reflection access to openConnection(URL, Proxy)
            this.openConnectionWithProxy =
                    clazz.getDeclaredMethod("openConnection", URL.class, Proxy.class);
            this.openConnectionWithProxy.setAccessible(true);

        } catch (Exception e) {
            throw new RuntimeException("Failed to init Bastion handler for protocol: " + protocol, e);
        }
    }

    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        try {
            // Use built-in handler for the real connection
            URLConnection realConn = (URLConnection) openConnectionWithProxy.invoke(builtin, url, Proxy.NO_PROXY);

            // Wrap HTTP(S) connections
            if (realConn instanceof HttpURLConnection) {
                String caller = BastionHttpURLConnection.identifyCaller();
                return new BastionHttpURLConnection((HttpURLConnection) realConn, caller);
            }

            // Non-HTTP protocols fall back untouched
            return realConn;
        } catch (Exception e) {
            throw new IOException("Bastion failed to delegate to builtin handler for " + protocol, e);
        }
    }

    @Override
    protected int getDefaultPort() {
        return "https".equals(protocol) ? 443 : 80;
    }
}
