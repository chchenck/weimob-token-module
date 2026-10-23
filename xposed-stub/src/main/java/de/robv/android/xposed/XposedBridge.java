package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Xposed API stub（编译占位），运行时由 LSPosed 注入真实实现。
 * 包含本模块用到的静态方法 + API 类所引用的内部类型，签名与 rovo89/XposedBridge 原版一致。
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

    public static void unhookMethod(java.lang.reflect.Member hookMethod,
                                    XC_MethodHook callback) {
    }

    public static void unhookLoadPackage(
            de.robv.android.xposed.callbacks.XC_LoadPackage callback) {
    }

    /** 官方 API 类（XCallback.Param 等）引用此集合类型，签名须与原版一致 */
    public static final class CopyOnWriteSortedSet<T> {
        private volatile Object[] snapshot = new Object[0];

        public Object[] getSnapshot() {
            return snapshot;
        }

        @SuppressWarnings("unchecked")
        public synchronized boolean add(T t) {
            for (Object o : snapshot) {
                if (o == t) {
                    return false;
                }
            }
            Object[] next = new Object[snapshot.length + 1];
            System.arraycopy(snapshot, 0, next, 0, snapshot.length);
            next[snapshot.length] = t;
            snapshot = next;
            return true;
        }

        @SuppressWarnings("unchecked")
        public synchronized boolean remove(T t) {
            for (int i = 0; i < snapshot.length; i++) {
                if (snapshot[i] == t) {
                    Object[] next = new Object[snapshot.length - 1];
                    System.arraycopy(snapshot, 0, next, 0, i);
                    System.arraycopy(snapshot, i + 1, next, i, snapshot.length - i - 1);
                    snapshot = next;
                    return true;
                }
            }
            return false;
        }

        public void clear() {
            snapshot = new Object[0];
        }
    }
}
