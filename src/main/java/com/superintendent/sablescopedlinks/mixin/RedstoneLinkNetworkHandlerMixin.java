package com.superintendent.sablescopedlinks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.superintendent.sablescopedlinks.RedstoneLinkSubLevelScope;
import com.superintendent.sablescopedlinks.SableScopedLinksConfig;
import com.superintendent.sablescopedlinks.scope.RedstoneLinkNetworkProxy;
import com.superintendent.sablescopedlinks.scope.LinkScopeResolver;

import net.minecraft.world.level.LevelAccessor;

@Mixin(targets = "com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler", priority = 900, remap = false)
public abstract class RedstoneLinkNetworkHandlerMixin {
    private static final ThreadLocal<Object> sableScopedLinks$currentWorld = new ThreadLocal<>();

    @Inject(method = "updateNetworkOf", at = @At("HEAD"), cancellable = true, remap = false)
    private void sableScopedLinks$captureWorld(LevelAccessor world, @Coerce Object actor, CallbackInfo ci) {
        RedstoneLinkSubLevelScope mode = SableScopedLinksConfig.REDSTONE_LINK_SUB_LEVEL_SCOPE.get();
        if (mode != RedstoneLinkSubLevelScope.VANILLA_CREATE) {
            sableScopedLinks$currentWorld.set(world);
            try {
                RedstoneLinkNetworkProxy.updateNetwork(this, world, actor, mode);
            } finally {
                sableScopedLinks$currentWorld.remove();
                if (LinkScopeResolver.isLinkedControllerManualFrequencyEntry(actor)) {
                    LinkScopeResolver.exitLinkedControllerScope();
                }
            }
            ci.cancel();
            return;
        }

        sableScopedLinks$currentWorld.set(world);
    }

    @Inject(method = "updateNetworkOf", at = @At("RETURN"), remap = false)
    private void sableScopedLinks$clearWorld(LevelAccessor world, @Coerce Object actor, CallbackInfo ci) {
        sableScopedLinks$currentWorld.remove();
    }

    @Inject(method = "withinRange", at = @At("HEAD"), cancellable = true, remap = false)
    private static void sableScopedLinks$filterDifferentSableScopes(@Coerce Object from, @Coerce Object to, CallbackInfoReturnable<Boolean> cir) {
        RedstoneLinkSubLevelScope mode = SableScopedLinksConfig.REDSTONE_LINK_SUB_LEVEL_SCOPE.get();
        if (mode == RedstoneLinkSubLevelScope.VANILLA_CREATE) {
            return;
        }

        if (LinkScopeResolver.isLinkedControllerProjectedRangeCheck()
                && (LinkScopeResolver.isLinkedControllerManualFrequencyEntry(from)
                        || LinkScopeResolver.isLinkedControllerManualFrequencyEntry(to))) {
            return;
        }

        if (!LinkScopeResolver.mayCommunicate(mode, sableScopedLinks$currentWorld.get(), from, to)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "sable$projectComparisons", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void sableScopedLinks$filterSableProjectedComparisons(
            @Coerce Object from,
            @Coerce Object to,
            LevelAccessor levelAccessor,
            CallbackInfoReturnable<Boolean> cir) {
        RedstoneLinkSubLevelScope mode = SableScopedLinksConfig.REDSTONE_LINK_SUB_LEVEL_SCOPE.get();
        if (mode == RedstoneLinkSubLevelScope.VANILLA_CREATE) {
            return;
        }

        if (LinkScopeResolver.isLinkedControllerManualFrequencyEntry(from)
                || LinkScopeResolver.isLinkedControllerManualFrequencyEntry(to)) {
            return;
        }

        if (!LinkScopeResolver.mayCommunicate(mode, levelAccessor, from, to)) {
            cir.setReturnValue(false);
        }
    }
}
