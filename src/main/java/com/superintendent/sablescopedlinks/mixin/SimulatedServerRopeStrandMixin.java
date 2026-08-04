package com.superintendent.sablescopedlinks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.superintendent.sablescopedlinks.scope.AttachedSubLevelScopeRegistry;

@Mixin(targets = "dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerRopeStrand", remap = false)
public abstract class SimulatedServerRopeStrandMixin {
    @Inject(method = "addAttachment", at = @At("RETURN"), remap = false, require = 0)
    private void sableScopedLinks$trackAddedAttachment(
            @Coerce Object level,
            @Coerce Object attachmentPoint,
            @Coerce Object attachment,
            CallbackInfo ci) {
        AttachedSubLevelScopeRegistry.recordSimulatedRope(this, level);
    }

    @Inject(method = "reattachConstraints", at = @At("RETURN"), remap = false, require = 0)
    private void sableScopedLinks$trackReattachedConstraints(@Coerce Object level, CallbackInfo ci) {
        AttachedSubLevelScopeRegistry.recordSimulatedRopeConstraint(this);
    }

    @Inject(method = "onAddition", at = @At("RETURN"), remap = false, require = 0)
    private void sableScopedLinks$trackLoadedRope(@Coerce Object physicsSystem, CallbackInfo ci) {
        AttachedSubLevelScopeRegistry.recordSimulatedRopeFromPhysicsSystem(this, physicsSystem);
    }

    @Inject(method = "removeConstraints", at = @At("RETURN"), remap = false, require = 0)
    private void sableScopedLinks$removeConstraints(CallbackInfo ci) {
        AttachedSubLevelScopeRegistry.clearSimulatedRopeConstraint(this);
    }

    @Inject(method = "onRemoved", at = @At("RETURN"), remap = false, require = 0)
    private void sableScopedLinks$removeRope(CallbackInfo ci) {
        AttachedSubLevelScopeRegistry.removeSimulatedRope(this);
    }

    @Inject(method = "onUnloaded", at = @At("RETURN"), remap = false, require = 0)
    private void sableScopedLinks$unloadRope(@Coerce Object holdingChunkMap, @Coerce Object chunkPos, CallbackInfo ci) {
        AttachedSubLevelScopeRegistry.removeSimulatedRope(this);
    }
}
