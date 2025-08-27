package com.example.bastion;

import java.io.IOException;
import java.net.SocketAddress;

public class BastionHooks {

    public static void checkSocketConnect(SocketAddress addr, int timeout) throws IOException {
        String host = addr.toString();
        BastionCore.getInstance().enforceDecision("UnknownMod", host, null, "Socket.connect");
    }

    public static void checkHttpConnect() throws IOException {
        BastionCore.getInstance().enforceDecision("UnknownMod", null, null, "HttpURLConnection.connect");
    }

    public static void checkChannelConnect(SocketAddress addr) throws IOException {
        String host = addr.toString();
        BastionCore.getInstance().enforceDecision("UnknownMod", host, null, "SocketChannel.connect");
    }
}
