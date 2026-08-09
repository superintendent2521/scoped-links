package com.superintendent.sablescopedlinks.scope;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.superintendent.sablescopedlinks.RedstoneLinkSubLevelScope;
import com.superintendent.sablescopedlinks.SableScopedLinks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;

public final class RedstoneLinkNetworkProxy {
    private static final AtomicBoolean LOGGED_MISSING_FREQUENCY = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_NETWORK_INSPECTION_FAILURE = new AtomicBoolean();
    private static final ThreadLocal<DashpanelsAddBatch> DASHPANELS_ADD_BATCH = new ThreadLocal<>();
    private static volatile int cachedCreateLinkRange = -1;

    private RedstoneLinkNetworkProxy() {
    }

    public static void updateNetwork(Object handler, LevelAccessor world, Object actor, RedstoneLinkSubLevelScope mode) {
        LinkScopeResolver.enterScopeCache();
        try {
            updateNetworkCached(handler, world, actor, mode);
        } finally {
            LinkScopeResolver.exitScopeCache();
        }
    }

    public static boolean shouldSkipRedundantDashpanelsAdd(Object handler, LevelAccessor world, Object actor) {
        if (!LinkScopeResolver.isDashpanelsModuleEntry(actor)) {
            return false;
        }

        Optional<Object> gameTimeValue = CachedReflection.invokeNoArg(world, "getGameTime");
        if (gameTimeValue.orElse(null) instanceof Number gameTime) {
            Set<?> network = getNetworkOf(handler, world, actor);
            boolean alreadyRegistered = network.contains(actor);

            DashpanelsAddBatch batch = DASHPANELS_ADD_BATCH.get();
            if (batch == null || batch.world != world || batch.gameTime != gameTime.longValue()) {
                batch = new DashpanelsAddBatch(world, gameTime.longValue());
                DASHPANELS_ADD_BATCH.set(batch);
            }

            if (!alreadyRegistered) {
                batch.updatedNetworks.add(network);
                return false;
            }

            return !batch.updatedNetworks.add(network);
        }

        return false;
    }

    private static void updateNetworkCached(Object handler, LevelAccessor world, Object actor, RedstoneLinkSubLevelScope mode) {
        Set<?> network = getNetworkOf(handler, world, actor);
        incrementGlobalPowerVersion(handler);

        UpdateContext context = new UpdateContext(handler, world, mode);
        List<ActorInfo> aliveLinks = new ArrayList<>();
        for (Iterator<?> iterator = network.iterator(); iterator.hasNext();) {
            Object other = iterator.next();
            ActorInfo otherInfo = context.infoFor(other);
            if (!otherInfo.alive()) {
                iterator.remove();
                continue;
            }

            aliveLinks.add(otherInfo);
        }

        ActorInfo actorInfo = context.infoFor(actor);
        if (actorInfo.listening()) {
            setNewPosition(actor);
            setReceivedStrength(actor, context.receivedPowerFor(actorInfo, aliveLinks));
        }

        for (ActorInfo other : aliveLinks) {
            if (other.actor() != actor && other.listening()) {
                setReceivedStrength(other.actor(), context.receivedPowerFor(other, aliveLinks));
            }
        }
    }

    public static boolean hasAnyLoadedPowerInScope(
            Object handler,
            Object frequency,
            LevelAccessor world,
            BlockPos origin,
            RedstoneLinkSubLevelScope mode) {
        if (frequency == null) {
            if (LOGGED_MISSING_FREQUENCY.compareAndSet(false, true)) {
                SableScopedLinks.LOGGER.warn("Could not resolve RedstoneLinkCondition frequency; scoped schedule condition will report unpowered.");
            }
            return false;
        }

        Object originActor = new PositionedScopeActor(world, origin);

        try {
            Optional<Field> cached = CachedReflection.declaredField(handler.getClass(), "connections");
            if (cached.isEmpty()) {
                return false;
            }
            Field connections = cached.get();
            Object value = connections.get(Modifier.isStatic(connections.getModifiers()) ? null : handler);
            if (!(value instanceof Map<?, ?> worlds)) {
                return false;
            }

            LinkScopeResolver.enterScopeCache();
            try {
                for (Map.Entry<?, ?> worldEntry : worlds.entrySet()) {
                    if (!(worldEntry.getValue() instanceof Map<?, ?> networks)) {
                        continue;
                    }

                    Object network = networks.get(frequency);
                    if (!(network instanceof Set<?> links)) {
                        continue;
                    }

                    for (Object link : links) {
                        if (transmittedStrength(link) > 0 && LinkScopeResolver.mayCommunicate(mode, worldEntry.getKey(), originActor, link)) {
                            return true;
                        }
                    }
                }
            } finally {
                LinkScopeResolver.exitScopeCache();
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (LOGGED_NETWORK_INSPECTION_FAILURE.compareAndSet(false, true)) {
                SableScopedLinks.LOGGER.warn("Could not inspect Create redstone link networks for scoped schedule condition.", exception);
            }
        }

        return false;
    }

    private static RangeCheck projectedWithinRange(Object handler, LevelAccessor world, Object from, Object to) {
        if (from == to) {
            return new RangeCheck(true, false);
        }

        Optional<Boolean> projectedRange = LinkScopeResolver.projectedWithinRange(world, from, to, createLinkRange());
        if (projectedRange.isPresent()) {
            return new RangeCheck(projectedRange.get(), true);
        }

        Optional<Method> sableMethod = findMethod(handler.getClass(), "sable$projectComparisons", 3);
        if (sableMethod.isPresent()) {
            boolean linkedControllerComparison = LinkScopeResolver.isLinkedControllerManualFrequencyEntry(from)
                    || LinkScopeResolver.isLinkedControllerManualFrequencyEntry(to);
            try {
                Method method = sableMethod.get();
                method.setAccessible(true);
                if (linkedControllerComparison) {
                    LinkScopeResolver.enterLinkedControllerProjectedRangeCheck();
                }
                Object result = method.invoke(handler, from, to, world);
                if (result instanceof Boolean value) {
                    return new RangeCheck(value, true);
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                SableScopedLinks.LOGGER.debug("Sable projected comparison call failed; falling back to ordinary Create range.", exception);
            } finally {
                if (linkedControllerComparison) {
                    LinkScopeResolver.exitLinkedControllerProjectedRangeCheck();
                }
            }
        }

        Optional<Method> withinRange = findMethod(handler.getClass(), "withinRange", 2);
        if (withinRange.isPresent()) {
            try {
                Method method = withinRange.get();
                method.setAccessible(true);
                Object result = method.invoke(null, from, to);
                if (result instanceof Boolean value) {
                    return new RangeCheck(value, false);
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                SableScopedLinks.LOGGER.debug("Create withinRange call failed; allowing scoped range check to continue.", exception);
            }
        }

        return new RangeCheck(true, false);
    }

    private static int createLinkRange() {
        int cached = cachedCreateLinkRange;
        if (cached > 0) {
            return cached;
        }

        try {
            Optional<Class<?>> allConfigs = CachedReflection.loadClass("com.simibubi.create.infrastructure.config.AllConfigs");
            if (allConfigs.isEmpty()) {
                return 256;
            }

            Optional<Method> serverMethod = CachedReflection.publicMethod(allConfigs.get(), "server");
            if (serverMethod.isEmpty()) {
                return 256;
            }
            Method server = serverMethod.get();
            Object serverConfig = server.invoke(null);
            Optional<Field> logisticsField = CachedReflection.publicField(serverConfig.getClass(), "logistics");
            if (logisticsField.isEmpty()) {
                return 256;
            }
            Field logistics = logisticsField.get();
            Object logisticsConfig = logistics.get(serverConfig);
            Optional<Field> linkRangeField = CachedReflection.publicField(logisticsConfig.getClass(), "linkRange");
            if (linkRangeField.isEmpty()) {
                return 256;
            }
            Field linkRange = linkRangeField.get();
            Object configValue = linkRange.get(logisticsConfig);
            Optional<Method> getMethod = CachedReflection.publicMethod(configValue.getClass(), "get");
            if (getMethod.isEmpty()) {
                return 256;
            }
            Method get = getMethod.get();
            Object value = get.invoke(configValue);
            if (value instanceof Number number) {
                cachedCreateLinkRange = number.intValue();
                return cachedCreateLinkRange;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }

        return 256;
    }

    private static Set<?> getNetworkOf(Object handler, LevelAccessor world, Object actor) {
        Optional<Method> method = findMethod(handler.getClass(), "getNetworkOf", 2);
        if (method.isEmpty()) {
            throw new IllegalStateException("Could not find Create RedstoneLinkNetworkHandler#getNetworkOf");
        }

        try {
            Method getNetworkOf = method.get();
            getNetworkOf.setAccessible(true);
            Object result = getNetworkOf.invoke(handler, world, actor);
            if (result instanceof Set<?> set) {
                return set;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Could not invoke Create RedstoneLinkNetworkHandler#getNetworkOf", exception);
        }

        throw new IllegalStateException("Create RedstoneLinkNetworkHandler#getNetworkOf did not return a Set");
    }

    private static void incrementGlobalPowerVersion(Object handler) {
        try {
            Optional<Field> cached = CachedReflection.publicField(handler.getClass(), "globalPowerVersion");
            if (cached.isEmpty()) {
                return;
            }
            Field field = cached.get();
            Object value = field.get(handler);
            if (value instanceof AtomicInteger counter) {
                counter.incrementAndGet();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static boolean isAlive(Object actor) {
        return invokeBoolean(actor, "isAlive", false);
    }

    private static boolean isListening(Object actor) {
        return invokeBoolean(actor, "isListening", false);
    }

    private static int transmittedStrength(Object actor) {
        return invokeInt(actor, "getTransmittedStrength", 0);
    }

    private static void setReceivedStrength(Object actor, int power) {
        Optional<Method> method = findMethod(actor.getClass(), "setReceivedStrength", 1);
        if (method.isEmpty()) {
            return;
        }

        try {
            Method setReceivedStrength = method.get();
            setReceivedStrength.setAccessible(true);
            setReceivedStrength.invoke(actor, power);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static void setNewPosition(Object actor) {
        try {
            Optional<Field> cached = CachedReflection.publicField(actor.getClass(), "newPosition");
            if (cached.isEmpty()) {
                return;
            }
            Field field = cached.get();
            field.setBoolean(actor, true);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static boolean invokeBoolean(Object target, String methodName, boolean fallback) {
        Optional<Method> method = findMethod(target.getClass(), methodName, 0);
        if (method.isEmpty()) {
            return fallback;
        }

        try {
            Method reflected = method.get();
            reflected.setAccessible(true);
            Object value = reflected.invoke(target);
            return value instanceof Boolean bool ? bool : fallback;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static int invokeInt(Object target, String methodName, int fallback) {
        Optional<Method> method = findMethod(target.getClass(), methodName, 0);
        if (method.isEmpty()) {
            return fallback;
        }

        try {
            Method reflected = method.get();
            reflected.setAccessible(true);
            Object value = reflected.invoke(target);
            return value instanceof Number number ? number.intValue() : fallback;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static Optional<Method> findMethod(Class<?> type, String name, int parameterCount) {
        return CachedReflection.namedMethod(type, name, parameterCount);
    }

    private static final class UpdateContext {
        private final Object handler;
        private final LevelAccessor world;
        private final RedstoneLinkSubLevelScope mode;
        private final Map<Object, ActorInfo> actorInfos = new IdentityHashMap<>();
        private final Map<Object, Integer> receivedPower = new IdentityHashMap<>();
        private final Map<ActorPair, RangeCheck> rangeChecks = new HashMap<>();
        private final Map<ActorPair, Boolean> communicationChecks = new HashMap<>();

        private UpdateContext(Object handler, LevelAccessor world, RedstoneLinkSubLevelScope mode) {
            this.handler = handler;
            this.world = world;
            this.mode = mode;
        }

        private ActorInfo infoFor(Object actor) {
            return actorInfos.computeIfAbsent(actor, ActorInfo::new);
        }

        private int receivedPowerFor(ActorInfo receiver, Iterable<ActorInfo> network) {
            return receivedPower.computeIfAbsent(receiver.actor(), ignored -> calculateReceivedPowerFor(receiver, network));
        }

        private int calculateReceivedPowerFor(ActorInfo receiver, Iterable<ActorInfo> network) {
            int power = 0;
            for (ActorInfo sender : network) {
                int strength = sender.transmittedStrength();
                if (strength <= power) {
                    continue;
                }

                if (!withinScopedRange(receiver, sender)) {
                    continue;
                }

                power = strength;
                if (power >= 15) {
                    break;
                }
            }

            return power;
        }

        private boolean withinScopedRange(ActorInfo from, ActorInfo to) {
            ActorPair pair = new ActorPair(from.actor(), to.actor());
            RangeCheck rangeCheck = rangeChecks.computeIfAbsent(pair,
                    ignored -> projectedWithinRange(handler, world, from.actor(), to.actor()));
            if (!rangeCheck.inRange()) {
                return false;
            }

            return communicationChecks.computeIfAbsent(pair,
                    ignored -> mayCommunicate(from, to, rangeCheck));
        }

        private boolean mayCommunicate(ActorInfo from, ActorInfo to, RangeCheck rangeCheck) {
            if (from.linkedControllerManualFrequencyEntry() || to.linkedControllerManualFrequencyEntry()) {
                return LinkScopeResolver.mayCommunicateLinkedController(
                        world,
                        from.actor(),
                        to.actor(),
                        rangeCheck.usedSableProjection());
            }

            return LinkScopeResolver.mayCommunicate(mode, from.scope(world), to.scope(world));
        }
    }

    private static final class ActorInfo {
        private final Object actor;
        private final boolean alive;
        private final boolean listening;
        private final int transmittedStrength;
        private final boolean linkedControllerManualFrequencyEntry;
        private LinkScope scope;

        private ActorInfo(Object actor) {
            this.actor = actor;
            this.alive = isAlive(actor);
            this.listening = isListening(actor);
            this.transmittedStrength = RedstoneLinkNetworkProxy.transmittedStrength(actor);
            this.linkedControllerManualFrequencyEntry = LinkScopeResolver.isLinkedControllerManualFrequencyEntry(actor);
        }

        private Object actor() {
            return actor;
        }

        private boolean alive() {
            return alive;
        }

        private boolean listening() {
            return listening;
        }

        private int transmittedStrength() {
            return transmittedStrength;
        }

        private boolean linkedControllerManualFrequencyEntry() {
            return linkedControllerManualFrequencyEntry;
        }

        private LinkScope scope(Object fallbackWorld) {
            if (scope == null) {
                scope = LinkScopeResolver.scopeForActor(fallbackWorld, actor);
            }

            return scope;
        }
    }

    private static final class ActorPair {
        private final Object from;
        private final Object to;

        private ActorPair(Object from, Object to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ActorPair pair && from == pair.from && to == pair.to;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(from) + System.identityHashCode(to);
        }
    }

    public static final class PositionedScopeActor {
        private final Object world;
        private final BlockPos location;

        public PositionedScopeActor(Object world, BlockPos location) {
            this.world = world;
            this.location = location;
        }

        public Object getWorld() {
            return world;
        }

        public BlockPos getLocation() {
            return location;
        }
    }

    private record RangeCheck(boolean inRange, boolean usedSableProjection) {
    }

    private static final class DashpanelsAddBatch {
        private final Object world;
        private final long gameTime;
        private final Set<Object> updatedNetworks = Collections.newSetFromMap(new IdentityHashMap<>());

        private DashpanelsAddBatch(Object world, long gameTime) {
            this.world = world;
            this.gameTime = gameTime;
        }
    }
}
