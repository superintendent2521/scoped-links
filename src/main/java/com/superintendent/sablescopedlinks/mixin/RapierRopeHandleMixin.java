package com.superintendent.sablescopedlinks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.superintendent.sablescopedlinks.scope.AttachedSubLevelScopeRegistry;

@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.rope.RapierRopeHandle", remap = false)
public abstract class RapierRopeHandleMixin {
    @Inject(method = "setAttachment", at = @At("RETURN"), remap = false, require = 0)
    private void sableScopedLinks$trackRopeHandleAttachment(
            @Coerce Object attachmentPoint,
            @Coerce Object location,
            @Coerce Object subLevel,
            CallbackInfo ci) {
        AttachedSubLevelScopeRegistry.recordRopeHandleAttachment(this, attachmentPoint, subLevel);
    }

    @Inject(method = "remove", at = @At("RETURN"), remap = false, require = 0)
    private void sableScopedLinks$removeRopeHandle(CallbackInfo ci) {
        AttachedSubLevelScopeRegistry.removeRopeHandle(this);
    }
}
