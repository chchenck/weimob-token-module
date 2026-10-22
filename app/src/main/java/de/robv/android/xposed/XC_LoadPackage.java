package de.robv.android.xposed;

import android.content.pm.ApplicationInfo;

/**
 * Xposed API stub（编译占位），运行时由 LSPosed 注入真实实现。
 */
public abstract class XC_LoadPackage extends XC_MethodHook {

    public XC_LoadPackage() {
    }

    public XC_LoadPackage(int priority) {
    }

    @Override
    protected abstract void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;

    public static class LoadPackageParam {

        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;
    }
}
