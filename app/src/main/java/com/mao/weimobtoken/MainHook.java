package com.mao.weimobtoken;

import android.app.Application;
import android.content.Context;

import org.json.JSONObject;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_LoadPackage;

/**
 * Xposed 入口：hook 微信小程序网络请求 JNI 层，提取请求头中的 X-WX-Token，
 * 变化时推送到 token_server.py 接收器。
 *
 * hook 点（按微信版本兼容性依次尝试，全部用 hookAllMethods 忽略签名差异）：
 *   1. com.tencent.mm.appbrand.commonjni.AppBrandCommonBindingJni#nativeInvokeHandler
 *   2. com.tencent.mm.appbrand.commonjni.AppBrandJsBridgeBinding#nativeInvokeHandler
 * 请求头 JSON 经过其中某个 String 参数传递，正则兜底提取，不依赖固定 JSON 结构。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "WeimobTokenSniffer";
    private static final String WECHAT_PKG = "com.tencent.mm";
    private static final String MODULE_PKG = "com.mao.weimobtoken";
    private static final String PREF_FILE = "config";
    private static final String PREF_KEY_URL = "server_url";

    /** 匹配 "X-WX-Token":"..."（容忍空格与单引号） */
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("\"X-WX-Token\"\\s*:\\s*\"([0-9a-fA-F]{32,})\"");

    private static final String[] HOOK_CLASSES = {
            "com.tencent.mm.appbrand.commonjni.AppBrandCommonBindingJni",
            "com.tencent.mm.appbrand.commonjni.AppBrandJsBridgeBinding",
    };

    private static volatile String lastToken = null;
    private static volatile boolean hooked = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!WECHAT_PKG.equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(TAG + ": loaded into wechat");

        // 微信小程序运行在独立 classLoader，先 hook Application.attach 拿到宿主 classLoader
        XposedHelpers.findAndHookMethod(Application.class, "attach",
                Context.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (hooked) {
                            return;
                        }
                        hooked = true;
                        ClassLoader cl = ((Application) param.thisObject).getClassLoader();
                        hookToken(cl);
                    }
                });

        // 兜底：部分微信版本主进程 classLoader 可直接找到目标类
        hookToken(lpparam.classLoader);
    }

    private void hookToken(ClassLoader classLoader) {
        for (String className : HOOK_CLASSES) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
                if (clazz == null) {
                    XposedBridge.log(TAG + ": class not found, skip: " + className);
                    continue;
                }
                XposedHelpers.hookAllMethods(clazz, "nativeInvokeHandler", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        scanArgs(param.args);
                    }
                });
                XposedBridge.log(TAG + ": hooked all overloads of " + className
                        + ".nativeInvokeHandler (" + clazz.getDeclaredMethods().length + " methods)");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook failed for " + className + ": " + t);
            }
        }
    }

    /** 扫描方法参数，找包含 X-WX-Token 的 JSON 字符串并提取 */
    private void scanArgs(Object[] args) {
        if (args == null) {
            return;
        }
        for (Object arg : args) {
            if (!(arg instanceof String)) {
                continue;
            }
            String s = (String) arg;
            if (!s.contains("X-WX-Token")) {
                continue;
            }
            String token = extractToken(s);
            if (token != null) {
                onTokenCaptured(token);
                return;
            }
        }
    }

    private String extractToken(String s) {
        // 先按 JSON 结构取，失败则正则兜底
        try {
            JSONObject json = new JSONObject(s);
            JSONObject header = json.optJSONObject("header");
            if (header != null) {
                String token = header.optString("X-WX-Token", "");
                if (!token.isEmpty()) {
                    return token;
                }
            }
        } catch (Throwable ignore) {
        }
        Matcher m = TOKEN_PATTERN.matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private void onTokenCaptured(String token) {
        if (token.equals(lastToken)) {
            return; // token 未变化，不重复推送
        }
        lastToken = token;

        XSharedPreferences prefs = new XSharedPreferences(MODULE_PKG, PREF_FILE);
        prefs.reload();
        String serverUrl = prefs.getString(PREF_KEY_URL, "");
        if (serverUrl.isEmpty()) {
            XposedBridge.log(TAG + ": server url not configured, token: " + mask(token));
            return;
        }
        XposedBridge.log(TAG + ": captured new token " + mask(token) + " -> " + serverUrl);
        pushToken(serverUrl, token);
    }

    private void pushToken(String serverUrl, String token) {
        // 微信主线程，必须异步发送
        Thread t = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject();
                body.put("token", token);
                body.put("time", System.currentTimeMillis());
                body.put("source", "xposed");

                conn = (HttpURLConnection) new URL(serverUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                XposedBridge.log(TAG + ": push result http " + code);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": push failed: " + t);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }, TAG + "-push");
        t.start();
    }

    private String mask(String token) {
        if (token.length() <= 18) {
            return token;
        }
        return token.substring(0, 12) + "..." + token.substring(token.length() - 6);
    }
}
