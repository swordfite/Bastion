package com.example.bastion;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.io.File;
import java.net.URL;

/**
 * ModCaller
 * Resolves the actual JAR filename FIRST (so the user knows what to delete),
 * then includes any Forge-declared metadata (modid, name, version) for context.
 */
public class ModCaller {

    /**
     * Resolve mod identity for a suspicious class.
     * Priority:
     *   1. JAR filename (actual file on disk).
     *   2. Declared Forge metadata (modid/name/version).
     *   3. Raw class name (last resort).
     */
    public static String resolveModName(String className) {
        String jarName = null;
        try {
            // === 1. ProtectionDomain → actual JAR file ===
            Class<?> cls = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            URL loc = cls.getProtectionDomain().getCodeSource().getLocation();
            if (loc != null) {
                File file = new File(loc.toURI());
                jarName = file.getName();
            }
        } catch (Exception ignored) {}

        // === 2. Forge metadata ===
        String metadata = null;
        try {
            for (ModContainer container : Loader.instance().getModList()) {
                try {
                    ClassLoader cl = container.getMod().getClass().getClassLoader();
                    if (cl != null) {
                        try {
                            cl.loadClass(className);
                            metadata = formatMod(container);
                            break;
                        } catch (ClassNotFoundException ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Exception ignored) {}

        // === Combine results ===
        if (jarName != null && metadata != null) {
            return jarName + " [declares " + metadata + "]";
        }
        if (jarName != null) return jarName;
        if (metadata != null) return metadata;
        return className;
    }

    private static String formatMod(ModContainer container) {
        StringBuilder sb = new StringBuilder();
        sb.append(container.getModId());
        if (container.getName() != null) sb.append(" (").append(container.getName()).append(")");
        if (container.getVersion() != null) sb.append(" v").append(container.getVersion());
        return sb.toString();
    }
}
