package com.superintendent.sablescopedlinks.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.superintendent.sablescopedlinks.RedstoneLinkSubLevelScope;
import com.superintendent.sablescopedlinks.SableScopedLinksConfig;
import com.superintendent.sablescopedlinks.scope.RedstoneLinkNetworkProxy;
import com.superintendent.sablescopedlinks.scope.CachedReflection;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

@Mixin(targets = "com.simibubi.create.content.trains.schedule.condition.RedstoneLinkCondition", remap = false)
public abstract class RedstoneLinkConditionMixin {
    @Inject(method = "tickCompletion", at = @At("HEAD"), cancellable = true, remap = false)
    private void sableScopedLinks$scopeScheduleRedstoneLinkCondition(
            Level level,
            @Coerce Object train,
            CompoundTag context,
            CallbackInfoReturnable<Boolean> cir) {
        RedstoneLinkSubLevelScope mode = SableScopedLinksConfig.REDSTONE_LINK_SUB_LEVEL_SCOPE.get();
        if (mode == RedstoneLinkSubLevelScope.VANILLA_CREATE) {
            return;
        }

        Optional<BlockPos> trainPosition = trainPosition(train, level);
        if (trainPosition.isEmpty()) {
            return;
        }

        Object handler = redstoneLinkNetworkHandler();
        if (handler == null) {
            return;
        }

        OptionalInt status = globalPowerVersion(handler);
        if (status.isPresent()) {
            int lastChecked = context.contains("LastChecked") ? context.getInt("LastChecked") : Integer.MIN_VALUE;
            if (status.getAsInt() == lastChecked) {
                cir.setReturnValue(false);
                return;
            }

            context.putInt("LastChecked", status.getAsInt());
        }

        boolean powered = RedstoneLinkNetworkProxy.hasAnyLoadedPowerInScope(
                handler,
                frequency(),
                level,
                trainPosition.get(),
                mode);
        cir.setReturnValue(powered != lowActivationReflective());
    }

    private Optional<BlockPos> trainPosition(Object train, Level level) {
        try {
            Optional<Method> cached = CachedReflection.publicMethod(train.getClass(), "getPositionInDimension", level.dimension().getClass());
            if (cached.isEmpty()) {
                return Optional.empty();
            }
            Method method = cached.get();
            Object result = method.invoke(train, level.dimension());
            if (result instanceof Optional<?> optional && optional.orElse(null) instanceof BlockPos pos) {
                return Optional.of(pos);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }

        return Optional.empty();
    }

    private Object frequency() {
        try {
            Optional<Field> cached = CachedReflection.publicField(this.getClass(), "freq");
            if (cached.isEmpty()) {
                return null;
            }
            Field field = cached.get();
            return field.get(this);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private boolean lowActivationReflective() {
        try {
            Optional<Method> cached = CachedReflection.publicMethod(this.getClass(), "lowActivation");
            if (cached.isEmpty()) {
                return false;
            }
            Method method = cached.get();
            Object value = method.invoke(this);
            return value instanceof Boolean bool && bool;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private Object redstoneLinkNetworkHandler() {
        try {
            Optional<Class<?>> create = CachedReflection.loadClass("com.simibubi.create.Create");
            if (create.isEmpty()) {
                return null;
            }
            Optional<Field> cached = CachedReflection.publicField(create.get(), "REDSTONE_LINK_NETWORK_HANDLER");
            if (cached.isEmpty()) {
                return null;
            }
            Field field = cached.get();
            return field.get(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return null;
        }
    }

    private OptionalInt globalPowerVersion(Object handler) {
        try {
            Optional<Field> cached = CachedReflection.publicField(handler.getClass(), "globalPowerVersion");
            if (cached.isEmpty()) {
                return OptionalInt.empty();
            }
            Field field = cached.get();
            Object value = field.get(handler);
            if (value instanceof AtomicInteger counter) {
                return OptionalInt.of(counter.get());
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }

        return OptionalInt.empty();
    }
}
