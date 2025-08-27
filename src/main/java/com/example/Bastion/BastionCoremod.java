package com.example.bastion;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.apache.logging.log4j.LogManager;
import java.io.IOException;
import java.net.Socket;
import java.net.URL;
import java.util.Map;

@IFMLLoadingPlugin.MCVersion("1.8.9")
@IFMLLoadingPlugin.Name("BastionCoremod")
public class BastionCoremod implements IFMLLoadingPlugin {

    public BastionCoremod() {
        // Init BastionCore (loads remembered decisions, webhook config, etc.)
        BastionCore core = BastionCore.getInstance();
        core.sendWebhook("[Bastion] Bastion started.");

        // 1. Install URL handler factory (wraps HttpURLConnection)
        try {
            URL.setURLStreamHandlerFactory(protocol -> {
                if ("http".equals(protocol) || "https".equals(protocol)) {
                    return new BastionURLStreamHandler(protocol);
                }
                return null;
            });
            LogManager.getLogger("Bastion").info("Installed Bastion URL handler factory.");
        } catch (Error e) {
            LogManager.getLogger("Bastion").warn("URLStreamHandlerFactory already set. Bastion may not intercept URLs.");
        }

        // 2. Install SocketImplFactory (wraps raw sockets)
        try {
            Socket.setSocketImplFactory(new BastionSocketFactory());
            LogManager.getLogger("Bastion").info("Installed Bastion SocketImplFactory.");
        } catch (IOException | Error e) {
            LogManager.getLogger("Bastion").warn("SocketImplFactory already set. Bastion may not intercept sockets.");
        }

        // 3. Scan mods directory for suspicious jars
        try {
            ModScanner.scanMods();
        } catch (Throwable t) {
            LogManager.getLogger("Bastion").error("Error scanning mods", t);
        }

        // 4. Init Toast/UI manager
        try {
            LogManager.getLogger("Bastion").info("ToastManager initialized.");
        } catch (Throwable t) {
            LogManager.getLogger("Bastion").warn("Failed to init ToastManager", t);
        }
    }

    @Override
    public String[] getASMTransformerClass() { return new String[0]; }
    @Override
    public String getModContainerClass() { return null; }
    @Override
    public String getSetupClass() { return null; }
    @Override
    public void injectData(Map<String, Object> data) {}
    @Override
    public String getAccessTransformerClass() { return null; }
}
