package com.radiocaribe.tuner;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Event-driven kiosk suspend. Does not poll the USB, change CPU governors or
 * modify kernel settings. Disabled by default until the owner opts in via ADB.
 *
 * adb shell settings put global radiocaribe_ultra_sleep 1
 * adb shell settings put global radiocaribe_ultra_sleep 0
 */
final class UltraSleep {
    private static final String TAG = "RadioCaribeSleep";
    static final String SETTING = "radiocaribe_ultra_sleep";
    static final String KEEP_WIFI = "radiocaribe_sleep_keep_wifi";
    private static final String PREFS = "radiocaribe_ultra_sleep_state";
    private static final String KEY_SLEEPING = "sleeping";
    private static final String KEY_WIFI = "restore_wifi";
    private static final String KEY_BT = "restore_bluetooth";

    private UltraSleep() {}

    static boolean enabled(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), SETTING, 0) == 1; }
        catch (Exception error) { Log.w(TAG, "Cannot read ultra-sleep toggle", error); return false; }
    }

    // Fast resume option: leave Wi-Fi associated while screen and media
    // sleep. Costs more battery than disabling Wi-Fi fully. Off by default.
    static boolean keepWifi(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), KEEP_WIFI, 0) == 1; }
        catch (Exception error) { return false; }
    }

    static boolean powered(Context context) {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        // Fail safe if battery service is not yet available: do not enter sleep.
        if (battery == null) return true;
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    static boolean sleeping(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SLEEPING, false);
    }

    static void enter(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_SLEEPING, false) || powered(context)) return;

        boolean wifiOn = false;
        boolean bluetoothOn = false;
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        try { wifiOn = wifi != null && wifi.isWifiEnabled(); }
        catch (Exception error) { Log.w(TAG, "Cannot read Wi-Fi state", error); }

        BluetoothAdapter bluetooth = null;
        try { bluetooth = BluetoothAdapter.getDefaultAdapter();
            bluetoothOn = bluetooth != null && bluetooth.isEnabled();
        } catch (Exception error) { Log.w(TAG, "Cannot read Bluetooth state", error); }

        // Durable state FIRST: when charging comes back, the manifest receiver
        // can restore radios even after Android killed the activity.
        if (!prefs.edit().putBoolean(KEY_SLEEPING, true)
                .putBoolean(KEY_WIFI, wifiOn).putBoolean(KEY_BT, bluetoothOn).commit()) {
            Log.e(TAG, "State not persisted: refusing to disable radios");
            return;
        }
        boolean wifiKeptOn = keepWifi(context);
        try { if (wifiOn && !wifiKeptOn && wifi != null) wifi.setWifiEnabled(false); }
        catch (Exception error) { Log.w(TAG, "Cannot switch Wi-Fi off", error); }
        try { if (bluetoothOn && bluetooth != null) bluetooth.disable(); }
        catch (Exception error) { Log.w(TAG, "Cannot switch Bluetooth off", error); }
        Log.i(TAG, "Ultra-sleep entered: wifi=" + wifiOn + " keptOn=" + wifiKeptOn
                + " bluetooth=" + bluetoothOn);
    }

    static void restore(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_SLEEPING, false)) return;
        boolean wifiOn = prefs.getBoolean(KEY_WIFI, false);
        boolean bluetoothOn = prefs.getBoolean(KEY_BT, false);
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);

        // Keep saved state until Wi-Fi restore has been requested, so a later
        // charging event can retry if the framework was temporarily unavailable.
        boolean restoredWifi = !wifiOn;
        try {
            if (wifiOn && wifi != null) {
                restoredWifi = wifi.isWifiEnabled() || wifi.setWifiEnabled(true);
            }
        }
        catch (Exception error) { Log.w(TAG, "Cannot restore Wi-Fi", error); }

        try {
            BluetoothAdapter bluetooth = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothOn && bluetooth != null) bluetooth.enable();
        } catch (Exception error) { Log.w(TAG, "Cannot restore Bluetooth", error); }
        if (restoredWifi) {
            prefs.edit().putBoolean(KEY_SLEEPING, false).apply();
        }
        Log.i(TAG, "USB wake: Wi-Fi requested=" + wifiOn + " success=" + restoredWifi
                + " Bluetooth requested=" + bluetoothOn);
    }

    @SuppressWarnings("deprecation")
    static void wakeScreen(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock lock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "RadioCaribe:UsbWake");
                lock.acquire(2000L); // Timeout ensures no permanent wakelock.
            }
        } catch (Exception error) { Log.w(TAG, "Cannot wake screen", error); }
    }

    static void sleepScreenAsync(Context context) {
        new Thread(() -> {
            // A short root command; no permanent root background service.
            try {
                if (powered(context)) return; // Avoid racing a reconnect.
                Process process = new ProcessBuilder("su", "-c",
                        "/system/bin/input keyevent 223").start();
                int status = process.waitFor();
                Log.i(TAG, "Screen-off root key result=" + status);
            } catch (Exception error) {
                Log.w(TAG, "Screen-off key unavailable; standard screen timeout remains", error);
            }
        }, "RadioCaribe-ScreenOff").start();
    }
}
