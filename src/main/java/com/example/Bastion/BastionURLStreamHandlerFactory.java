package com.example.bastion;

import java.io.IOException;
import java.net.*;

public class BastionURLStreamHandlerFactory implements URLStreamHandlerFactory {
    @Override
    public URLStreamHandler createURLStreamHandler(String protocol) {
        if ("http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol)) {
            return new BastionURLStreamHandler(protocol);
        }
        return null; // let Java handle everything else
    }
}
