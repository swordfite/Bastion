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

    /**
     * Error logging with message only.
     * No stacktrace printed — for simple error lines.
     */
    public static void error(String msg) {
        String out = PREFIX + "ERROR: " + msg;
        System.err.println(out);
        BastionCore.getInstance().sendWebhook(out);
    }

    /**
     * Error logging with message + throwable.
     * Prints the stacktrace if not null.
     */
    public static void error(String msg, Throwable t) {
        String out = PREFIX + "ERROR: " + msg;
        if (t != null) {
            out += " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
            System.err.println(out);
            t.printStackTrace();
        } else {
            System.err.println(out);
        }
        BastionCore.getInstance().sendWebhook(out);
    }
}
