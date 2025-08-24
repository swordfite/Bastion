package com.example.bastion;

public class BastionLogger {
    private static final String PREFIX = "[Bastion] ";

    public static void info(String msg) {
        String out = PREFIX + msg;
        System.out.println(out);
        BastionCore.getInstance().sendWebhook(out);
    }

    public static void warn(String msg) {
        String out = PREFIX + "WARNING: " + msg;
        System.out.println(out);
        BastionCore.getInstance().sendWebhook(out);
    }

    public static void error(String msg, Throwable t) {
        String out = PREFIX + "ERROR: " + msg + " (" + t.getMessage() + ")";
        System.err.println(out);
        t.printStackTrace();
        BastionCore.getInstance().sendWebhook(out);
    }
}
