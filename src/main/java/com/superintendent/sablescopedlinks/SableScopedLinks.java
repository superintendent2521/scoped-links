package com.superintendent.sablescopedlinks;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(SableScopedLinks.MOD_ID)
public final class SableScopedLinks {
    public static final String MOD_ID = "sable_scoped_links";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SableScopedLinks(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SableScopedLinksConfig.SPEC);
    }
}
