package com.radiocaribe.booster;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent!=null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())){
            Intent svc=new Intent(context,BoosterService.class);
            if(Build.VERSION.SDK_INT>=26) context.startForegroundService(svc);
            else context.startService(svc);
        }
    }
}
