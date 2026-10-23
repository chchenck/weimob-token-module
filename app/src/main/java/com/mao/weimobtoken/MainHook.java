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
import de.robv.android.xposed.callbacks.XC_LoadPackage;

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
    private static final java.util.Set<String> seenClasses =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!WECHAT_PKG.equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(TAG + ": loaded into wechat, process=" + lpparam.processName);

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
                        hookClassLoaderDiscovery(cl);
                    }
                });

        // 兜底：部分微信版本主进程 classLoader 可直接找到目标类
        hookToken(lpparam.classLoader);
        hookClassLoaderDiscovery(lpparam.classLoader);
    }

    private void hookToken(ClassLoader classLoader) {
        for (String className : HOOK_CLASSES) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
                if (clazz == null) {
                    XposedBridge.log(TAG + ": class not found, skip: " + className);
                    continue;
                }
                hookAllNativeMethods(clazz, className);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook failed for " + className + ": " + t);
            }
        }
    }

    /**
     * 自适应发现：微信混淆类名随版本变化，固定类名会失效。
     * 监控 loadClass，凡小程序相关包下含 Jni/Binding/JsBridge 的类加载即 hook
     * 其全部 native 方法，不依赖具体方法名与类名。
     */
    private void hookClassLoaderDiscovery(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (!(result instanceof Class)) {
                                return;
                            }
                            String name = ((Class<?>) result).getName();
                            if (!name.startsWith("com.tencent.mm")
                                    || seenClasses.contains(name)) {
                                return;
                            }
                            seenClasses.add(name);
                            boolean interesting = name.contains("appbrand")
                                    && (name.contains("Jni") || name.contains("Binding")
                                        || name.contains("JsBridge"));
                            if (!interesting) {
                                return;
                            }
                            XposedBridge.log(TAG + ": discovered candidate class: " + name);
                            try {
                                hookAllNativeMethods((Class<?>) result, name);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": hook discovered failed: " + name
                                        + " " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": classloader discovery armed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": classloader discovery setup failed: " + t);
        }
    }

    /** hook 类中全部 native 方法（名字不限），参数交 scanArgs 扫描 */
    private void hookAllNativeMethods(Class<?> clazz, String label) {
        java.lang.reflect.Method[] methods = clazz.getDeclaredMethods();
        int hookedCount = 0;
        for (java.lang.reflect.Method m : methods) {
            int mod = m.getModifiers();
            if (!java.lang.reflect.Modifier.isNative(mod)) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        scanArgs(param.args);
                    }
                });
                hookedCount++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook native failed " + label + "#"
                        + m.getName() + ": " + t);
            }
        }
        XposedBridge.log(TAG + ": hooked " + hookedCount + "/" + methods.length
                + " native methods of " + label);
    }

    /** 扫描方法参数，找包含 X-WX-Token 的 JSON 字符串并提取 token/wid/cuid */
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
                onTokenCaptured(token, extractWid(s), extractCuid(s));
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

    /** 从请求体 customInfo.wid 提取用户 wid，失败返回空串 */
    private String extractWid(String s) {
        try {
            JSONObject json = new JSONObject(s);
            JSONObject custom = json.optJSONObject("customInfo");
            if (custom != null) {
                String wid = custom.optString("wid", "");
                if (!wid.isEmpty()) {
                    return wid;
                }
            }
        } catch (Throwable ignore) {
        }
        Matcher m = java.util.regex.Pattern
                .compile("\"wid\"\\s*:\\s*\"?(\\d{6,})\"?").matcher(s);
        return m.find() ? m.group(1) : "";
    }

    /** 从请求串 rprm_cuid=xxx 提取设备 cuid，失败返回空串 */
    private String extractCuid(String s) {
        Matcher m = java.util.regex.Pattern
                .compile("rprm_cuid=([0-9a-zA-Z]+)").matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private void onTokenCaptured(String token, String wid, String cuid) {
        if (token.equals(lastToken)) {
            return; // token 未变化，不重复推送
        }
        lastToken = token;

        // 首选：UDP 广播直推（微信进程禁止明文 HTTP，UDP 无此限制且无需配置）
        if (pushByUdp(token, wid, cuid)) {
            XposedBridge.log(TAG + ": captured token " + mask(token)
                    + " wid=" + wid + " -> pushed via UDP broadcast");
            return;
        }

        // 兜底：HTTP 推送（需模块 App 配置过接收器地址）
        String serverUrl = "";
        XSharedPreferences prefs = new XSharedPreferences(MODULE_PKG, PREF_FILE);
        prefs.reload();
        serverUrl = prefs.getString(PREF_KEY_URL, "");
        if (serverUrl.isEmpty()) {
            serverUrl = discoverServerUrl();
        }
        if (serverUrl.isEmpty()) {
            XposedBridge.log(TAG + ": no server url (pref + discovery failed), token: "
                    + mask(token));
            return;
        }
        XposedBridge.log(TAG + ": captured new token " + mask(token)
                + " wid=" + wid + " -> " + serverUrl);
        pushToken(serverUrl, token, wid, cuid);
    }

    private static final int UDP_PORT = 9876;

    /** UDP 广播 token JSON 到接收器（fire-and-forget），发送成功即认为已推送 */
    private boolean pushByUdp(String token, String wid, String cuid) {
        java.net.DatagramSocket socket = null;
        try {
            JSONObject body = new JSONObject();
            body.put("token", token);
            if (!wid.isEmpty()) {
                body.put("wid", wid);
            }
            if (!cuid.isEmpty()) {
                body.put("cuid", cuid);
            }
            body.put("time", System.currentTimeMillis());
            body.put("source", "xposed-udp");
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);

            socket = new java.net.DatagramSocket();
            socket.setBroadcast(true);
            socket.send(new java.net.DatagramPacket(data, data.length,
                    java.net.InetAddress.getByName("255.255.255.255"), UDP_PORT));
            XposedBridge.log(TAG + ": token pushed via UDP broadcast ("
                    + data.length + " bytes)");
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": UDP push failed: " + t);
            return false;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private static volatile String cachedServerUrl = null;

    /** 广播 WEIMOB_DISCOVER 发现接收器，返回 http://ip:port/token；失败返回空串 */
    private String discoverServerUrl() {
        if (cachedServerUrl != null) {
            return cachedServerUrl;
        }
        java.net.DatagramSocket socket = null;
        try {
            socket = new java.net.DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(3000);
            byte[] magic = "WEIMOB_DISCOVER".getBytes(StandardCharsets.UTF_8);
            socket.send(new java.net.DatagramPacket(magic, magic.length,
                    java.net.InetAddress.getByName("255.255.255.255"), 9876));

            byte[] buf = new byte[512];
            long deadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    java.net.DatagramPacket packet = new java.net.DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    JSONObject json = new JSONObject(
                            new String(packet.getData(), 0, packet.getLength(),
                                    StandardCharsets.UTF_8));
                    if ("weimob-token-receiver".equals(json.optString("service"))) {
                        int port = json.optInt("port", 9876);
                        cachedServerUrl = "http://" + packet.getAddress().getHostAddress()
                                + ":" + port + "/token";
                        XposedBridge.log(TAG + ": discovered server " + cachedServerUrl);
                        return cachedServerUrl;
                    }
                } catch (java.net.SocketTimeoutException e) {
                    break;
                } catch (Throwable ignore) {
                    // 非 JSON 应答，继续等
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": discovery failed: " + t);
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
        return "";
    }

    private void pushToken(String serverUrl, String token, String wid, String cuid) {
        // 微信主线程，必须异步发送
        Thread t = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject();
                body.put("token", token);
                if (!wid.isEmpty()) {
                    body.put("wid", wid);
                }
                if (!cuid.isEmpty()) {
                    body.put("cuid", cuid);
                }
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
            } catch (Throwable ex) {
                XposedBridge.log(TAG + ": push failed: " + ex);
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
