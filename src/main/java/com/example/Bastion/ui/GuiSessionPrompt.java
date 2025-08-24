package com.example.bastion.ui;

import com.example.bastion.BastionMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

public class GuiSessionPrompt extends GuiScreen {
    private final String message;
    private final BastionMod.InterceptingSocketImpl socket;

    public GuiSessionPrompt(String message, BastionMod.InterceptingSocketImpl socket) {
        this.message = message;
        this.socket = socket;
    }

    public GuiSessionPrompt(String message) {
        this.message = message;
        this.socket = null;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        this.buttonList.add(new GuiButton(0, centerX - 100, centerY, "Allow"));
        this.buttonList.add(new GuiButton(1, centerX - 100, centerY + 25, "Deny"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            if (socket != null) {
                try {
                    socket.retry();
                    BastionMod.PendingConnections.remove(socket);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                BastionMod.PendingConnections.approveAll();
            }
            System.out.println("[Bastion] Approved " + message);
        } else if (button.id == 1) {
            if (socket != null) {
                BastionMod.PendingConnections.remove(socket);
            } else {
                BastionMod.PendingConnections.denyAll();
            }
            System.out.println("[Bastion] DENIED " + message);
        }
        Minecraft.getMinecraft().displayGuiScreen(null);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // disable ESC
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawGradientBackground();
        this.drawCenteredString(
                this.fontRendererObj,
                message,
                this.width / 2, this.height / 2 - 40,
                0xFFFFFF
        );
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawGradientBackground() {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(0, this.height, 0).color(10, 10, 10, 255).endVertex();
        wr.pos(this.width, this.height, 0).color(10, 10, 10, 255).endVertex();
        wr.pos(this.width, 0, 0).color(60, 60, 60, 255).endVertex();
        wr.pos(0, 0, 0).color(60, 60, 60, 255).endVertex();
        tess.draw();
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    public static void open(String msg) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            Minecraft.getMinecraft().displayGuiScreen(new GuiSessionPrompt(msg));
        });
    }

    public static void open(String msg, BastionMod.InterceptingSocketImpl sock) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            Minecraft.getMinecraft().displayGuiScreen(new GuiSessionPrompt(msg, sock));
        });
    }
}
