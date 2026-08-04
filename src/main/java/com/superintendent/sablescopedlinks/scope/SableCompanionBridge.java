package com.superintendent.sablescopedlinks.scope;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.superintendent.sablescopedlinks.SableScopedLinks;
import net.minecraft.core.BlockPos;

final class SableCompanionBridge {
    private static final String SABLE_COMPANION_CLASS = "dev.ryanhcode.sable.companion.SableCompanion";

    private final AtomicBoolean initialized = new AtomicBoolean();
    private Object companion;
    private Method getContaining;
    private Method getAllIntersecting;
    private Method projectOutOfSubLevel;
    private Constructor<?> boundingBox3dConstructor;
    private Constructor<?> vector3dConstructor;
    private boolean getContainingAcceptsBlockPos;
    private volatile boolean available;

    boolean isAvailable() {
        init();
        return available;
    }

    Optional<Object> getContaining(Object level, BlockPos pos) {
        init();
        if (!available) {
            return Optional.empty();
        }

        try {
            Object queryPosition = getContainingAcceptsBlockPos
                    ? pos
                    : vectorAtCenter(pos);
            Object result = getContaining.invoke(companion, level, queryPosition);
            if (result instanceof Optional<?> optional) {
                return optional.map(Object.class::cast);
            }
            return Optional.ofNullable(result);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            invalidate("Sable getContaining(level, pos) failed; treating the companion API as unresolved", exception);
            return Optional.empty();
        }
    }

    Optional<Object> getIntersectingPhysical(Object level, BlockPos pos) {
        init();
        if (!available || getAllIntersecting == null || boundingBox3dConstructor == null) {
            return Optional.empty();
        }

        try {
            Object bounds = boundingBox3dConstructor.newInstance(
                    pos.getX() - 0.25, pos.getY() - 2.0, pos.getZ() - 0.25,
                    pos.getX() + 1.25, pos.getY() + 1.25, pos.getZ() + 1.25);
            Object result = getAllIntersecting.invoke(companion, level, bounds);
            if (result instanceof Iterable<?> iterable) {
                for (Object subLevel : iterable) {
                    if (subLevel != null && !isRemoved(subLevel)) {
                        return Optional.of(subLevel);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            invalidate("Sable physical sub-level lookup failed; treating the companion API as unresolved", exception);
        }

        return Optional.empty();
    }

    boolean subLevelContainsPhysical(Object subLevel, BlockPos pos) {
        init();
        if (!available || vector3dConstructor == null) {
            return false;
        }

        try {
            Object bounds = reflectNoArg(subLevel, "boundingBox").orElse(null);
            if (bounds == null) {
                return false;
            }

            double x = pos.getX() + 0.5;
            double y = pos.getY() + 0.5;
            double z = pos.getZ() + 0.5;
            double expansion = 2.0;
            return x >= doubleNoArg(bounds, "minX") - expansion
                    && x <= doubleNoArg(bounds, "maxX") + expansion
                    && y >= doubleNoArg(bounds, "minY") - expansion
                    && y <= doubleNoArg(bounds, "maxY") + expansion
                    && z >= doubleNoArg(bounds, "minZ") - expansion
                    && z <= doubleNoArg(bounds, "maxZ") + expansion;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            SableScopedLinks.LOGGER.warn("Sable sub-level physical containment check failed; denying linked controller bridge", exception);
            return false;
        }
    }


    Optional<Boolean> projectedWithinRange(Object level, BlockPos from, BlockPos to, int range) {
        init();
        if (!available || projectOutOfSubLevel == null || vector3dConstructor == null) {
            return Optional.empty();
        }

        try {
            Object fromProjected = projectOutOfSubLevel.invoke(companion, level, vectorAtCenter(from), vectorAtCenter(from));
            Object toProjected = projectOutOfSubLevel.invoke(companion, level, vectorAtCenter(to), vectorAtCenter(to));
            double dx = vectorComponent(fromProjected, "x") - vectorComponent(toProjected, "x");
            double dy = vectorComponent(fromProjected, "y") - vectorComponent(toProjected, "y");
            double dz = vectorComponent(fromProjected, "z") - vectorComponent(toProjected, "z");
            return Optional.of(dx * dx + dy * dy + dz * dz < (double) range * range);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            SableScopedLinks.LOGGER.warn("Sable projected redstone link range check failed; falling back to ordinary Create range", exception);
            return Optional.empty();
        }
    }

    private void invalidate(String message, Exception exception) {
        available = false;
        SableScopedLinks.LOGGER.warn(message, exception);
    }

    private void init() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        Optional<Class<?>> companionClass = loadClass(SABLE_COMPANION_CLASS);
        if (companionClass.isEmpty()) {
            SableScopedLinks.LOGGER.warn("Could not find {}. Sable redstone link scoping is inactive.", SABLE_COMPANION_CLASS);
            return;
        }

        Optional<Object> instance = findCompanionInstance(companionClass.get());
        Optional<Method> method = findGetContainingMethod(companionClass.get());
        Optional<Method> intersecting = findGetAllIntersectingMethod(companionClass.get());
        Optional<Method> projection = findProjectOutOfSubLevelMethod(companionClass.get());

        if (instance.isEmpty() || method.isEmpty()) {
            SableScopedLinks.LOGGER.warn("Found {}, but could not bind INSTANCE/getContaining(level, pos). Sable redstone link scoping is inactive.",
                    companionClass.get().getName());
            return;
        }

        companion = instance.get();
        getContaining = method.get();
        getContaining.setAccessible(true);
        getContainingAcceptsBlockPos = getContaining.getParameterTypes()[1].isAssignableFrom(BlockPos.class);
        getAllIntersecting = intersecting.orElse(null);
        if (getAllIntersecting != null) {
            getAllIntersecting.setAccessible(true);
        }
        projectOutOfSubLevel = projection.orElse(null);
        if (projectOutOfSubLevel != null) {
            projectOutOfSubLevel.setAccessible(true);
        }
        boundingBox3dConstructor = findBoundingBox3dConstructor().orElse(null);
        vector3dConstructor = findVector3dConstructor().orElse(null);
        available = true;
        SableScopedLinks.LOGGER.info("Using {} for Sable redstone link scoping", companionClass.get().getName());
    }

    private Optional<Object> findCompanionInstance(Class<?> type) {
        try {
            Optional<Field> cached = CachedReflection.publicField(type, "INSTANCE");
            if (cached.isEmpty()) {
                return Optional.empty();
            }
            Field field = cached.get();
            return Optional.ofNullable(field.get(null));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Optional<Method> findGetAllIntersectingMethod(Class<?> type) {
        Optional<Class<?>> boundingBox3dc = loadClass("dev.ryanhcode.sable.companion.math.BoundingBox3dc");
        if (boundingBox3dc.isEmpty()) {
            return Optional.empty();
        }

        for (Method method : type.getMethods()) {
            if (!method.getName().equals("getAllIntersecting") || method.getParameterCount() != 2) {
                continue;
            }

            if (method.getParameterTypes()[1].isAssignableFrom(boundingBox3dc.get())) {
                return Optional.of(method);
            }
        }

        return Optional.empty();
    }

    private Optional<Method> findGetContainingMethod(Class<?> type) {
        Optional<Class<?>> vector3dc = loadClass("org.joml.Vector3dc");
        for (Method method : type.getMethods()) {
            if (!method.getName().equals("getContaining") || method.getParameterCount() != 2) {
                continue;
            }

            Class<?> positionType = method.getParameterTypes()[1];
            if (method.getParameterTypes()[1].isAssignableFrom(BlockPos.class)
                    || vector3dc.isPresent() && positionType.isAssignableFrom(vector3dc.get())) {
                return Optional.of(method);
            }
        }

        return Optional.empty();
    }

    private Optional<Method> findProjectOutOfSubLevelMethod(Class<?> type) {
        Optional<Class<?>> vector3d = loadClass("org.joml.Vector3d");
        if (vector3d.isEmpty()) {
            return Optional.empty();
        }

        for (Method method : type.getMethods()) {
            if (!method.getName().equals("projectOutOfSubLevel") || method.getParameterCount() != 3) {
                continue;
            }

            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes[1].isAssignableFrom(vector3d.get()) && parameterTypes[2].isAssignableFrom(vector3d.get())) {
                return Optional.of(method);
            }
        }

        return Optional.empty();
    }

    private Optional<Constructor<?>> findVector3dConstructor() {
        Optional<Class<?>> vector3d = loadClass("org.joml.Vector3d");
        if (vector3d.isEmpty()) {
            return Optional.empty();
        }

        try {
            Constructor<?> constructor = vector3d.get().getConstructor(double.class, double.class, double.class);
            constructor.setAccessible(true);
            return Optional.of(constructor);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Optional<Constructor<?>> findBoundingBox3dConstructor() {
        Optional<Class<?>> boundingBox3d = loadClass("dev.ryanhcode.sable.companion.math.BoundingBox3d");
        if (boundingBox3d.isEmpty()) {
            return Optional.empty();
        }

        try {
            Constructor<?> constructor = boundingBox3d.get().getConstructor(
                    double.class, double.class, double.class, double.class, double.class, double.class);
            constructor.setAccessible(true);
            return Optional.of(constructor);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Object vectorAtCenter(BlockPos pos) throws ReflectiveOperationException {
        return vector3dConstructor.newInstance(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private double vectorComponent(Object vector, String methodName) throws ReflectiveOperationException {
        Optional<Method> method = CachedReflection.noArgMethod(vector.getClass(), methodName);
        if (method.isEmpty()) {
            return 0.0;
        }
        Object value = method.get().invoke(vector);
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private double doubleNoArg(Object target, String methodName) throws ReflectiveOperationException {
        Optional<Method> method = CachedReflection.noArgMethod(target.getClass(), methodName);
        if (method.isEmpty()) {
            return 0.0;
        }
        Object value = method.get().invoke(target);
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private Optional<Object> reflectNoArg(Object target, String methodName) {
        if (target == null) {
            return Optional.empty();
        }

        try {
            Optional<Method> method = CachedReflection.noArgMethod(target.getClass(), methodName);
            if (method.isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable(method.get().invoke(target));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private boolean isRemoved(Object subLevel) {
        try {
            Optional<Method> method = CachedReflection.noArgMethod(subLevel.getClass(), "isRemoved");
            if (method.isEmpty()) {
                return false;
            }
            Object result = method.get().invoke(subLevel);
            return result instanceof Boolean value && value;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private Optional<Class<?>> loadClass(String className) {
        return CachedReflection.loadClass(className);
    }
}
