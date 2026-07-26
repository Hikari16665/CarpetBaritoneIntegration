package baritone.api.utils;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

public final class TypeUtils {
    private TypeUtils() { }
    public static Class<?> resolveBaseClass(Type type) {
        return type instanceof Class<?> cls ? cls
                : type instanceof ParameterizedType parameterized
                ? (Class<?>) parameterized.getRawType() : null;
    }
}
