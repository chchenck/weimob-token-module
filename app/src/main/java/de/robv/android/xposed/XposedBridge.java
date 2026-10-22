package de.robv.android.xposed;

/**
 * Xposed API stub（编译占位）。
 * 运行时由 LSPosed/Xposed 框架注入同名真实类，本桩体不会被打包逻辑引用。
 * 方法签名与 rovo89/XposedBridge 原版保持一致，避免运行时链接失败。
 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member hookMethod,
                                                  XC_MethodHook callback) {
        return null;
    }

    public static boolean isXposedEnabled() {
        return true;
    }
}
