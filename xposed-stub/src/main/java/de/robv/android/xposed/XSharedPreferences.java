package de.robv.android.xposed;

import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * Xposed API stub（编译占位），运行时由 LSPosed 注入真实实现。
 * LSPosed（xposedminversion >= 93）会自动代理模块私有 prefs 使微信进程可读。
 */
public class XSharedPreferences {

    public XSharedPreferences(String packageName) {
    }

    public XSharedPreferences(String packageName, String prefFileName) {
    }

    public XSharedPreferences(File prefFile) {
    }

    public void reload() {
    }

    public boolean hasFileChanged() {
        return false;
    }

    public boolean getFileExists() {
        return false;
    }

    public String getString(String key, String defValue) {
        return defValue;
    }

    public boolean getBoolean(String key, boolean defValue) {
        return defValue;
    }

    public int getInt(String key, int defValue) {
        return defValue;
    }

    public long getLong(String key, long defValue) {
        return defValue;
    }

    public Map<String, ?> getAll() {
        return null;
    }

    public Set<String> getStringSet(String key, Set<String> defValues) {
        return defValues;
    }
}
