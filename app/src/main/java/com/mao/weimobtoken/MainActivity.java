package com.mao.weimobtoken;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Scanner;

public class MainActivity extends Activity {

    private static final String PREF_FILE = "config";
    private static final String PREF_KEY_URL = "server_url";
    private static final int DISCOVER_PORT = 9876;
    private static final byte[] DISCOVER_MAGIC = "WEIMOB_DISCOVER".getBytes(StandardCharsets.UTF_8);

    private EditText urlInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.url_input);
        Button saveBtn = findViewById(R.id.save_btn);
        Button testBtn = findViewById(R.id.test_btn);
        Button discoverBtn = findViewById(R.id.discover_btn);
        TextView usage = findViewById(R.id.usage_text);

        SharedPreferences prefs = getSharedPreferences(PREF_FILE, MODE_PRIVATE);
        urlInput.setText(prefs.getString(PREF_KEY_URL, ""));
        usage.setText(R.string.usage);

        saveBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Toast.makeText(this, "地址必须以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString(PREF_KEY_URL, url).apply();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        });

        testBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "请先填写接收器地址", Toast.LENGTH_SHORT).show();
                return;
            }
            testBtn.setEnabled(false);
            testBtn.setText("发送中...");
            new Thread(() -> {
                String result = sendTest(url);
                runOnUiThread(() -> {
                    testBtn.setEnabled(true);
                    testBtn.setText(R.string.test);
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                });
            }).start();
        });

        // 局域网 UDP 广播自动发现接收器，免去手动填写电脑 IP
        discoverBtn.setOnClickListener(v -> {
            discoverBtn.setEnabled(false);
            discoverBtn.setText("搜索中...");
            new Thread(() -> {
                final String found = discoverServer();
                runOnUiThread(() -> {
                    discoverBtn.setEnabled(true);
                    discoverBtn.setText(R.string.discover);
                    if (found == null) {
                        Toast.makeText(this, "未发现接收器：确认电脑已开机运行 token_server 且手机与其同一网络",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    // 保留旧地址里的 query（如 ?wid=3150705087）
                    String old = urlInput.getText().toString().trim();
                    int q = old.indexOf('?');
                    String newUrl = q >= 0 ? found + old.substring(q) : found;
                    urlInput.setText(newUrl);
                    Toast.makeText(this, "已发现接收器：" + newUrl
                            + (q < 0 ? "\n请补上 ?wid=你的wid" : ""), Toast.LENGTH_LONG).show();
                });
            }).start();
        });
    }

    /** 广播 WEIMOB_DISCOVER，返回首个应答的 http://ip:port/token，超时返回 null */
    private String discoverServer() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(3000);

            // 目标广播地址：全网广播 + 本机所在子网广播
            java.util.List<InetAddress> targets = new java.util.ArrayList<>();
            try {
                targets.add(InetAddress.getByName("255.255.255.255"));
            } catch (Exception ignore) {
            }
            String myIp = getLocalIp();
            if (myIp != null) {
                int lastDot = myIp.lastIndexOf('.');
                if (lastDot > 0) {
                    try {
                        targets.add(InetAddress.getByName(
                                myIp.substring(0, lastDot + 1) + "255"));
                    } catch (Exception ignore) {
                    }
                }
            }

            for (InetAddress addr : targets) {
                socket.send(new DatagramPacket(DISCOVER_MAGIC, DISCOVER_MAGIC.length,
                        addr, DISCOVER_PORT));
            }

            byte[] buf = new byte[512];
            long deadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    JSONObject json = new JSONObject(
                            new String(packet.getData(), 0, packet.getLength(),
                                    StandardCharsets.UTF_8));
                    if ("weimob-token-receiver".equals(json.optString("service"))) {
                        int port = json.optInt("port", DISCOVER_PORT);
                        return "http://" + packet.getAddress().getHostAddress()
                                + ":" + port + "/token";
                    }
                } catch (SocketTimeoutException e) {
                    break;
                } catch (Exception ignore) {
                    // 非 JSON 应答，继续等
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private String getLocalIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private String sendTest(String urlStr) {
        HttpURLConnection conn = null;
        try {
            // 只测连通性（GET /ping），绝不 POST 假 token 污染服务端 config
            java.net.URI uri = new java.net.URI(urlStr);
            String base = uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            conn = (HttpURLConnection) new URL(base + "/ping").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            String resp = readStream(conn);
            return "HTTP " + code + (resp.isEmpty() ? "" : "\n" + resp);
        } catch (Exception e) {
            return "失败: " + e.getMessage();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String readStream(HttpURLConnection conn) {
        try (Scanner sc = new Scanner(conn.getInputStream(), "UTF-8").useDelimiter("\\A")) {
            return sc.hasNext() ? sc.next() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
