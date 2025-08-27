package com.example.bastion;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DecisionCache {
    private static final Map<String, BastionCore.DecisionState> cache = new ConcurrentHashMap<>();

    public static BastionCore.DecisionState get(String mod, String host) {
        return cache.get(key(mod, host));
    }

    public static void put(String mod, String host, BastionCore.DecisionState state) {
        cache.put(key(mod, host), state);
    }

    private static String key(String mod, String host) {
        return mod + "|" + host.toLowerCase();
    }
}
