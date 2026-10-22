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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class MainActivity extends Activity {

    private static final String PREF_FILE = "config";
    private static final String PREF_KEY_URL = "server_url";

    private EditText urlInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.url_input);
        Button saveBtn = findViewById(R.id.save_btn);
        Button testBtn = findViewById(R.id.test_btn);
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
    }

    private String sendTest(String urlStr) {
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("token", "test-from-module");
            body.put("time", System.currentTimeMillis());
            body.put("source", "test");

            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
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
