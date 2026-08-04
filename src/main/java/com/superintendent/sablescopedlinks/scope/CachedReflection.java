package com.superintendent.sablescopedlinks.scope;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class CachedReflection {
    private static final ConcurrentMap<ClassKey, Optional<Class<?>>> CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ExactMethodKey, Optional<Method>> PUBLIC_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<NamedMethodKey, Optional<Method>> NAMED_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<FieldKey, Optional<Field>> PUBLIC_FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<FieldKey, Optional<Field>> DECLARED_FIELDS = new ConcurrentHashMap<>();

    private CachedReflection() {
    }

    public static Optional<Class<?>> loadClass(String className) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return CLASSES.computeIfAbsent(new ClassKey(className, loader), key -> {
            try {
                return Optional.of(Class.forName(key.name(), false, key.loader()));
            } catch (ClassNotFoundException | LinkageError ignored) {
                return Optional.empty();
            }
        });
    }

    public static Optional<Method> publicMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        return PUBLIC_METHODS.computeIfAbsent(new ExactMethodKey(type, name, List.of(parameterTypes)), key -> {
            try {
                Method method = key.type().getMethod(key.name(), key.parameterTypes().toArray(Class<?>[]::new));
                makeAccessible(method);
                return Optional.of(method);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return Optional.empty();
            }
        });
    }

    public static Optional<Method> noArgMethod(Class<?> type, String name) {
        return publicMethod(type, name);
    }

    public static Optional<Method> namedMethod(Class<?> type, String name, int parameterCount) {
        return NAMED_METHODS.computeIfAbsent(new NamedMethodKey(type, name, parameterCount), key -> {
            for (Method method : key.type().getDeclaredMethods()) {
                if (method.getName().equals(key.name()) && method.getParameterCount() == key.parameterCount()) {
                    makeAccessible(method);
                    return Optional.of(method);
                }
            }

            for (Method method : key.type().getMethods()) {
                if (method.getName().equals(key.name()) && method.getParameterCount() == key.parameterCount()) {
                    makeAccessible(method);
                    return Optional.of(method);
                }
            }

            return Optional.empty();
        });
    }

    public static Optional<Field> publicField(Class<?> type, String name) {
        return PUBLIC_FIELDS.computeIfAbsent(new FieldKey(type, name), key -> {
            try {
                Field field = key.type().getField(key.name());
                makeAccessible(field);
                return Optional.of(field);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return Optional.empty();
            }
        });
    }

    public static Optional<Field> declaredField(Class<?> type, String name) {
        return DECLARED_FIELDS.computeIfAbsent(new FieldKey(type, name), key -> {
            Class<?> current = key.type();
            while (current != null) {
                try {
                    Field field = current.getDeclaredField(key.name());
                    makeAccessible(field);
                    return Optional.of(field);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    current = current.getSuperclass();
                }
            }

            return Optional.empty();
        });
    }

    public static Optional<Object> invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return Optional.empty();
        }

        Optional<Method> method = noArgMethod(target.getClass(), methodName);
        if (method.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(method.get().invoke(target));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public static Optional<Object> readDeclaredField(Object target, String fieldName) {
        if (target == null) {
            return Optional.empty();
        }

        Optional<Field> field = declaredField(target.getClass(), fieldName);
        if (field.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(field.get().get(target));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static void makeAccessible(Field field) {
        try {
            field.setAccessible(true);
        } catch (RuntimeException ignored) {
        }
    }

    private static void makeAccessible(Method method) {
        try {
            method.setAccessible(true);
        } catch (RuntimeException ignored) {
        }
    }

    private record ClassKey(String name, ClassLoader loader) {
    }

    private record ExactMethodKey(Class<?> type, String name, List<Class<?>> parameterTypes) {
    }

    private record NamedMethodKey(Class<?> type, String name, int parameterCount) {
    }

    private record FieldKey(Class<?> type, String name) {
    }
}
