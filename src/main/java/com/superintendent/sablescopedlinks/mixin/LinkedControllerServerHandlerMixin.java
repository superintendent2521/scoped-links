package com.superintendent.sablescopedlinks.mixin;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.superintendent.sablescopedlinks.RedstoneLinkSubLevelScope;
import com.superintendent.sablescopedlinks.SableScopedLinksConfig;
import com.superintendent.sablescopedlinks.scope.LinkScopeResolver;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;

@Mixin(targets = "com.simibubi.create.content.redstone.link.controller.LinkedControllerServerHandler", remap = false)
public abstract class LinkedControllerServerHandlerMixin {
    @Inject(method = "receivePressed", at = @At("HEAD"), remap = false)
    private static void sableScopedLinks$enterLinkedControllerScope(
            LevelAccessor world,
            BlockPos pos,
            UUID uniqueID,
            @Coerce Object frequencies,
            boolean pressed,
            CallbackInfo ci) {
        LinkScopeResolver.exitLinkedControllerScope();
        if (SableScopedLinksConfig.REDSTONE_LINK_SUB_LEVEL_SCOPE.get() != RedstoneLinkSubLevelScope.VANILLA_CREATE) {
            LinkScopeResolver.enterLinkedControllerScope(world, pos, uniqueID);
        }
    }

    @Inject(method = "receivePressed", at = @At("RETURN"), remap = false)
    private static void sableScopedLinks$exitLinkedControllerScope(
            LevelAccessor world,
            BlockPos pos,
            UUID uniqueID,
            @Coerce Object frequencies,
            boolean pressed,
            CallbackInfo ci) {
        LinkScopeResolver.exitLinkedControllerScope();
    }
}
