package com.superintendent.sablescopedlinks.scope;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import com.superintendent.sablescopedlinks.SableScopedLinks;
public final class AttachedSubLevelScopeRegistry {
    private static final List<Edge> EDGES = new ArrayList<>();
    private static final IdentityHashMap<Object, RopeLink> ROPES = new IdentityHashMap<>();
    private static final Map<Object, RopeLink> ROPES_BY_HANDLE = new HashMap<>();
    private static final Map<BodyKey, Object> COMPONENT_BY_BODY = new HashMap<>();
    private static final IdentityHashMap<Object, BodyKey> BODY_KEYS = new IdentityHashMap<>();
    private static final Map<Class<?>, Optional<Method>> SUB_LEVEL_CONTAINER_GETTERS = new HashMap<>();
    private static final Map<Object, Map<UUID, Object>> SUB_LEVELS_BY_UUID_BY_LEVEL = new WeakHashMap<>();
    private static boolean dirty = true;

    private AttachedSubLevelScopeRegistry() {
    }

    public static synchronized void recordConstraint(Object firstBody, Object secondBody, Object configuration, Object handle) {
        if (!isRotaryConstraint(configuration) || !isServerSubLevel(firstBody) || !isServerSubLevel(secondBody)) {
            return;
        }

        Optional<BodyKey> first = bodyKey(firstBody);
        Optional<BodyKey> second = bodyKey(secondBody);
        if (first.isEmpty() || second.isEmpty() || !Objects.equals(first.get().dimensionKey(), second.get().dimensionKey())) {
            return;
        }

        EDGES.add(new Edge(first.get(), second.get(), firstBody, secondBody, handle));
        if (SableScopedLinks.LOGGER.isDebugEnabled()) {
            SableScopedLinks.LOGGER.debug("Tracked rotary attachment {} <-> {}", first.get(), second.get());
        }
        dirty = true;
    }

    public static synchronized void recordRopeAttachment(Object rope, Object attachmentPoint, Object subLevel) {
        if (!isRopePhysicsObject(rope) || attachmentPoint == null || subLevel != null && !isServerSubLevel(subLevel)) {
            return;
        }

        RopeLink link = ROPES.computeIfAbsent(rope, ignored -> new RopeLink(rope));
        syncRopeFields(link);
        boolean changed = false;
        if (isStartAttachment(attachmentPoint)) {
            changed = !Objects.equals(bodyKeyOrNull(link.startBody), bodyKeyOrNull(subLevel));
            link.startBody = subLevel;
        } else if (isEndAttachment(attachmentPoint)) {
            changed = !Objects.equals(bodyKeyOrNull(link.endBody), bodyKeyOrNull(subLevel));
            link.endBody = subLevel;
        }

        if (link.handle != null) {
            ROPES_BY_HANDLE.put(link.handle, link);
        }
        logRopeState("rope-object", attachmentPoint, link);
        dirty |= changed;
    }

    public static synchronized void recordRopeHandle(Object rope, Object handle) {
        if (!isRopePhysicsObject(rope) || handle == null) {
            return;
        }

        RopeLink link = ROPES.computeIfAbsent(rope, ignored -> new RopeLink(rope));
        BodyKey previousStart = bodyKeyOrNull(link.startBody);
        BodyKey previousEnd = bodyKeyOrNull(link.endBody);
        Object previousHandle = link.handle;
        link.handle = handle;
        syncRopeFields(link);
        ROPES_BY_HANDLE.put(handle, link);
        logRopeState("rope-handle-created", "HANDLE", link);
        dirty |= previousHandle != link.handle
                || !Objects.equals(previousStart, bodyKeyOrNull(link.startBody))
                || !Objects.equals(previousEnd, bodyKeyOrNull(link.endBody));
    }

    public static synchronized void recordRopeHandleAttachment(Object handle, Object attachmentPoint, Object subLevel) {
        if (handle == null || attachmentPoint == null || subLevel != null && !isServerSubLevel(subLevel)) {
            return;
        }

        RopeLink link = ROPES_BY_HANDLE.get(handle);
        if (link == null) {
            return;
        }

        boolean changed = false;
        if (isStartAttachment(attachmentPoint)) {
            changed = !Objects.equals(bodyKeyOrNull(link.startBody), bodyKeyOrNull(subLevel));
            link.startBody = subLevel;
        } else if (isEndAttachment(attachmentPoint)) {
            changed = !Objects.equals(bodyKeyOrNull(link.endBody), bodyKeyOrNull(subLevel));
            link.endBody = subLevel;
        }
        logRopeState("rope-handle", attachmentPoint, link);
        dirty |= changed;
    }

    public static synchronized void recordSimulatedRope(Object rope, Object level) {
        if (!hasClassName(rope, "dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerRopeStrand")
                || level == null) {
            return;
        }

        Optional<Iterable<?>> attachments = simulatedRopeAttachments(rope);
        if (attachments.isEmpty()) {
            return;
        }

        RopeLink link = ROPES.computeIfAbsent(rope, ignored -> new RopeLink(rope));
        syncRopeFields(link);
        BodyKey previousStart = bodyKeyOrNull(link.startBody);
        BodyKey previousEnd = bodyKeyOrNull(link.endBody);
        Object previousHandle = link.handle;
        link.startBody = null;
        link.endBody = null;

        Map<UUID, Object> subLevelCache = subLevelCache(level);
        for (Object attachment : attachments.get()) {
            Object attachmentPoint = reflectNoArg(attachment, "point").orElse(null);
            Object subLevelId = reflectNoArg(attachment, "subLevelID").orElse(null);
            Object subLevel = subLevelByUuid(level, subLevelCache, subLevelId).orElse(null);
            if (subLevel == null) {
                continue;
            }

            String attachmentName = attachmentPointName(attachmentPoint);
            if ("START".equals(attachmentName)) {
                link.startBody = subLevel;
            } else if ("END".equals(attachmentName)) {
                link.endBody = subLevel;
            }
        }

        simulatedRopeConstraint(rope).ifPresent(handle -> link.handle = handle);
        if (link.handle != null) {
            ROPES_BY_HANDLE.put(link.handle, link);
        }
        logRopeState("simulated-rope", "SNAPSHOT", link);
        dirty |= !Objects.equals(previousStart, bodyKeyOrNull(link.startBody))
                || !Objects.equals(previousEnd, bodyKeyOrNull(link.endBody))
                || previousHandle != link.handle;
    }

    public static synchronized void recordSimulatedRopeFromPhysicsSystem(Object rope, Object physicsSystem) {
        Object level = reflectNoArg(physicsSystem, "getLevel").orElse(null);
        recordSimulatedRope(rope, level);
    }

    public static synchronized void recordSimulatedRopeConstraint(Object rope) {
        RopeLink link = ROPES.get(rope);
        if (link == null) {
            return;
        }

        Object previousHandle = link.handle;
        simulatedRopeConstraint(rope).ifPresent(handle -> link.handle = handle);
        if (link.handle != null) {
            ROPES_BY_HANDLE.put(link.handle, link);
        }
        if (previousHandle != link.handle) {
            dirty = true;
        }
    }

    public static synchronized void clearSimulatedRopeConstraint(Object rope) {
        RopeLink link = ROPES.get(rope);
        if (link == null || link.handle == null) {
            return;
        }

        ROPES_BY_HANDLE.remove(link.handle);
        link.handle = null;
        dirty = true;
    }

    public static synchronized void removeSimulatedRope(Object rope) {
        RopeLink link = ROPES.remove(rope);
        if (link != null) {
            if (link.handle != null) {
                ROPES_BY_HANDLE.remove(link.handle);
            }
            if (SableScopedLinks.LOGGER.isDebugEnabled()) {
                SableScopedLinks.LOGGER.debug("Removed simulated rope attachment {}", rope);
            }
            dirty = true;
        }
    }

    public static synchronized void removeRopeHandle(Object handle) {
        RopeLink link = ROPES_BY_HANDLE.remove(handle);
        if (link != null) {
            ROPES.remove(link.rope);
            if (SableScopedLinks.LOGGER.isDebugEnabled()) {
                SableScopedLinks.LOGGER.debug("Removed rope attachment handle {}", handle);
            }
            dirty = true;
        }
    }

    public static synchronized void removeConstraintHandle(Object handle) {
        if (EDGES.removeIf(edge -> edge.handle() == handle)) {
            if (SableScopedLinks.LOGGER.isDebugEnabled()) {
                SableScopedLinks.LOGGER.debug("Removed rotary attachment handle {}", handle);
            }
            dirty = true;
        }
    }

    private static boolean isRotaryConstraint(Object configuration) {
        return configuration != null
                && configuration.getClass().getName().equals("dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintConfiguration");
    }

    private static boolean isRopePhysicsObject(Object rope) {
        return hasClassName(rope, "dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject");
    }

    private static boolean isServerSubLevel(Object body) {
        return hasClassName(body, "dev.ryanhcode.sable.sublevel.ServerSubLevel");
    }

    private static boolean hasClassName(Object target, String className) {
        if (target == null) {
            return false;
        }

        Class<?> type = target.getClass();
        while (type != null) {
            if (type.getName().equals(className)) {
                return true;
            }
            type = type.getSuperclass();
        }

        return false;
    }

    private static boolean isStartAttachment(Object attachmentPoint) {
        return "START".equals(attachmentPointName(attachmentPoint));
    }

    private static boolean isEndAttachment(Object attachmentPoint) {
        return "END".equals(attachmentPointName(attachmentPoint));
    }

    private static String attachmentPointName(Object attachmentPoint) {
        return String.valueOf(reflectNoArg(attachmentPoint, "name").orElse(null));
    }

    static synchronized Optional<Object> componentIdentity(Object level, Object subLevel) {
        Optional<BodyKey> origin = bodyKey(subLevel);
        if (origin.isEmpty()) {
            return Optional.empty();
        }

        rebuildComponentsIfDirty();

        return Optional.ofNullable(COMPONENT_BY_BODY.get(origin.get()));
    }

    private static void rebuildComponentsIfDirty() {
        if (!dirty) {
            return;
        }

        pruneInvalidEdges();
        pruneInvalidRopes();
        COMPONENT_BY_BODY.clear();

        Set<BodyKey> remaining = new HashSet<>();
        Map<BodyKey, List<BodyKey>> adjacency = new HashMap<>();
        for (Edge edge : EDGES) {
            remaining.add(edge.first());
            remaining.add(edge.second());
            adjacency.computeIfAbsent(edge.first(), ignored -> new ArrayList<>()).add(edge.second());
            adjacency.computeIfAbsent(edge.second(), ignored -> new ArrayList<>()).add(edge.first());
        }

        for (RopeLink rope : ROPES.values()) {
            rope.bodyKeys().ifPresent(keys -> {
                remaining.add(keys.first());
                remaining.add(keys.second());
                adjacency.computeIfAbsent(keys.first(), ignored -> new ArrayList<>()).add(keys.second());
                adjacency.computeIfAbsent(keys.second(), ignored -> new ArrayList<>()).add(keys.first());
            });
        }

        while (!remaining.isEmpty()) {
            BodyKey start = remaining.iterator().next();
            Set<BodyKey> component = new HashSet<>();
            ArrayDeque<BodyKey> queue = new ArrayDeque<>();
            component.add(start);
            queue.add(start);

            while (!queue.isEmpty()) {
                BodyKey current = queue.removeFirst();
                for (BodyKey next : adjacency.getOrDefault(current, Collections.emptyList())) {
                    if (component.add(next)) {
                        queue.add(next);
                    }
                }
            }

            remaining.removeAll(component);
            if (component.size() > 1) {
                int minimumRuntimeId = component.stream()
                        .mapToInt(BodyKey::runtimeId)
                        .min()
                        .orElse(start.runtimeId());
                Object componentIdentity = new AttachedComponentIdentity(start.dimensionKey(), minimumRuntimeId);
                for (BodyKey key : component) {
                    COMPONENT_BY_BODY.put(key, componentIdentity);
                }
            }
        }

        if (SableScopedLinks.LOGGER.isDebugEnabled()) {
            SableScopedLinks.LOGGER.debug("Rebuilt attached sublevel scope cache: {} components, {} bodies, {} rotary edges, {} rope links",
                    new HashSet<>(COMPONENT_BY_BODY.values()).size(), COMPONENT_BY_BODY.size(), EDGES.size(), ROPES.size());
        }
        dirty = false;
    }

    private static void pruneInvalidEdges() {
        EDGES.removeIf(edge -> !edge.isValid());
    }

    private static void pruneInvalidRopes() {
        ROPES.values().removeIf(rope -> !rope.isValid());
        ROPES_BY_HANDLE.values().removeIf(rope -> !rope.isValid() || !ROPES.containsValue(rope));
    }

    private static Optional<BodyKey> bodyKey(Object body) {
        BodyKey cached = BODY_KEYS.get(body);
        if (cached != null) {
            return Optional.of(cached);
        }

        Optional<Integer> runtimeId = intNoArg(body, "getRuntimeId");
        if (runtimeId.isEmpty()) {
            return Optional.empty();
        }

        Object level = reflectNoArg(body, "getLevel").orElse(null);
        if (level == null) {
            return Optional.empty();
        }

        BodyKey key = new BodyKey(dimensionKey(level), runtimeId.get());
        BODY_KEYS.put(body, key);
        return Optional.of(key);
    }

    private static BodyKey bodyKeyOrNull(Object body) {
        return bodyKey(body).orElse(null);
    }

    private static boolean isValidHandle(Object handle) {
        if (handle == null) {
            return true;
        }

        Optional<Object> valid = reflectNoArg(handle, "isValid");
        return valid.orElse(true) instanceof Boolean value ? value : true;
    }

    private static boolean isRemoved(Object body) {
        Optional<Object> removed = reflectNoArg(body, "isRemoved");
        return removed.orElse(false) instanceof Boolean value && value;
    }

    private static boolean isActive(Object rope) {
        Optional<Object> active = reflectNoArg(rope, "isActive");
        return active.orElse(true) instanceof Boolean value ? value : true;
    }

    private static Optional<Object> subLevelByUuid(Object level, Map<UUID, Object> subLevelCache, Object id) {
        if (!(id instanceof UUID uuid)) {
            return Optional.empty();
        }

        Object cachedSubLevel = subLevelCache.get(uuid);
        if (cachedSubLevel != null) {
            if (isServerSubLevel(cachedSubLevel) && !isRemoved(cachedSubLevel)) {
                return Optional.of(cachedSubLevel);
            }

            subLevelCache.remove(uuid);
        }

        Optional<Object> container = subLevelContainer(level);
        if (container.isEmpty()) {
            return Optional.empty();
        }

        try {
            Optional<Method> cached = CachedReflection.publicMethod(container.get().getClass(), "getSubLevel", UUID.class);
            if (cached.isEmpty()) {
                return Optional.empty();
            }
            Method method = cached.get();
            Object subLevel = method.invoke(container.get(), uuid);
            if (isServerSubLevel(subLevel)) {
                subLevelCache.put(uuid, subLevel);
                return Optional.of(subLevel);
            }
            return Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Map<UUID, Object> subLevelCache(Object level) {
        return SUB_LEVELS_BY_UUID_BY_LEVEL.computeIfAbsent(level, ignored -> new HashMap<>());
    }

    private static Optional<Object> subLevelContainer(Object level) {
        if (level == null) {
            return Optional.empty();
        }

        try {
            Optional<Method> getter = subLevelContainerGetter(level.getClass());
            if (getter.isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable(getter.get().invoke(null, level));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Method> subLevelContainerGetter(Class<?> levelType) {
        return SUB_LEVEL_CONTAINER_GETTERS.computeIfAbsent(levelType, ignored -> {
            Optional<Class<?>> containerType = CachedReflection.loadClass("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            if (containerType.isEmpty()) {
                return Optional.empty();
            }

            for (Method method : containerType.get().getMethods()) {
                if (!method.getName().equals("getContainer") || method.getParameterCount() != 1) {
                    continue;
                }

                Class<?> parameter = method.getParameterTypes()[0];
                if (!parameter.isAssignableFrom(levelType)) {
                    continue;
                }

                method.setAccessible(true);
                return Optional.of(method);
            }

            return Optional.empty();
        });
    }

    private static void syncRopeFields(RopeLink link) {
        Optional<Object> start = reflectField(link.rope, "startAttachmentSubLevel");
        if (start.isPresent() && isServerSubLevel(start.get())) {
            link.startBody = start.get();
        }

        Optional<Object> handle = simulatedRopeConstraint(link.rope).or(() -> reflectField(link.rope, "handle"));
        if (handle.isPresent()) {
            link.handle = handle.get();
        }
    }

    private static void logRopeState(String source, Object attachmentPoint, RopeLink link) {
        if (!SableScopedLinks.LOGGER.isDebugEnabled()) {
            return;
        }

        SableScopedLinks.LOGGER.debug("Tracked {} rope attachment {} start={} end={} handle={}",
                source, attachmentPoint, bodyKey(link.startBody).orElse(null), bodyKey(link.endBody).orElse(null), link.handle);
    }

    private static Optional<Integer> intNoArg(Object target, String methodName) {
        Optional<Object> value = reflectNoArg(target, methodName);
        if (value.orElse(null) instanceof Number number) {
            return Optional.of(number.intValue());
        }

        return Optional.empty();
    }

    private static Object dimensionKey(Object level) {
        Optional<Object> dimension = reflectNoArg(level, "dimension");
        if (dimension.isPresent()) {
            Object key = dimension.get();
            Optional<Object> location = reflectNoArg(key, "location");
            return location.orElse(key);
        }

        return level == null ? "unknown" : level;
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

    private static Optional<Object> reflectField(Object target, String fieldName) {
        return CachedReflection.readDeclaredField(target, fieldName);
    }

    private static Optional<Iterable<?>> simulatedRopeAttachments(Object rope) {
        Optional<Object> attachments = reflectNoArg(rope, "getAttachments");
        if (attachments.orElse(null) instanceof Iterable<?> iterable) {
            return Optional.of(iterable);
        }

        return Optional.empty();
    }

    private static Optional<Object> simulatedRopeConstraint(Object rope) {
        return reflectField(rope, "constraint");
    }

    private record BodyKey(Object dimensionKey, int runtimeId) {
    }

    private record AttachedComponentIdentity(Object dimensionKey, int minimumRuntimeId) {
    }

    private record Edge(BodyKey first, BodyKey second, Object firstBody, Object secondBody, Object handle) {
        Optional<BodyKey> other(BodyKey body) {
            if (first.equals(body)) {
                return Optional.of(second);
            }

            if (second.equals(body)) {
                return Optional.of(first);
            }

            return Optional.empty();
        }

        boolean isValid() {
            return isValidHandle(handle) && !isRemoved(firstBody) && !isRemoved(secondBody);
        }
    }

    private static final class RopeLink {
        private final Object rope;
        private Object handle;
        private Object startBody;
        private Object endBody;

        private RopeLink(Object rope) {
            this.rope = rope;
        }

        Optional<BodyKey> other(BodyKey body) {
            Optional<RopeBodyKeys> keys = bodyKeys();
            if (keys.isEmpty()) {
                return Optional.empty();
            }

            BodyKey start = keys.get().first();
            BodyKey end = keys.get().second();

            if (start.equals(body)) {
                return Optional.of(end);
            }

            if (end.equals(body)) {
                return Optional.of(start);
            }

            return Optional.empty();
        }

        Optional<RopeBodyKeys> bodyKeys() {
            Optional<BodyKey> start = bodyKey(startBody);
            Optional<BodyKey> end = bodyKey(endBody);
            if (start.isEmpty() || end.isEmpty() || !Objects.equals(start.get().dimensionKey(), end.get().dimensionKey())) {
                return Optional.empty();
            }

            return Optional.of(new RopeBodyKeys(start.get(), end.get()));
        }

        boolean isValid() {
            return isActive(rope)
                    && (startBody == null || !isRemoved(startBody))
                    && (endBody == null || !isRemoved(endBody));
        }
    }

    private record RopeBodyKeys(BodyKey first, BodyKey second) {
    }
}
