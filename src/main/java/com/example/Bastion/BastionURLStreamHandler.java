package com.example.bastion;

import java.io.IOException;
import java.net.*;

public class BastionURLStreamHandler extends URLStreamHandler {
    private final URLStreamHandler builtin;
    private final String protocol;

    public BastionURLStreamHandler(String protocol) {
        this.protocol = protocol;

        // Load the default JVM handler (sun internal class)
        try {
            String className = "sun.net.www.protocol." + protocol + ".Handler";
            Class<?> clazz = Class.forName(className);
            this.builtin = (URLStreamHandler) clazz.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load builtin handler for: " + protocol, e);
        }
    }

    @Override
    protected URLConnection openConnection(URL u) throws IOException {
        BastionLogger.info("Something happened");


        // Use the builtin handler to make the real connection
        URLConnection realConn = openWithBuiltin(u);

        // Wrap HTTP/HTTPS connections
        if (realConn instanceof HttpURLConnection) {
            return new BastionHttpURLConnection((HttpURLConnection) realConn, u);
        }

        return realConn;
    }

    private URLConnection openWithBuiltin(URL u) throws IOException {
        try {
            // Call openConnection(URL, Proxy) with NO_PROXY
            return (URLConnection) builtin.getClass()
                    .getDeclaredMethod("openConnection", URL.class, Proxy.class)
                    .invoke(builtin, u, Proxy.NO_PROXY);
        } catch (Exception e) {
            throw new IOException("Failed to delegate to builtin handler for " + protocol, e);
        }
    }
}
