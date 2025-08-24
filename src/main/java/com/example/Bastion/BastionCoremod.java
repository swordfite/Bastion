package com.example.bastion;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import java.net.URL;
import java.util.Map;

@IFMLLoadingPlugin.MCVersion("1.8.9")
@IFMLLoadingPlugin.Name("BastionCoremod")
public class BastionCoremod implements IFMLLoadingPlugin {

    private static final Logger LOGGER = LogManager.getLogger("Bastion");

    public BastionCoremod() {
        BastionLogger.info("Something happened");


        try {
            URL.setURLStreamHandlerFactory(protocol -> {
                if ("http".equals(protocol) || "https".equals(protocol)) {
                    return new BastionURLStreamHandler(protocol);
                }
                return null;
            });
            BastionLogger.info("Something happened");

        } catch (Error e) {
            // JVM only allows this once per process
            BastionLogger.info("Something happened");

        }
    }


    @Override
    public String[] getASMTransformerClass() {
        return new String[0]; // Not injecting ASM transformers
    }

    @Override
    public String getModContainerClass() {
        return null; // No separate mod container
    }

    @Override
    public String getSetupClass() {
        return null; // Not using a setup class
    }

    @Override
    public void injectData(Map<String, Object> data) {
        // Could be used to grab Minecraft directory if needed
    }

    @Override
    public String getAccessTransformerClass() {
        return null; // No access transformers used
    }
}
