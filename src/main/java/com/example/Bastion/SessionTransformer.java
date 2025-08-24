package com.example.bastion;

import net.minecraft.launchwrapper.IClassTransformer;

public class SessionTransformer implements IClassTransformer {

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        // No bytecode injection performed – Bastion runs in wrapper mode
        if (name != null && name.contains("Session")) {
            System.out.println("[Bastion] Saw class load: " + name + " (no transform applied)");
        }
        return basicClass;
    }
}
