package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

/**
 * Xposed API stub（编译占位），运行时由 LSPosed 注入真实实现。
 * 仅包含本模块用到的静态方法，签名与 rovo89/XposedBridge 原版完全一致
 * （JVM 方法解析含返回类型，返回类型不一致会导致 NoSuchMethodError）。
 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        return null;
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className,
                                                         ClassLoader classLoader,
                                                         String methodName,
                                                         Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz,
                                                         String methodName,
                                                         Object... parameterTypesAndCallback) {
        return null;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> clazz,
                                                           String methodName,
                                                           XC_MethodHook callback) {
        return null;
    }
}
