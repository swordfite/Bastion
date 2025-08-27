package com.example.bastion.ui;

import com.example.bastion.BastionCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.io.*;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.*;

public class GuiSessionPrompt extends GuiScreen {

    private static final File LOG_FILE = new File("config", "bastion_prompts.log");
    private static final Queue<GuiSessionPrompt> pendingQueue = new ArrayDeque<>();
    private static final Object LOCK = new Object();
    private static GuiSessionPrompt activePrompt = null;

    private static Method isRunningMethod;
    static {
        try {
            Class<?> splash = Class.forName("net.minecraftforge.fml.client.SplashProgress");
            isRunningMethod = splash.getDeclaredMethod("isRunning");
            isRunningMethod.setAccessible(true);
        } catch (Exception e) {
            isRunningMethod = null;
        }
    }

    public enum PromptType { SOCKET, HTTP }

    private final String modName;
    private final String message;
    private final PromptType type;
    private final String hostPort;
    private final String url;
    private final Runnable onApprove;
    private final Runnable onDeny;

    private boolean decisionMade = false;

    private GuiButton allowOnce, allowRemember, blockOnce, blockRemember;
    private final List<String> wrappedMessage = new ArrayList<>();
    private int scrollOffset = 0;
    private long lastBlinkTime = 0;
    private boolean blinkState = false;

    public GuiSessionPrompt(String modName, String message,
                            PromptType type, String hostPort, String url,
                            Runnable onApprove, Runnable onDeny) {
        this.modName = modName;
        this.message = message;
        this.type = type;
        this.hostPort = hostPort;
        this.url = url;
        this.onApprove = onApprove;
        this.onDeny = onDeny;
    }

    public static GuiSessionPrompt getActivePrompt() { return activePrompt; }
    public static boolean hasActivePrompt() { return activePrompt != null; }
    public boolean isDecisionMade() { return decisionMade; }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int cx = this.width / 2;
        int cy = this.height / 2;

        allowOnce     = new GuiButton(0, cx - 110, cy + 40, 220, 20, "Allow Once");
        allowRemember = new GuiButton(1, cx - 110, cy + 65, 220, 20, "Allow & Remember");
        blockOnce     = new GuiButton(2, cx - 110, cy + 95, 220, 20, "Block Once");
        blockRemember = new GuiButton(3, cx - 110, cy + 120, 220, 20, "Block & Remember");

        this.buttonList.add(allowOnce);
        this.buttonList.add(allowRemember);
        this.buttonList.add(blockOnce);
        this.buttonList.add(blockRemember);

        wrappedMessage.clear();
        String decorated = "[" + modName + "] " + message;
        wrappedMessage.addAll(this.fontRendererObj.listFormattedStringToWidth(decorated, this.width - 40));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (decisionMade) return;
        BastionCore.DecisionState state = null;
        boolean remember = false;

        switch (button.id) {
            case 0: state = BastionCore.DecisionState.APPROVED; remember = false; break;
            case 1: state = BastionCore.DecisionState.APPROVED; remember = true;  break;
            case 2: state = BastionCore.DecisionState.DENIED;   remember = false; break;
            case 3: state = BastionCore.DecisionState.DENIED;   remember = true;  break;
        }
        if (state != null) {
            handleDecision(state, remember);
            for (GuiButton b : this.buttonList) b.enabled = false;
        }
    }

    private synchronized void handleDecision(BastionCore.DecisionState state, boolean remember) {
        if (decisionMade) return;
        decisionMade = true;

        BastionCore core = BastionCore.getInstance();
        try {
            core.recordDecision(modName, hostPort, url, state, remember);
        } catch (Exception e) {
            core.recordDecision(modName, null, null, state, remember);
        }

        if (state == BastionCore.DecisionState.APPROVED && onApprove != null) onApprove.run();
        if (state == BastionCore.DecisionState.DENIED && onDeny != null) onDeny.run();

        logDecision(modName, state, message);

        synchronized (LOCK) {
            activePrompt = null;
            if (!pendingQueue.isEmpty()) {
                activePrompt = pendingQueue.poll();
                Minecraft.getMinecraft().addScheduledTask(() -> Minecraft.getMinecraft().displayGuiScreen(activePrompt));
            } else {
                Minecraft.getMinecraft().addScheduledTask(() -> {
                    if (Minecraft.getMinecraft().currentScreen == this) {
                        Minecraft.getMinecraft().displayGuiScreen(null);
                    }
                });
            }
        }
    }

    private void logDecision(String mod, BastionCore.DecisionState state, String details) {
        try {
            if (!LOG_FILE.getParentFile().exists()) LOG_FILE.getParentFile().mkdirs();
            try (BufferedWriter w = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                w.write("[" + ts + "] " + mod + " → " + state + " (" + details + ")");
                w.newLine();
            }
        } catch (IOException ignored) {}
    }

    @Override protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_DOWN && scrollOffset < wrappedMessage.size() - 5) scrollOffset++;
        if (key == Keyboard.KEY_UP && scrollOffset > 0) scrollOffset--;
    }
    @Override public void onGuiClosed() {}
    @Override public boolean doesGuiPauseGame() { return true; }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        this.drawDefaultBackground();

        long now = System.currentTimeMillis();
        if (now - lastBlinkTime > 500) {
            blinkState = !blinkState;
            lastBlinkTime = now;
        }

        int titleColor = blinkState ? 0xFF5555 : 0xFF0000;
        this.drawCenteredString(this.fontRendererObj, "⚠ SECURITY PROMPT ⚠", this.width/2, 30, titleColor);
        this.drawCenteredString(this.fontRendererObj, "Suspicious Activity Detected", this.width/2, 50, 0xFFFFFF);

        GlStateManager.pushMatrix();
        int y = 80;
        int linesShown = 0;
        for (int i = scrollOffset; i < wrappedMessage.size(); i++) {
            if (linesShown > 8) break;
            this.drawCenteredString(this.fontRendererObj, wrappedMessage.get(i), this.width/2, y, 0xDDDDDD);
            y += 12;
            linesShown++;
        }
        if (wrappedMessage.size() > 8) {
            this.drawCenteredString(this.fontRendererObj, "[↑/↓ to scroll]", this.width/2, y+5, 0xAAAAAA);
        }
        GlStateManager.popMatrix();

        super.drawScreen(mx, my, pt);
    }

    public static void open(String mod, String msg, PromptType type,
                            String hostPort, String url,
                            Runnable onApprove, Runnable onDeny) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            GuiSessionPrompt p = new GuiSessionPrompt(mod, msg, type, hostPort, url, onApprove, onDeny);
            synchronized (LOCK) {
                if (activePrompt == null && !isSplashRunning()) {
                    activePrompt = p;
                    Minecraft.getMinecraft().displayGuiScreen(p);
                } else {
                    pendingQueue.add(p);
                }
            }
        });
    }

    private static void showNextInQueue() {
        synchronized (LOCK) {
            if (!pendingQueue.isEmpty() && !isSplashRunning()) {
                activePrompt = pendingQueue.poll();
                Minecraft.getMinecraft().displayGuiScreen(activePrompt);
            }
        }
    }

    public static void tick() {
        synchronized (LOCK) {
            if (activePrompt != null && !activePrompt.isDecisionMade() && !isSplashRunning()) {
                if (Minecraft.getMinecraft().currentScreen != activePrompt) {
                    Minecraft.getMinecraft().displayGuiScreen(activePrompt);
                }
            }
        }
    }

    public static void registerRetryHandler() {
        MinecraftForge.EVENT_BUS.register(new Object() {
            @SubscribeEvent public void onClientTick(TickEvent.ClientTickEvent e) {
                if (e.phase == TickEvent.Phase.END) tick();
            }
        });
    }

    private static boolean isSplashRunning() {
        if (isRunningMethod == null) return false;
        try { return (Boolean) isRunningMethod.invoke(null); }
        catch (Exception e) { return false; }
    }

    // === Legacy overloads for compatibility ===
    public static void open(String modName, String msg) {
        open(modName, msg, PromptType.HTTP, null, null, null, null);
    }

    public static void open(String modName, String msg, Runnable onApprove, Runnable onDeny) {
        open(modName, msg, PromptType.HTTP, null, null, onApprove, onDeny);
    }

    public static void queue(String modName, String msg) {
        open(modName, msg, PromptType.HTTP, null, null, null, null);
    }

    public static void queue(String modName, String msg, Runnable onApprove, Runnable onDeny) {
        open(modName, msg, PromptType.HTTP, null, null, onApprove, onDeny);
    }
}
