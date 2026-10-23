package com.radiocaribe.booster;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.media.audiofx.LoudnessEnhancer;
import android.util.Log;
import java.util.Locale;

public final class BoosterService extends Service {
    private static final String STORE="settings";
    private static final String CHANNEL="rcbooster_dsp";
    private static final int NID=601;
    private LoudnessEnhancer effect;

    @Override public void onCreate(){
        super.onCreate();
        showNotification("Iniciando DSP");
    }
    private void showNotification(String title){
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch=new NotificationChannel(CHANNEL,"RadioCaribe DSP",NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(ch);
        }
        Intent launch=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,launch,PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        Notification n=builder.setContentTitle("RadioCaribe Booster")
            .setContentText(title).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi).setOngoing(true).build();
        startForeground(NID,n);
    }
    private synchronized void setGain(int half){
        half=Math.max(0,Math.min(12,half));
        getSharedPreferences(STORE,MODE_PRIVATE).edit().putInt("gain_half",half).apply();
        String label=String.format(Locale.US,"+%.1f dB",half/2.0);
        try{
            if(effect==null) effect=new LoudnessEnhancer(0);
            effect.setTargetGain(half*50);
            effect.setEnabled(half>0);
            String state = (half==0 ? "DSP en espera (+0 dB)" : "DSP habilitado "+label+" (sesión global)");
            getSharedPreferences(STORE,MODE_PRIVATE).edit().putString("engine_status",state).apply();
            showNotification(state);
            Log.i("RadioCaribeBooster",state);
        }catch(Throwable err){
            String state="Efecto no disponible: "+err.getClass().getSimpleName()+" "+String.valueOf(err.getMessage());
            getSharedPreferences(STORE,MODE_PRIVATE).edit().putString("engine_status",state).apply();
            showNotification("DSP no disponible; ganancia sin aplicar");
            Log.e("RadioCaribeBooster",state,err);
            if(effect!=null){try{effect.release();}catch(Exception ignored){}effect=null;}
        }
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        int gain=intent!=null && intent.hasExtra("gain_half")?intent.getIntExtra("gain_half",0):
            getSharedPreferences(STORE,MODE_PRIVATE).getInt("gain_half",0);
        setGain(gain);
        return START_STICKY;
    }
    @Override public void onDestroy(){
        if(effect!=null){try{effect.setEnabled(false);effect.release();}catch(Exception ignored){}effect=null;}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
