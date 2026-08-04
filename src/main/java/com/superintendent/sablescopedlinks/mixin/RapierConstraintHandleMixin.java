package com.superintendent.sablescopedlinks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.superintendent.sablescopedlinks.scope.AttachedSubLevelScopeRegistry;

@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.constraint.RapierConstraintHandle", remap = false)
public abstract class RapierConstraintHandleMixin {
    @Inject(method = "remove", at = @At("RETURN"), remap = false, require = 0)
    private void sableScopedLinks$removeAttachedConstraint(CallbackInfo ci) {
        AttachedSubLevelScopeRegistry.removeConstraintHandle(this);
    }
}
