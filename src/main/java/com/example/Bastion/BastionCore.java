package com.example.bastion;

import com.example.bastion.ui.GuiSessionPrompt;
import com.example.bastion.ui.ToastManager;
import net.minecraft.client.Minecraft;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLContext;
import java.io.*;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.net.Proxy;


public class BastionCore {
    public String getWebhookUrl() {
        return webhookUrl;
    }

    // ---------------------------------------------------------
    // Singleton pattern
    // ---------------------------------------------------------
    private static BastionCore INSTANCE;

    public static synchronized BastionCore getInstance() {
        if (INSTANCE == null) INSTANCE = new BastionCore();
        return INSTANCE;
    }

    // ---------------------------------------------------------
    // Enums
    // ---------------------------------------------------------
    public enum DecisionState { APPROVED, DENIED, UNDECIDED }
    public enum Severity { LOW, MEDIUM, CRITICAL }

    // ---------------------------------------------------------
    // Internal state maps
    // ---------------------------------------------------------
    private final Map<String,DecisionState> modDecisions  = new ConcurrentHashMap<>();
    private final Map<String,DecisionState> hostDecisions = new ConcurrentHashMap<>();
    private final Map<String,DecisionState> urlDecisions  = new ConcurrentHashMap<>();

    // Futures for things waiting for decision
    private final Map<String,CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();

    // Captured session token (if found)
    private volatile String sessionToken = null;

    // Event listeners for external hooks
    private final List<BastionEventListener> listeners = new CopyOnWriteArrayList<>();

    // ---------------------------------------------------------
    // Files and persistence
    // ---------------------------------------------------------
    private final File logFile;
    private final PrintWriter logWriter;
    private final File stateFile;
    private final File configFile;

    // ---------------------------------------------------------
    // Config values
    // ---------------------------------------------------------
    private String webhookUrl = "";
    private int promptTimeoutSeconds = 180;
    private boolean logToFile = true;

    // ---------------------------------------------------------
    // Constructor
    // ---------------------------------------------------------
    private BastionCore() {
        File configDir = new File("config/bastion/");
        if (!configDir.exists()) configDir.mkdirs();

        this.configFile = new File(configDir, "bastion_config.json");
        this.stateFile  = new File(configDir, "bastion_state.json");
        this.logFile    = new File(configDir, "bastion_state.log");

        // Load config, init log writer
        loadConfig();

        PrintWriter writer = null;
        try {
            if (logToFile) writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)));
        } catch (IOException e) {
            System.err.println("[Bastion] Failed to open log: " + e.getMessage());
        }
        this.logWriter = writer;

        loadState();
        log("[Init] BastionCore ready.");

// 🔹 Install plain SocketImplFactory (TCP) if not already set
//        try {
//            java.lang.reflect.Field f = java.net.Socket.class.getDeclaredField("factory");
//            f.setAccessible(true);
//            Object currentFactory = f.get(null);
//
//            if (currentFactory == null) {
//               // java.net.Socket.setSocketImplFactory(new BastionSocketFactory());
//                log("[Init] BastionSocketFactory installed.");
//            }
//            // else: do nothing silently
//        } catch (Throwable t) {
//            // do nothing silently
//        }



        // 🔹 Install SSL factory later, async
        new Thread(() -> {
            try {
                Thread.sleep(2000); // wait 2s to avoid freezing init
                javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(
                        new BastionSSLSocketFactory()
                );
                log("[Init] BastionSSLSocketFactory installed (delayed).");
            } catch (Exception e) {
                log("[Error] Failed to install SSLSocketFactory: " + e.getMessage());
            }
        }, "Bastion-SSL-Init").start();
    runWebhookDiagnostics();
}

    // ---------------------------------------------------------
    // Config loading/saving
    // ---------------------------------------------------------


    private void loadConfig() {
        if (!configFile.exists()) {
            log("[Config] No config found, creating default.");
            saveConfig();
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(configFile))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String json = sb.toString().trim();
            if (!json.isEmpty()) {
                Map<String,String> map = parseJson(json);
                webhookUrl = map.getOrDefault("webhookUrl", "");
                promptTimeoutSeconds = Integer.parseInt(map.getOrDefault("promptTimeoutSeconds","180"));
                logToFile = Boolean.parseBoolean(map.getOrDefault("logToFile","true"));
                log("[Config] Loaded. webhookUrl=" + webhookUrl + ", timeout=" + promptTimeoutSeconds + ", logToFile=" + logToFile);
            }
        } catch (Exception e) {
            System.err.println("[Bastion] Failed to load config, using defaults: " + e.getMessage());
        }
    }

    /**
     * Save current config to file
     */
    private void saveConfig() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(configFile))) {
            Map<String,String> map = new LinkedHashMap<>();
            map.put("webhookUrl", webhookUrl);
            map.put("promptTimeoutSeconds", String.valueOf(promptTimeoutSeconds));
            map.put("logToFile", String.valueOf(logToFile));
            pw.write(toJson(map));
            log("[Config] Saved to " + configFile.getName());
        } catch (IOException e) {
            log("[Error] Failed to save config: " + e.getMessage());
        }
    }


    // ---------------------------------------------------------
// Enforcement entry points
// ---------------------------------------------------------
    public boolean enforceDecision(String modName, String hostKey, String fullUrl, String reason) throws IOException {
        // If the mod itself is approved, bypass everything
        if (modName != null) {
            String modKey = modName.toLowerCase(Locale.ROOT);
            if (modDecisions.getOrDefault(modKey, DecisionState.UNDECIDED) == DecisionState.APPROVED) {
                log("[Bypass] " + modName + " is globally approved — skipping Bastion enforcement.");
                return true; // let the rat’s own request execute
            }
        }

        CompletableFuture<Boolean> future = requestApproval(modName, hostKey, fullUrl, reason);
        boolean allowed = awaitApproval(modName, hostKey, fullUrl, future);
        if (!allowed) {
            String target = (fullUrl != null ? fullUrl : hostKey);
            throw new IOException("[Bastion] Blocked → " + modName + " → " + target + " (" + reason + ")");
        }
        return true;
    }

    /**
     * Overload without fullUrl
     */
    public boolean enforceDecision(String modName, String hostKey, String reason) throws IOException {
        return enforceDecision(modName, hostKey, null, reason);
    }

    /**
     * Overload with just mod + reason
     */
    public boolean enforceDecision(String modName, String reason) throws IOException {
        return enforceDecision(modName, null, null, reason);
    }

// ---------------------------------------------------------
// Approval flow
// ---------------------------------------------------------

    /**
     * Request approval – main entry
     */
    public CompletableFuture<Boolean> requestApproval(String mod, String host, String url, String reason) {
        DecisionState state = queryDecision(mod, host, url);
        if (state == DecisionState.APPROVED) return CompletableFuture.completedFuture(true);
        if (state == DecisionState.DENIED)   return CompletableFuture.completedFuture(false);

        String key = makeKey(mod, host, url);

        return pending.computeIfAbsent(key, k -> {
            log("[Approval] Request queued → " + key + " (" + reason + ")");
            CompletableFuture<Boolean> future = new CompletableFuture<>();

            Severity sev = classifyFromReason(reason, host, url);
            int color = toastColor(sev);

            // === LOW severity ===
            if (sev == Severity.LOW) {
                ToastManager.addToast("[Bastion] Notice: " + mod + " → " + reason, color);
                sendWebhook("[Bastion] Notice: " + mod + " → " + reason);
                recordDecision(mod, host, url, DecisionState.APPROVED, false);
                future.complete(true);
                return future;
            }

            // === MEDIUM/CRITICAL severity ===
            GuiSessionPrompt.open(
                    mod,
                    reason,
                    GuiSessionPrompt.PromptType.SOCKET,
                    host,
                    url,
                    () -> {
                        recordDecision(mod, host, url, DecisionState.APPROVED, true);
                        future.complete(true);
                    },
                    () -> {
                        recordDecision(mod, host, url, DecisionState.DENIED, true);
                        future.complete(false);
                    }
            );

            ToastManager.addToast("[Bastion] Suspicious: " + mod + " → " + reason, color);
            sendWebhook("[Bastion] Suspicious: " + mod + " → " + reason);

            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                if (!future.isDone()) {
                    log("[Decision] Timeout → auto-deny " + key);
                    recordDecision(mod, host, url, DecisionState.DENIED, true);
                    future.complete(false);
                }
            }, promptTimeoutSeconds, TimeUnit.SECONDS);

            return future;
        });
    }

    public boolean awaitApproval(String mod, String host, String url, CompletableFuture<Boolean> future) {
        try {
            return future.get(promptTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log("[Decision] Exception/timeout → auto-deny " + makeKey(mod, host, url));
            recordDecision(mod, host, url, DecisionState.DENIED, true);
            return false;
        }
    }

// end of part 1

    // ---------------------------------------------------------
// Static scan approval
// ---------------------------------------------------------
public CompletableFuture<Boolean> requestStaticScanApproval(String mod, File jarFile, List<String> indicators) {
    String reason = "[StaticScan] " + indicators.toString();
    String hostPort = jarFile.getName();

    DecisionState state = queryDecision(mod, hostPort, null);
    if (state == DecisionState.APPROVED) return CompletableFuture.completedFuture(true);
    if (state == DecisionState.DENIED)   return CompletableFuture.completedFuture(false);

    String key = makeKey(mod, null, null);


    return pending.computeIfAbsent(key, k -> {
        log("[StaticScan] Request queued → " + key + " (" + reason + ")");
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Severity sev = classifyIndicators(indicators);
        int color = toastColor(sev);

// LOW severity = auto-approve, no GUI
        if (sev == Severity.LOW) {
            sendWebhook("[Bastion] StaticScan notice: " + mod + " flagged " + indicators);
            recordDecision(mod, null, null, DecisionState.APPROVED, false);
            future.complete(true);
            return future;
        }

// === MEDIUM/CRITICAL severity ===
        GuiSessionPrompt.open(
                mod,
                reason,
                GuiSessionPrompt.PromptType.HTTP,
                null,
                null,
                () -> {
                    recordDecision(mod, hostPort, null, DecisionState.APPROVED, true);
                    future.complete(true);
                },
                () -> {
                    recordDecision(mod, null, null, DecisionState.DENIED, true);
                    future.complete(false);
                }
        );



        ToastManager.addToast("[Bastion] StaticScan: " + mod + " flagged " + indicators, color);
        sendWebhook("[Bastion] StaticScan: " + mod + " flagged " + indicators);

        Executors.newSingleThreadScheduledExecutor().schedule(() -> {
            if (!future.isDone()) {
                log("[StaticScan] Timeout → auto-deny " + key);
                recordDecision(mod, null, null, DecisionState.DENIED, true);
                future.complete(false);
            }
        }, promptTimeoutSeconds, TimeUnit.SECONDS);

        return future;
    });
}

// ---------------------------------------------------------
// Decision querying
// ---------------------------------------------------------

public synchronized void cancelPending(String mod, String host, String url) {
    String key = makeKey(mod, host, url);
    CompletableFuture<Boolean> f = pending.remove(key);
    if (f != null && !f.isDone()) {
        f.complete(false);
        log("[Cancel] Pending decision removed → " + key);
    }
}
public synchronized void cancelPending(String mod, String host) { cancelPending(mod, host, null); }
public synchronized void cancelPending(String mod) { cancelPending(mod, null, null); }

    // ---------------------------------------------------------
// Record decision
// ---------------------------------------------------------
    public synchronized void recordDecision(String mod, String host, String fullUrl,
                                            DecisionState state, boolean remember) {
        if (mod == null || isSelf(mod)) return;

        String modKey = mod.toLowerCase(Locale.ROOT);
        String hash = "nohash";
        try {
            File modsDir = new File("mods");
            if (modsDir.exists() && modsDir.isDirectory()) {
                final String searchKey = modKey; // make it effectively final for lambda
                File[] jars = modsDir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).contains(searchKey));

                if (jars != null && jars.length > 0) {
                    hash = sha256(jars[0]);
                }
            }
        } catch (Exception ignored) {}
        modKey = modKey + "#" + hash;

        String hostKey = (host != null) ? modKey + "@" + host.toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "") : null;
        String urlKey  = null;

        if (fullUrl != null) {
            try {
                URL u = new URL(fullUrl);
                urlKey = modKey + "@" + u.getHost().toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "");
            } catch (Exception ignored) {}
        }

        // --- Store at the most specific level ---
        if (urlKey != null) {
            urlDecisions.put(urlKey, state);
        } else if (hostKey != null) {
            hostDecisions.put(hostKey, state);
        } else {
            modDecisions.put(modKey, state);
        }

        // --- Cascade approval (ensures bypass works everywhere) ---
        // --- Cascade approval (ensures bypass works everywhere) ---
        if (state == DecisionState.APPROVED) {
            modDecisions.put(modKey, state);

            if (hostKey != null) hostDecisions.put(hostKey, state);
            if (urlKey != null)  urlDecisions.put(urlKey, state);

            // Always seed Discord hosts for this mod
            hostDecisions.put(modKey + "@discord.com", state);
            hostDecisions.put(modKey + "@discordapp.com", state);
            hostDecisions.put(modKey + "@discordapp.net", state);
            hostDecisions.put(modKey + "@discord.gg", state);

            // If mod is unknown, cascade approval globally for session
            if (mod.startsWith("unknown-mod")) {
                for (String h : new String[]{
                        "discord.com","discordapp.com","discordapp.net","discord.gg"
                }) {
                    hostDecisions.put("unknown-mod#" + hash + "@" + h, state);
                }
            }
        }


        // --- Log + notify ---
        String effectiveKey = (urlKey != null ? urlKey : hostKey != null ? hostKey : modKey);
        log("[Decision] " + effectiveKey + " -> " + state + (remember ? " [remember]" : " [session]"));
        fireDecision(mod, host, fullUrl, state);

        if (remember) saveState();

        // --- Resolve any pending futures ---
        CompletableFuture<Boolean> future = pending.remove(effectiveKey);
        if (future != null && !future.isDone()) {
            future.complete(state == DecisionState.APPROVED);
        }
    }

    // ---------------------------------------------------------
// Normalized key handling for consistent decisions
// ---------------------------------------------------------
    // ---------------------------------------------------------
// Normalized key handling for consistent decisions
// ---------------------------------------------------------
    private String makeKey(String mod, String host, String url) {
        String modKey = (mod != null) ? mod.toLowerCase(Locale.ROOT) : "unknownmod";

        // Attempt to locate the mod jar and fingerprint it
        String hash = "nohash";
        try {
            File modsDir = new File("mods");
            if (modsDir.exists() && modsDir.isDirectory()) {
                final String searchKey = modKey; // make it effectively final for lambda
                File[] jars = modsDir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).contains(searchKey));;
                if (jars != null && jars.length > 0) {
                    hash = sha256(jars[0]); // fingerprint the first matching jar
                }
            }
        } catch (Exception ignored) {}

        String baseKey = modKey + "#" + hash;

        try {
            if (url != null) {
                URL u = new URL(url);
                return baseKey + "@" + u.getHost().toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "");
            }
        } catch (Exception ignored) {}
        if (host != null) {
            return baseKey + "@" + host.toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "");
        }
        return baseKey;
    }


    public synchronized DecisionState queryDecision(String mod, String host, String fullUrl) {
        if (mod == null) return DecisionState.UNDECIDED;
        if (isSelf(mod)) return DecisionState.APPROVED;

        String modKey = mod.toLowerCase(Locale.ROOT);
        String hash = "nohash";
        try {
            File modsDir = new File("mods");
            if (modsDir.exists() && modsDir.isDirectory()) {
                final String searchKey = modKey;
                File[] jars = modsDir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).contains(searchKey));
                if (jars != null && jars.length > 0) {
                    hash = sha256(jars[0]);
                }
            }
        } catch (Exception ignored) {}
        modKey = modKey + "#" + hash;

        String hostKey = (host != null) ? modKey + "@" + host.toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "") : null;
        String urlKey  = null;

        if (fullUrl != null) {
            try {
                URL u = new URL(fullUrl);
                urlKey = modKey + "@" + u.getHost().toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "");
            } catch (Exception ignored) {
                urlKey = modKey + "@" + fullUrl.toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "");
            }
        }

        // --- Global mod decision overrides everything ---
        if (modDecisions.containsKey(modKey)) {
            return modDecisions.get(modKey);
        }

        // --- Otherwise, host-level decision ---
        if (hostKey != null && hostDecisions.containsKey(hostKey)) {
            return hostDecisions.get(hostKey);
        }

        // --- Finally, URL-level decision ---
        if (urlKey != null && urlDecisions.containsKey(urlKey)) {
            return urlDecisions.get(urlKey);
        }

        return DecisionState.UNDECIDED;
    }


    // ---------------------------------------------------------
// State persistence
// ---------------------------------------------------------
private synchronized void saveState() {
    try (PrintWriter pw = new PrintWriter(new FileWriter(stateFile))) {
        Map<String,String> all = new LinkedHashMap<>();

        for (Map.Entry<String,DecisionState> e : modDecisions.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "");
            all.put(k, e.getValue().name());
        }
        for (Map.Entry<String,DecisionState> e : hostDecisions.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "");
            all.put(k, e.getValue().name());
        }
        for (Map.Entry<String,DecisionState> e : urlDecisions.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "");
            all.put(k, e.getValue().name());
        }

        pw.write(toJson(all));
        log("[State] Saved " + all.size() + " remembered decisions.");
    } catch (IOException e) {
        log("[State] Failed to save decisions: " + e.getMessage());
    }
}


    private void loadState() {
        if (!stateFile.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(stateFile))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String json = sb.toString().trim();
            if (!json.isEmpty()) {
                Map<String,String> map = parseJson(json);
                for (Map.Entry<String,String> e : map.entrySet()) {
                    DecisionState st = DecisionState.valueOf(e.getValue());
                    String k = e.getKey();

                    // normalize the key the same way makeKey() does
                    String normalized = k.toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "");

                    if (normalized.contains("@")) {
                        if (normalized.contains("http") || normalized.contains("https")) {
                            urlDecisions.put(normalized, st);
                        } else {
                            hostDecisions.put(normalized, st);
                        }
                    } else {
                        modDecisions.put(normalized, st);
                    }
                }
                log("[State] Loaded " + map.size() + " remembered decisions.");
            }
        } catch (Exception e) {
            log("[State] Failed to load decisions: " + e.getMessage());
        }
    }


// ---------------------------------------------------------
// JSON helpers
// ---------------------------------------------------------
private String toJson(Map<String,String> map) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String,String> e : map.entrySet()) {
        if (!first) sb.append(",");
        sb.append("\"").append(e.getKey().replace("\"","\\\"")).append("\":\"")
                .append(e.getValue()).append("\"");
        first = false;
    }
    sb.append("}");
    return sb.toString();
}
    private Map<String,String> parseJson(String json) {
        Map<String,String> map = new LinkedHashMap<>();
        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length()-1);
        }
        for (String part : json.split(",")) {
            String[] kv = part.split(":", 2); // split only on first colon
            if (kv.length == 2) {
                String key = kv[0].trim().replace("\"", "");
                String value = kv[1].trim().replace("\"", "");
                map.put(key, value);
            }
        }
        return map;
    }


// ---------------------------------------------------------
// Severity classification
// ---------------------------------------------------------
private int toastColor(Severity sev) {
    switch (sev) {
        case CRITICAL: return 0xFF0000; // red
        case MEDIUM:   return 0xFFAA00; // orange
        case LOW:      return 0x55FF55; // green
        default:       return 0xFFFFFF;
    }
}


    private Severity classifyIndicators(List<String> indicators) {
        String joined = String.join(" ", indicators).toLowerCase();

        if (joined.contains("discord")) return Severity.CRITICAL;
        if (joined.contains("token") || joined.contains("session")) return Severity.CRITICAL;
        if (joined.contains("password")) return Severity.MEDIUM;
        if (joined.contains("uuid") || joined.contains("userid")) return Severity.LOW;
        if (joined.contains("optifine")) return Severity.LOW;
        return Severity.MEDIUM; // default catch-all
    }


private Severity classifyFromReason(String reason, String host, String url) {
    if (reason == null) return Severity.LOW;
    String lowReason = reason.toLowerCase(Locale.ROOT);

    if (lowReason.contains("optifine")) return Severity.LOW;
    if (isDiscordHost(host) || lowReason.contains("discord")) return Severity.MEDIUM;
    if (containsTokenData(reason) || looksLikeToken(url)) return Severity.CRITICAL;
    if (containsUuidOnly(reason)) return Severity.LOW;
    if (isSuspiciousHost(host)) return Severity.LOW;
    return Severity.LOW;
}

// ---------------------------------------------------------
// Suspicion helpers
// ---------------------------------------------------------
private static final Set<String> DISCORD_HOSTS = new HashSet<>(Arrays.asList(
        "discord.com","discordapp.com","discordapp.net","discord.gg"
));
private static final Pattern TOKEN_PATTERN =
        Pattern.compile("(?i)\\b(session|token|sid)[=:]([a-f0-9\\-]{16,})");
private static final Pattern UUID_PATTERN =
        Pattern.compile("\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b",
                Pattern.CASE_INSENSITIVE);

private boolean isDiscordHost(String host) { return host != null && DISCORD_HOSTS.contains(host.toLowerCase(Locale.ROOT)); }
private boolean looksLikeToken(String text) { return text != null && TOKEN_PATTERN.matcher(text).find(); }
public static boolean containsTokenData(String payload) { return payload != null && TOKEN_PATTERN.matcher(payload).find(); }
public static boolean containsUuidOnly(String payload) { return payload != null && UUID_PATTERN.matcher(payload).find() && !containsTokenData(payload); }

public static boolean isSuspiciousHost(String host) {
    if (host == null) return false;
    host = host.toLowerCase(Locale.ROOT);
    if (HARD_WHITELIST.contains(host)) return false;
    for (String suffix : HARD_SUFFIXES) {
        if (host.endsWith(suffix) && host.lastIndexOf(suffix)>0 && host.charAt(host.lastIndexOf(suffix)-1)=='.')
            return false;
    }
    return true;
}
private static final Set<String> HARD_WHITELIST = new HashSet<>(Arrays.asList(
        "api.mojang.com","authserver.mojang.com","sessionserver.mojang.com","textures.minecraft.net",
        "minecraft.net","login.live.com","xsts.auth.xboxlive.com","user.auth.xboxlive.com","api.minecraftservices.com",
        "snoop.minecraft.net"
));
private static final String[] HARD_SUFFIXES = { ".mojang.com",".minecraft.net",".microsoft.com" };

    // ---------------------------------------------------------
// Webhook sender (RAW, bypasses Bastion enforcement)
// ---------------------------------------------------------
    public synchronized void sendWebhook(String msg) {
        String finalMsg = "[Bastion RAW] " + msg; // 🔹 always tag message
        System.out.println("[Bastion][Webhook][RAW] Attempting to send: " + finalMsg);

        if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
            System.out.println("[Bastion][Webhook][RAW] No webhookUrl configured.");
            return;
        }

        try {
            URL url = new URL(webhookUrl);

            // 🔹 Use stock HTTPS connection, no Bastion socket factories
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Bastion-RawWebhook");

            // 🔹 JSON payload
            String payload = "{\"content\":\"" + finalMsg.replace("\"", "\\\"") + "\"}";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes("UTF-8"));
            }

            int responseCode = conn.getResponseCode();
            System.out.println("[Bastion][Webhook][RAW] Discord responded: " + responseCode);

            // Always drain/close input stream to free the connection
            try (InputStream in = conn.getInputStream()) {
                while (in.read() != -1) { /* drain */ }
            }
            conn.disconnect();

        } catch (Exception e) {
            System.out.println("[Bastion][Webhook][RAW][Error] " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }


    public synchronized void log(String msg) {
    String line = "[Bastion] " + msg;
    System.out.println(line);
    if (logToFile && logWriter != null) {
        logWriter.println(line);
        logWriter.flush();
    }
}

// ---------------------------------------------------------
// Utils
// ---------------------------------------------------------
public boolean isSelf(String modName) { return modName != null && modName.toLowerCase().contains("bastion"); }
public static String normalizeHostPort(URL url) { return url.getHost() + ":" + url.getPort(); }
public static String normalizeHostPort(String host, int port) { return host + ":" + port; }

public void setSessionToken(String token) {
    if (token == null || token.trim().isEmpty()) return;
    this.sessionToken = token;
    log("[Token] Session token captured.");
    fireSession("bastion", token);
}
public String getSessionToken() { return sessionToken; }

public void setDecision(String mod, DecisionState state, boolean remember) {
    recordDecision(mod, null, null, state, remember);
}

// ---------------------------------------------------------
// Event firing
// ---------------------------------------------------------
public void fireSuspicious(String mod, String host, String url, String reason) {
    Severity sev = classifyFromReason(reason, host, url);
    for (BastionEventListener l : listeners) {
        l.onSuspiciousRequest(mod, host, url, reason, sev);
    }
}
public void fireDecision(String mod, String host, String url, DecisionState state) {
    for (BastionEventListener l : listeners) {
        l.onDecision(mod, host, url, state);
    }
}
public void fireSession(String mod, String payload) {
    Severity sev;
    if (containsTokenData(payload)) sev = Severity.CRITICAL;
    else if (containsUuidOnly(payload)) sev = Severity.LOW;
    else sev = Severity.MEDIUM;
    for (BastionEventListener l : listeners) {
        l.onSessionDetected(mod, payload, sev);
    }
}

// ---------------------------------------------------------
// Legacy overloads for backward compat
// ---------------------------------------------------------
public CompletableFuture<Boolean> requestApproval(String mod, String reason) {
    return requestApproval(mod, null, null, reason);
}
public CompletableFuture<Boolean> requestApproval(String mod, String host, String reason) {
    return requestApproval(mod, host, null, reason);
}
public void approve(String mod) { recordDecision(mod, null, null, DecisionState.APPROVED, true); }
public void deny(String mod) { recordDecision(mod, null, null, DecisionState.DENIED, true); }
public void approveOnce(String mod) { recordDecision(mod, null, null, DecisionState.APPROVED, false); }
public void denyOnce(String mod) { recordDecision(mod, null, null, DecisionState.DENIED, false); }
public void clearDecisions() {
    modDecisions.clear();
    hostDecisions.clear();
    urlDecisions.clear();
    pending.clear();
    log("[Clear] All decisions cleared.");
    }
    // ---------------------------------------------------------
// SHA256 Utility
// ---------------------------------------------------------
    public static String sha256(File file) {
        try (InputStream fis = new FileInputStream(file)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "nohash";
        }
    }
    // ---------------------------------------------------------
    // Webhook Debug Tester (runs multiple methods)
    // ---------------------------------------------------------
    public void runWebhookDiagnostics() {
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
            log("[Webhook][TEST] No webhookUrl configured.");
            return;
        }

        log("========== Bastion Webhook Diagnostics ==========");

        // --- Method 1: Raw HttpsURLConnection with clean SSLContext ---
        try {
            log("[Webhook][TEST1] Raw HttpsURLConnection + clean SSLContext");
            URL url = new URL(webhookUrl);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, null, null);

            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection(Proxy.NO_PROXY);
            conn.setSSLSocketFactory(ctx.getSocketFactory());
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Bastion-TestWebhook-1");

            String payload = "{\"content\":\"[TEST1] If you see this, Method1 worked.\"}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes("UTF-8"));
            }
            int response = conn.getResponseCode();
            log("[Webhook][TEST1] Response code: " + response);
            conn.getInputStream().close();
        } catch (Exception e) {
            log("[Webhook][TEST1][Error] " + e.getMessage());
        }

        // --- Method 2: HttpsURLConnection with JVM default SSL (may be intercepted) ---
        try {
            log("[Webhook][TEST2] HttpsURLConnection + default SSLSocketFactory");
            URL url = new URL(webhookUrl);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection(Proxy.NO_PROXY);
            conn.setSSLSocketFactory((SSLSocketFactory) SSLSocketFactory.getDefault());
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Bastion-TestWebhook-2");

            String payload = "{\"content\":\"[TEST2] If you see this, Method2 worked.\"}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes("UTF-8"));
            }
            int response = conn.getResponseCode();
            log("[Webhook][TEST2] Response code: " + response);
            conn.getInputStream().close();
        } catch (Exception e) {
            log("[Webhook][TEST2][Error] " + e.getMessage());
        }

        // --- Method 3: Plain URLConnection (trust JVM defaults blindly) ---
        try {
            log("[Webhook][TEST3] Plain URLConnection (no SSL override)");
            URL url = new URL(webhookUrl);
            java.net.URLConnection conn = url.openConnection(Proxy.NO_PROXY);
            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setRequestMethod("POST");
                https.setDoOutput(true);
                https.setRequestProperty("Content-Type", "application/json");
                https.setRequestProperty("User-Agent", "Bastion-TestWebhook-3");

                String payload = "{\"content\":\"[TEST3] If you see this, Method3 worked.\"}";
                try (OutputStream os = https.getOutputStream()) {
                    os.write(payload.getBytes("UTF-8"));
                }
                int response = https.getResponseCode();
                log("[Webhook][TEST3] Response code: " + response);
                https.getInputStream().close();
            }
        } catch (Exception e) {
            log("[Webhook][TEST3][Error] " + e.getMessage());
        }

        log("========== End of Webhook Diagnostics ==========");
    }

} // end of part 2


