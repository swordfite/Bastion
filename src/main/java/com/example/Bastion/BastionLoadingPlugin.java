package com.example.bastion.coremod;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

@IFMLLoadingPlugin.Name("BastionCoreMod")
@IFMLLoadingPlugin.MCVersion("1.12.2") // adjust to your MC/Forge version
@IFMLLoadingPlugin.SortingIndex(1001)
public class BastionLoadingPlugin implements IFMLLoadingPlugin {
    @Override
    public String[] getASMTransformerClass() {
        return new String[] { "com.example.bastion.coremod.BastionTransformer" };
    }

    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public void injectData(Map<String, Object> data) {}
    @Override public String getAccessTransformerClass() { return null; }
}
