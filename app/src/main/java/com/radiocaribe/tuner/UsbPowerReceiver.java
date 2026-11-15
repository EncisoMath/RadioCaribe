package com.radiocaribe.tuner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * System-powered wake path, including when RadioCaribe's Activity was killed.
 * System broadcast requires no periodic polling and can wake a suspended CPU.
 */
public final class UsbPowerReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) return;
        if (!UltraSleep.enabled(context)) return;
        Log.i("RadioCaribeSleep", "Power connected (manifest receiver)");
        UltraSleep.restore(context);
        UltraSleep.wakeScreen(context);
        try {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(launch);
        } catch (Exception error) {
            Log.e("RadioCaribeSleep", "Could not open RadioCaribe on USB power", error);
        }
    }
}
