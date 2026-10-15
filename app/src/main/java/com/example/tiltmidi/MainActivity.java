package com.example.tiltmidi;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Trusted local UI, with the existing BLE and RTP transports kept intact. */
public class MainActivity extends Activity implements SensorEventListener {
    private static final String APP_URL = "https://glissway.local/index.html";
    private static final int BLUETOOTH_PERMISSION_REQUEST = 9;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean[] bentChannels = new boolean[17];
    private final float[] rotationMatrix = new float[9];
    private final float[] screenMatrix = new float[9];
    private final float[] orientation = new float[3];
    private SensorManager sensors;
    private Sensor rotation;
    private BleMidi ble;
    private RtpMidi rtp;
    private WebView web;
    private FrameLayout content;
    private boolean pageReady;
    private boolean foreground;
    private boolean destroyed;
    private boolean sensorRegistered;
    private boolean changingTransport;
    private int lifecycleVersion;
    private int finishedPauseVersion = -1;
    private int transportVersion;
    private float roll;
    private float pitch;
    private String connectionStatus = "未启动 MIDI";
    private String lastConnectionPayload = "";
    private String legacyConfig = "null";

    private final Runnable connectionPoll = new Runnable() {
        @Override public void run() {
            if (!foreground || destroyed) return;
            publishConnection(false);
            main.postDelayed(this, 100);
        }
    };

    private final BroadcastReceiver wallpaperChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { publishTheme(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        legacyConfig = readLegacyConfig();
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotation = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotation == null) rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        ble = new BleMidi(this, this::onTransportStatus);
        rtp = new RtpMidi(this::onTransportStatus);
        buildWebView();
        IntentFilter wallpaperFilter = new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(wallpaperChanged, wallpaperFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(wallpaperChanged, wallpaperFilter);
    }

    private void buildWebView() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        content = new FrameLayout(this);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.addJavascriptInterface(new MidiBridge(), "GlisswayMidi");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (APP_URL.equals(url)) view.evaluateJavascript(
                        "document.documentElement.classList.add('inset-native')", null);
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String asset = assetFor(uri);
                if (asset != null) {
                    try {
                        WebResourceResponse response = new WebResourceResponse(
                                asset.endsWith(".js") ? "text/javascript" : "text/html", "UTF-8",
                                getAssets().open(asset));
                        response.setResponseHeaders(Collections.singletonMap("Cache-Control", "no-store"));
                        return response;
                    } catch (Exception ignored) { }
                }
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                        Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !"index.html".equals(assetFor(request.getUrl()));
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (!APP_URL.equals(url)) return;
                view.evaluateJavascript("document.documentElement.classList.add('inset-native');"
                        + "window.dispatchEvent(new Event('resize'));", null);
                pageReady = true;
                publishTheme();
                publishConnection(true);
                publishPose();
                callUi(foreground ? "resume" : "suspend", "");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                if (isFinishing()) { result.cancel(); return true; }
                new AlertDialog.Builder(MainActivity.this).setTitle("Glissway").setMessage(message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                        .setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }

            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                if (isFinishing()) { result.cancel(); return true; }
                new AlertDialog.Builder(MainActivity.this).setTitle("Glissway").setMessage(message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                        .setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }

            @Override public boolean onJsPrompt(WebView view, String url, String message, String initial, JsPromptResult result) {
                if (isFinishing()) { result.cancel(); return true; }
                EditText input = new EditText(MainActivity.this);
                input.setSingleLine();
                input.setText(initial);
                new AlertDialog.Builder(MainActivity.this).setTitle("Glissway").setMessage(message).setView(input)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm(input.getText().toString()))
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                        .setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
        });
        content.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(content);
        content.requestApplyInsets();
        web.loadUrl(APP_URL);
    }

    private static String assetFor(Uri uri) {
        if (!"https".equals(uri.getScheme()) || !"glissway.local".equals(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
        if ("/index.html".equals(uri.getPath())) return "index.html";
        if ("/glissway-presets.js".equals(uri.getPath())) return "glissway-presets.js";
        return null;
    }

    private void callUi(String method, String argument) {
        if (pageReady && !destroyed) web.evaluateJavascript(
                "window.Glissway&&window.Glissway." + method + "&&window.Glissway." + method + "(" + argument + ")", null);
    }

    private void onTransportStatus(String message) {
        main.post(() -> {
            if (destroyed) return;
            connectionStatus = message;
            publishConnection(true);
        });
    }

    private void publishConnection(boolean force) {
        if (!pageReady || destroyed) return;
        try {
            boolean bleReady = ble.connected();
            boolean rtpReady = rtp.connected();
            JSONObject state = new JSONObject();
            state.put("connected", bleReady || rtpReady);
            state.put("bleConnected", bleReady);
            state.put("rtpConnected", rtpReady);
            state.put("label", bleReady && rtpReady ? "BLE MIDI + RTP-MIDI" : rtpReady ? "RTP-MIDI" : bleReady ? "BLE MIDI" : "MIDI");
            state.put("status", connectionStatus);
            String payload = state.toString();
            if (force || !lastConnectionPayload.equals(payload)) {
                lastConnectionPayload = payload;
                callUi("setConnection", payload);
            }
        } catch (Exception ignored) { }
    }

    private void publishPose() {
        if (rotation == null) return;
        callUi("setPose", String.format(Locale.US, "{roll:%.5f,pitch:%.5f}", roll, pitch));
    }

    private boolean hasBluetoothPermissions() {
        return Build.VERSION.SDK_INT < 31 || (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED);
    }

    private void startBle() {
        if (!hasBluetoothPermissions()) {
            requestPermissions(new String[] { Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE }, BLUETOOTH_PERMISSION_REQUEST);
            return;
        }
        switchTransport(() -> {
            connectionStatus = "正在启动 BLE MIDI…";
            ble.start();
            publishConnection(true);
        });
    }

    /** Let the existing RTP worker drain a final center message before disconnecting. */
    private void switchTransport(Runnable next) {
        int operation = ++transportVersion;
        changingTransport = true;
        resetBends();
        boolean drainRtp = rtp.connected();
        Runnable finish = () -> {
            if (destroyed || operation != transportVersion) return;
            ble.stop();
            rtp.disconnect();
            changingTransport = false;
            for (int channel = 1; channel <= 16; channel++) bentChannels[channel] = false;
            if (next != null && foreground) next.run();
            else { connectionStatus = "MIDI 已停止"; publishConnection(true); }
        };
        if (drainRtp) main.postDelayed(finish, 60);
        else finish.run();
    }

    private void resetBends() {
        for (int channel = 1; channel <= 16; channel++) {
            if (!bentChannels[channel]) continue;
            if (rtp.connected()) rtp.sendPitchBend(channel, 8192);
            if (ble.connected()) ble.sendPitchBend(channel, 8192);
            bentChannels[channel] = false;
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != BLUETOOTH_PERMISSION_REQUEST) return;
        if (hasBluetoothPermissions()) startBle();
        else { connectionStatus = "请授予蓝牙连接和广播权限"; publishConnection(true); }
    }

    private final class MidiBridge {
        @JavascriptInterface public boolean sendCC(int channel, int cc, int value) {
            if (channel < 1 || channel > 16 || cc < 0 || cc > 127 || value < 0 || value > 127) return false;
            return onMain(() -> {
                if (changingTransport || destroyed) return false;
                boolean available = false, accepted = true;
                if (ble.connected()) { available = true; accepted &= ble.send(channel, cc, value); }
                if (rtp.connected()) { available = true; accepted &= rtp.send(channel, cc, value); }
                return available && accepted;
            });
        }

        @JavascriptInterface public boolean sendPitchBend(int channel, int value) {
            if (channel < 1 || channel > 16 || value < 0 || value > 16383) return false;
            return onMain(() -> {
                if (changingTransport || destroyed) return false;
                boolean available = false, accepted = true, anyAccepted = false;
                if (ble.connected()) {
                    available = true;
                    boolean sent = ble.sendPitchBend(channel, value);
                    accepted &= sent;
                    anyAccepted |= sent;
                }
                if (rtp.connected()) {
                    available = true;
                    boolean sent = rtp.sendPitchBend(channel, value);
                    accepted &= sent;
                    anyAccepted |= sent;
                }
                // A partial delivery can still leave a receiver bent. Keep its reset pending.
                if (value != 8192 && anyAccepted) bentChannels[channel] = true;
                else if (value == 8192 && available && accepted) bentChannels[channel] = false;
                return available && accepted;
            });
        }

        @JavascriptInterface public void startBle() { main.post(() -> { if (foreground) MainActivity.this.startBle(); }); }
        @JavascriptInterface public void stopMidi() { main.post(() -> switchTransport(null)); }
        @JavascriptInterface public void connectRtp(String host, int port) {
            if (host == null || host.trim().isEmpty() || host.length() > 253 || port < 1 || port > 65534) {
                main.post(() -> { connectionStatus = "请填写有效 IP 或主机名和端口（1–65534）"; publishConnection(true); });
                return;
            }
            main.post(() -> {
                if (!foreground) return;
                switchTransport(() -> {
                    connectionStatus = "RTP-MIDI 正在握手…";
                    rtp.connect(host.trim(), port);
                    publishConnection(true);
                });
            });
        }
        @JavascriptInterface public void requestState() { main.post(() -> { publishTheme(); publishConnection(true); publishPose(); }); }
        @JavascriptInterface public boolean enableSensor() {
            main.post(() -> {
                registerSensor();
                if (rotation == null) {
                    connectionStatus = "此设备没有姿态传感器，可使用 Edit 手动推子";
                    publishConnection(true);
                }
            });
            return rotation != null;
        }
        @JavascriptInterface public String getLegacyConfig() { return legacyConfig; }
    }

    private boolean onMain(Callable<Boolean> operation) {
        if (destroyed) return false;
        FutureTask<Boolean> task = null;
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) return operation.call();
            task = new FutureTask<>(operation);
            main.post(task);
            return task.get(1200, TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            if (task != null) task.cancel(false);
            onTransportStatus("MIDI 发送失败：" + exception.getClass().getSimpleName());
            return false;
        }
    }

    /** One-time import is committed by the UI only after local preset storage succeeds. */
    private String readLegacyConfig() {
        SharedPreferences preferences = getSharedPreferences("config", MODE_PRIVATE);
        String raw = preferences.getString("maps", null);
        if (raw == null) return "null";
        try {
            JSONArray previous = new JSONArray(raw);
            JSONArray imported = new JSONArray();
            for (int index = 0; index < Math.min(32, previous.length()); index++) {
                JSONObject old = previous.getJSONObject(index);
                JSONObject mapping = new JSONObject();
                mapping.put("name", old.optString("name", "Controller"));
                mapping.put("output", "cc");
                mapping.put("ch", old.optInt("channel", 1));
                mapping.put("cc", old.optInt("cc", 1));
                mapping.put("lo", old.optInt("low", 0));
                mapping.put("hi", old.optInt("high", 127));
                mapping.put("axis", old.optInt("axis", 0));
                mapping.put("manual", old.optInt("manual", 64));
                mapping.put("v", old.optInt("manual", 64));
                mapping.put("range", old.optDouble("range", 64));
                mapping.put("on", old.optBoolean("enabled", true));
                mapping.put("inv", old.optBoolean("invert", false));
                mapping.put("sensitivity", 1);
                imported.put(mapping);
            }
            JSONObject result = new JSONObject();
            result.put("maps", imported);
            result.put("selX", 0);
            result.put("selY", imported.length() > 1 ? 1 : 0);
            return result.toString();
        } catch (Exception ignored) { return "null"; }
    }

    private int tone(String family, int tone, int fallback) {
        if (Build.VERSION.SDK_INT < 31) return fallback;
        int id = getResources().getIdentifier("system_" + family + "_" + tone, "color", "android");
        return id == 0 ? fallback : getColor(id);
    }

    private static String hex(int value) { return String.format(Locale.US, "#%06x", value & 0xffffff); }

    private JSONObject palette(boolean dark) throws Exception {
        JSONObject colors = new JSONObject();
        colors.put("background", hex(tone("neutral1", dark ? 900 : 10, dark ? 0xff141719 : 0xfff8fafb)));
        colors.put("surface", hex(tone("neutral1", dark ? 800 : 50, dark ? 0xff252a2d : 0xffedf1f4)));
        colors.put("bubble", hex(tone("accent2", dark ? 500 : 400, dark ? 0xff657b80 : 0xff899da2)));
        colors.put("onSurface", hex(tone("neutral1", dark ? 50 : 900, dark ? 0xffedf1f4 : 0xff1c2225)));
        colors.put("onSurfaceVariant", hex(tone("neutral2", dark ? 200 : 700, dark ? 0xffbcc8ce : 0xff46535a)));
        colors.put("onBubble", dark ? "#ffffff" : "#ffffff");
        colors.put("onBubbleVariant", dark ? "#ffffff" : "#152124");
        colors.put("primary", hex(tone("accent1", dark ? 200 : 600, dark ? 0xffb1cbd3 : 0xff486873)));
        colors.put("onPrimary", hex(tone("accent1", dark ? 800 : 0, dark ? 0xff18333c : 0xffffffff)));
        colors.put("secondary", hex(tone("accent2", dark ? 200 : 600, dark ? 0xffb9cad0 : 0xff526970)));
        colors.put("primaryContainer", hex(tone("accent1", dark ? 700 : 100, dark ? 0xff304e58 : 0xffd4e7ee)));
        colors.put("onPrimaryContainer", hex(tone("accent1", dark ? 100 : 900, dark ? 0xffd4e7ee : 0xff102d37)));
        colors.put("outline", hex(tone("neutral2", dark ? 400 : 500, dark ? 0xff8c9ca5 : 0xff71838e)));
        return colors;
    }

    private void publishTheme() {
        if (destroyed || web == null) return;
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int background = tone("neutral1", dark ? 900 : 10, dark ? 0xff141719 : 0xfff8fafb);
        content.setBackgroundColor(background);
        web.setBackgroundColor(background);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            getWindow().getInsetsController().setSystemBarsAppearance(dark ? 0
                            : android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("light", palette(false));
            payload.put("dark", palette(true));
            callUi("applyTheme", payload.toString());
        } catch (Exception ignored) { }
    }

    private void registerSensor() {
        if (!foreground || sensorRegistered || rotation == null) return;
        sensorRegistered = sensors.registerListener(this, rotation, 10000);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!foreground || destroyed) return;
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        int x = SensorManager.AXIS_X, y = SensorManager.AXIS_Y;
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: x = SensorManager.AXIS_Y; y = SensorManager.AXIS_MINUS_X; break;
            case Surface.ROTATION_180: x = SensorManager.AXIS_MINUS_X; y = SensorManager.AXIS_MINUS_Y; break;
            case Surface.ROTATION_270: x = SensorManager.AXIS_MINUS_Y; y = SensorManager.AXIS_X; break;
            default: break;
        }
        SensorManager.remapCoordinateSystem(rotationMatrix, x, y, screenMatrix);
        SensorManager.getOrientation(screenMatrix, orientation);
        pitch = (float) Math.toDegrees(orientation[1]);
        roll = (float) Math.toDegrees(orientation[2]);
        publishPose();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        publishTheme();
        content.requestApplyInsets();
    }

    @Override public void onResume() {
        super.onResume();
        foreground = true;
        ++lifecycleVersion;
        registerSensor();
        main.removeCallbacks(connectionPoll);
        main.post(connectionPoll);
        if (web != null) web.onResume();
        publishTheme();
        callUi("resume", "");
    }

    @Override public void onPause() {
        foreground = false;
        int version = ++lifecycleVersion;
        main.removeCallbacks(connectionPoll);
        sensors.unregisterListener(this);
        sensorRegistered = false;
        Runnable finishPause = () -> {
            if (version != lifecycleVersion || version == finishedPauseVersion || foreground || destroyed) return;
            finishedPauseVersion = version;
            switchTransport(null);
            if (web != null) web.onPause();
        };
        if (pageReady) {
            web.evaluateJavascript("window.Glissway&&window.Glissway.suspend&&window.Glissway.suspend()",
                    result -> finishPause.run());
            main.postDelayed(finishPause, 250);
        } else finishPause.run();
        super.onPause();
    }

    @Override public void onDestroy() {
        boolean drainOutput = ble.connected() || rtp.connected();
        resetBends();
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        // Destruction can overtake onPause's asynchronous UI flush. Give the existing
        // RTP worker and Bluetooth stack time to dispatch their final center message.
        Runnable closeTransports = () -> { ble.stop(); rtp.disconnect(); };
        if (drainOutput) main.postDelayed(closeTransports, 60);
        else closeTransports.run();
        try { unregisterReceiver(wallpaperChanged); } catch (Exception ignored) { }
        if (web != null) { web.removeJavascriptInterface("GlisswayMidi"); web.destroy(); }
        super.onDestroy();
    }
}
