package com.superintendent.sablescopedlinks;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class SableScopedLinksConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.EnumValue<RedstoneLinkSubLevelScope> REDSTONE_LINK_SUB_LEVEL_SCOPE;
    public static final ModConfigSpec.BooleanValue FAIL_OPEN_WHEN_SABLE_API_MISSING;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("redstone_links");

        REDSTONE_LINK_SUB_LEVEL_SCOPE = builder
                .comment("Controls how Create redstone links are allowed to communicate across Sable sub-level boundaries.",
                        "VANILLA_CREATE disables this addon filter.",
                        "SAME_SUBLEVEL_ONLY requires both links to be in the same Sable scope: ordinary world, or the exact same sub-level.",
                        "SUBLEVEL_AND_WORLD lets a sub-level link talk to ordinary-world links, but still isolates different sub-levels.")
                .defineEnum("redstoneLinkSubLevelScope", RedstoneLinkSubLevelScope.SAME_SUBLEVEL_ONLY);

        FAIL_OPEN_WHEN_SABLE_API_MISSING = builder
                .comment("When true, links behave like vanilla Create if this addon cannot find Sable's companion API at runtime.",
                        "When false, only ordinary-world links are allowed until the API is found.")
                .define("failOpenWhenSableApiMissing", true);

        builder.pop();

        SPEC = builder.build();
    }

    private SableScopedLinksConfig() {
    }
}
