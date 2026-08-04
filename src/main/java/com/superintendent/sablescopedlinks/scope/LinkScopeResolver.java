package com.superintendent.sablescopedlinks.scope;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.superintendent.sablescopedlinks.RedstoneLinkSubLevelScope;
import com.superintendent.sablescopedlinks.SableScopedLinksConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;

public final class LinkScopeResolver {
    private static final SableCompanionBridge SABLE = new SableCompanionBridge();
    private static final ThreadLocal<LinkScope> LINKED_CONTROLLER_SCOPE = new ThreadLocal<>();
    private static final ThreadLocal<Integer> LINKED_CONTROLLER_PROJECTED_RANGE_DEPTH = new ThreadLocal<>();
    private static final ThreadLocal<ScopeCache> SCOPE_CACHE = new ThreadLocal<>();

    private LinkScopeResolver() {
    }

    public static boolean mayCommunicate(RedstoneLinkSubLevelScope mode, Object fallbackWorld, Object from, Object to) {
        LinkScope fromScope = scopeFor(fallbackWorld, from);
        LinkScope toScope = scopeFor(fallbackWorld, to);

        return mayCommunicate(mode, fromScope, toScope);
    }

    public static boolean mayCommunicate(RedstoneLinkSubLevelScope mode, LinkScope fromScope, LinkScope toScope) {
        if (!fromScope.apiResolved() || !toScope.apiResolved()) {
            return SableScopedLinksConfig.FAIL_OPEN_WHEN_SABLE_API_MISSING.get();
        }

        return scopesMayCommunicate(mode, fromScope, toScope);
    }

    public static LinkScope scopeForActor(Object fallbackWorld, Object actor) {
        return scopeFor(fallbackWorld, actor);
    }

    public static boolean mayCommunicateLinkedController(Object fallbackWorld, Object from, Object to, boolean usedSableProjection) {
        Object controller = isLinkedControllerManualFrequencyEntry(from) ? from : to;
        Object receiver = controller == from ? to : from;
        if (!isLinkedControllerManualFrequencyEntry(controller)) {
            return false;
        }

        RedstoneLinkSubLevelScope mode = SableScopedLinksConfig.REDSTONE_LINK_SUB_LEVEL_SCOPE.get();
        Optional<BlockPos> controllerPos = blockPosFor(controller);
        Optional<BlockPos> receiverPos = blockPosFor(receiver);
        if (controllerPos.isEmpty() || receiverPos.isEmpty()) {
            return false;
        }

        Optional<Object> receiverSubLevel = SABLE.getContaining(fallbackWorld, receiverPos.get());
        if (receiverSubLevel.isPresent()) {
            if (!usedSableProjection) {
                return false;
            }

            if (SABLE.subLevelContainsPhysical(receiverSubLevel.get(), controllerPos.get())) {
                return true;
            }

            LinkScope controllerScope = scopeFor(fallbackWorld, controller);
            LinkScope receiverScope = LinkScope.subLevel(
                    dimensionKey(fallbackWorld),
                    subLevelScopeIdentity(receiverSubLevel.get()));
            return controllerScope.apiResolved() && receiverScope.apiResolved()
                    && scopesMayCommunicate(mode, controllerScope, receiverScope);
        }

        if (mode == RedstoneLinkSubLevelScope.SUBLEVEL_AND_WORLD) {
            return mayCommunicate(mode, fallbackWorld, controller, receiver);
        }

        return SABLE.getIntersectingPhysical(fallbackWorld, controllerPos.get()).isEmpty()
                && mayCommunicate(mode, fallbackWorld, controller, receiver);
    }

    public static Optional<Boolean> projectedWithinRange(Object world, Object from, Object to, int range) {
        Optional<BlockPos> fromPos = blockPosFor(from);
        Optional<BlockPos> toPos = blockPosFor(to);
        if (fromPos.isEmpty() || toPos.isEmpty()) {
            return Optional.empty();
        }

        return SABLE.projectedWithinRange(world, fromPos.get(), toPos.get(), range);
    }

    public static void enterScopeCache() {
        ScopeCache cache = SCOPE_CACHE.get();
        if (cache == null) {
            SCOPE_CACHE.set(new ScopeCache());
            return;
        }

        cache.depth++;
    }

    public static void exitScopeCache() {
        ScopeCache cache = SCOPE_CACHE.get();
        if (cache == null || cache.depth <= 1) {
            SCOPE_CACHE.remove();
            return;
        }

        cache.depth--;
    }

    public static void enterLinkedControllerScope(LevelAccessor world, BlockPos pos, UUID playerId) {
        LINKED_CONTROLLER_SCOPE.remove();

        Optional<Object> player = playerById(world, playerId);
        if (player.isPresent()) {
            Optional<LinkScope> scope = scopeForSableTrackedEntity(world, player.get());
            if (scope.isPresent()) {
                LINKED_CONTROLLER_SCOPE.set(scope.get());
                return;
            }
        }

        Optional<Object> plotSubLevel = SABLE.getContaining(world, pos);
        if (plotSubLevel.isPresent()) {
            LinkScope scope = LinkScope.subLevel(dimensionKey(world), subLevelScopeIdentity(plotSubLevel.get()));
            LINKED_CONTROLLER_SCOPE.set(scope);
            return;
        }

        Optional<Object> physicalSubLevel = SABLE.getIntersectingPhysical(world, pos);
        if (physicalSubLevel.isEmpty()) {
            return;
        }

        LinkScope scope = LinkScope.subLevel(dimensionKey(world), subLevelScopeIdentity(physicalSubLevel.get()));
        LINKED_CONTROLLER_SCOPE.set(scope);
    }

    public static void exitLinkedControllerScope() {
        LINKED_CONTROLLER_SCOPE.remove();
    }

    public static void enterLinkedControllerProjectedRangeCheck() {
        Integer depth = LINKED_CONTROLLER_PROJECTED_RANGE_DEPTH.get();
        LINKED_CONTROLLER_PROJECTED_RANGE_DEPTH.set(depth == null ? 1 : depth + 1);
    }

    public static void exitLinkedControllerProjectedRangeCheck() {
        Integer depth = LINKED_CONTROLLER_PROJECTED_RANGE_DEPTH.get();
        if (depth == null || depth <= 1) {
            LINKED_CONTROLLER_PROJECTED_RANGE_DEPTH.remove();
            return;
        }

        LINKED_CONTROLLER_PROJECTED_RANGE_DEPTH.set(depth - 1);
    }

    public static boolean isLinkedControllerProjectedRangeCheck() {
        Integer depth = LINKED_CONTROLLER_PROJECTED_RANGE_DEPTH.get();
        return depth != null && depth > 0;
    }

    private static LinkScope scopeFor(Object fallbackWorld, Object actor) {
        if (isLinkedControllerManualFrequencyEntry(actor)) {
            LinkScope controllerScope = LINKED_CONTROLLER_SCOPE.get();
            if (controllerScope != null) {
                return controllerScope;
            }
        }

        ScopeCache cache = SCOPE_CACHE.get();
        if (cache != null) {
            ScopeCacheKey key = new ScopeCacheKey(fallbackWorld, actor);
            LinkScope cached = cache.scopes.get(key);
            if (cached != null) {
                return cached;
            }

            LinkScope resolved = resolveScopeFor(fallbackWorld, actor);
            cache.scopes.put(key, resolved);
            return resolved;
        }

        return resolveScopeFor(fallbackWorld, actor);
    }

    private static LinkScope resolveScopeFor(Object fallbackWorld, Object actor) {
        Object world = firstPresent(reflectNoArg(actor, "getWorld"), Optional.ofNullable(fallbackWorld)).orElse(null);
        Object dimension = dimensionKey(world);
        Optional<BlockPos> pos = blockPosFor(actor);

        if (world == null || pos.isEmpty()) {
            return LinkScope.world(dimension, SABLE.isAvailable());
        }

        Optional<Object> subLevel = SABLE.getContaining(world, pos.get());
        if (subLevel.isPresent()) {
            return LinkScope.subLevel(dimension, subLevelScopeIdentity(subLevel.get()));
        }

        Optional<Object> physicalSubLevel = SABLE.getIntersectingPhysical(world, pos.get());
        if (physicalSubLevel.isPresent()) {
            return LinkScope.subLevel(dimension, subLevelScopeIdentity(physicalSubLevel.get()));
        }

        return LinkScope.world(dimension, SABLE.isAvailable());
    }

    private static boolean scopesMayCommunicate(RedstoneLinkSubLevelScope mode, LinkScope fromScope, LinkScope toScope) {
        return switch (mode) {
            case VANILLA_CREATE -> true;
            case SAME_SUBLEVEL_ONLY -> fromScope.sameScope(toScope);
            case SUBLEVEL_AND_WORLD -> fromScope.sameScope(toScope)
                    || fromScope.sameDimension(toScope) && (fromScope.isWorldScope() || toScope.isWorldScope());
        };
    }

    private static Optional<LinkScope> scopeForSableTrackedEntity(Object world, Object entity) {
        Object dimension = dimensionKey(world);
        Optional<Object> trackingSubLevel = reflectNoArg(entity, "sable$getTrackingSubLevel");
        Optional<Object> unwrappedTrackingSubLevel = trackingSubLevel.flatMap(LinkScopeResolver::unwrapOptional);
        if (unwrappedTrackingSubLevel.isPresent() && !isRemoved(unwrappedTrackingSubLevel.get())) {
            Object subLevel = unwrappedTrackingSubLevel.get();
            return Optional.of(LinkScope.subLevel(dimension, subLevelScopeIdentity(subLevel)));
        }

        Optional<Object> plotPosition = reflectNoArg(entity, "sable$getPlotPosition");
        if (plotPosition.isEmpty() || !(plotPosition.get() instanceof Vec3 pos)) {
            return Optional.empty();
        }

        Optional<Object> subLevel = SABLE.getContaining(world, BlockPos.containing(pos));
        return subLevel.map(value -> LinkScope.subLevel(dimension, subLevelScopeIdentity(value)));
    }

    private static boolean isRemoved(Object subLevel) {
        Optional<Object> removed = reflectNoArg(subLevel, "isRemoved");
        return removed.orElse(false) instanceof Boolean value && value;
    }

    private static Optional<Object> unwrapOptional(Object value) {
        if (value instanceof Optional<?> optional) {
            return optional.map(Object.class::cast);
        }

        return Optional.ofNullable(value);
    }

    private static Optional<Object> playerById(Object world, UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }

        try {
            Optional<Method> cached = CachedReflection.publicMethod(world.getClass(), "getPlayerByUUID", UUID.class);
            if (cached.isEmpty()) {
                return Optional.empty();
            }
            Method method = cached.get();
            return Optional.ofNullable(method.invoke(world, playerId));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public static boolean isLinkedControllerManualFrequencyEntry(Object actor) {
        return actor != null && actor.getClass().getName().equals(
                "com.simibubi.create.content.redstone.link.controller.LinkedControllerServerHandler$ManualFrequencyEntry");
    }

    private static Optional<BlockPos> blockPosFor(Object actor) {
        Optional<Object> location = reflectNoArg(actor, "getLocation");
        if (location.orElse(null) instanceof BlockPos pos) {
            return Optional.of(pos);
        }

        Optional<Object> pos = reflectNoArg(actor, "getPos");
        if (pos.orElse(null) instanceof BlockPos blockPos) {
            return Optional.of(blockPos);
        }

        return Optional.empty();
    }

    private static Object dimensionKey(Object world) {
        Optional<Object> dimension = reflectNoArg(world, "dimension");
        if (dimension.isPresent()) {
            Object key = dimension.get();
            Optional<Object> location = reflectNoArg(key, "location");
            return location.orElse(key);
        }

        return world == null ? "unknown" : world;
    }

    private static Object subLevelIdentity(Object subLevel) {
        for (String methodName : new String[] {
                "getUniqueId", "uuid", "getUuid", "getUUID", "identifier", "getIdentifier", "key", "getKey"
        }) {
            Optional<Object> value = reflectNoArg(subLevel, methodName);
            if (value.isPresent()) {
                return new SubLevelIdentity(subLevel.getClass().getName(), value.get());
            }
        }

        Optional<Object> bounds = boundingBoxIdentity(subLevel);
        if (bounds.isPresent()) {
            return new SubLevelIdentity(subLevel.getClass().getName(), bounds.get());
        }

        for (String methodName : new String[] { "id", "getId" }) {
            Optional<Object> value = reflectNoArg(subLevel, methodName);
            if (value.isPresent()) {
                return new SubLevelIdentity(subLevel.getClass().getName(), value.get());
            }
        }

        return subLevel;
    }

    private static Object subLevelScopeIdentity(Object subLevel) {
        return AttachedSubLevelScopeRegistry.componentIdentity(subLevel)
                .orElseGet(() -> subLevelIdentity(subLevel));
    }

    private static Optional<Object> boundingBoxIdentity(Object subLevel) {
        Optional<Object> bounds = reflectNoArg(subLevel, "boundingBox");
        if (bounds.isEmpty()) {
            return Optional.empty();
        }

        Optional<Double> minX = doubleNoArg(bounds.get(), "minX");
        Optional<Double> minY = doubleNoArg(bounds.get(), "minY");
        Optional<Double> minZ = doubleNoArg(bounds.get(), "minZ");
        Optional<Double> maxX = doubleNoArg(bounds.get(), "maxX");
        Optional<Double> maxY = doubleNoArg(bounds.get(), "maxY");
        Optional<Double> maxZ = doubleNoArg(bounds.get(), "maxZ");
        if (minX.isEmpty() || minY.isEmpty() || minZ.isEmpty()
                || maxX.isEmpty() || maxY.isEmpty() || maxZ.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(List.of(minX.get(), minY.get(), minZ.get(), maxX.get(), maxY.get(), maxZ.get()));
    }

    private static Optional<Double> doubleNoArg(Object target, String methodName) {
        Optional<Object> value = reflectNoArg(target, methodName);
        if (value.orElse(null) instanceof Number number) {
            return Optional.of(number.doubleValue());
        }

        return Optional.empty();
    }

    private static Optional<Object> reflectNoArg(Object target, String methodName) {
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

    @SafeVarargs
    private static <T> Optional<T> firstPresent(Optional<? extends T>... values) {
        for (Optional<? extends T> value : values) {
            if (value.isPresent()) {
                return Optional.of(value.get());
            }
        }
        return Optional.empty();
    }

    private record SubLevelIdentity(String type, Object value) {
    }

    private static final class ScopeCache {
        private final Map<ScopeCacheKey, LinkScope> scopes = new HashMap<>();
        private int depth = 1;
    }

    private static final class ScopeCacheKey {
        private final Object world;
        private final Object actor;

        private ScopeCacheKey(Object world, Object actor) {
            this.world = world;
            this.actor = actor;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ScopeCacheKey key && world == key.world && actor == key.actor;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(world) + System.identityHashCode(actor);
        }
    }
}
