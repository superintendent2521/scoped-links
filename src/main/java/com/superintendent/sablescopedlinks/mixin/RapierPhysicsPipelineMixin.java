package com.superintendent.sablescopedlinks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.superintendent.sablescopedlinks.scope.AttachedSubLevelScopeRegistry;

@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class RapierPhysicsPipelineMixin {
    @Inject(method = "addConstraint", at = @At("RETURN"), remap = false, require = 0)
    private void sableScopedLinks$trackAttachedSubLevels(
            @Coerce Object firstBody,
            @Coerce Object secondBody,
            @Coerce Object configuration,
            CallbackInfoReturnable<Object> cir) {
        AttachedSubLevelScopeRegistry.recordConstraint(firstBody, secondBody, configuration, cir.getReturnValue());
    }

    @Inject(method = "addRope", at = @At("RETURN"), remap = false, require = 0)
    private void sableScopedLinks$trackRopeHandle(
            @Coerce Object rope,
            CallbackInfoReturnable<Object> cir) {
        AttachedSubLevelScopeRegistry.recordRopeHandle(rope, cir.getReturnValue());
    }
}
