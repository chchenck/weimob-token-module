package de.robv.android.xposed;

/**
 * Xposed API stub（编译占位），运行时由 LSPosed 注入真实实现。
 */
public interface IXposedHookLoadPackage {

    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
