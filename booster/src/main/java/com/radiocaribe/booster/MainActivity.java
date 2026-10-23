package com.radiocaribe.booster;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import android.content.SharedPreferences;

public final class MainActivity extends Activity {
    private static final String STORE = "settings";
    private SeekBar slider;
    private TextView amount;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (status != null) {
                status.setText(getSharedPreferences(STORE, MODE_PRIVATE).getString("engine_status", "Iniciando..."));
            }
            handler.postDelayed(this, 1600L);
        }
    };
    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("RadioCaribe Booster");
        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        lay.setBackgroundColor(0xFFF8FAFC);
        lay.setPadding(dp(23),dp(35),dp(23),dp(24));
        TextView head = new TextView(this);
        head.setText("RadioCaribe · Booster digital");
        head.setTextSize(22);
        head.setTextColor(0xFF102441);
        head.setTypeface(null,1);
        lay.addView(head);
        TextView guide = new TextView(this);
        guide.setText("\nGanancia del audio mezclado por Android. No modifica la FM directa, el volumen 25/25 ni el amplificador físico.\n\nEmpieza con +0 dB. La aplicación RadioCaribe ya puede aplicar +6 dB adicionales al streaming; las ganancias pueden acumularse.");
        guide.setTextSize(14);
        guide.setTextColor(0xFF36445B);
        lay.addView(guide);
        amount = new TextView(this);
        amount.setTextSize(34);
        amount.setTextColor(0xFF114C96);
        amount.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1,dp(65));
        ap.topMargin=dp(18);
        lay.addView(amount,ap);
        slider = new SeekBar(this);
        slider.setMax(12); // 0 to +6.0 dB, steps of 0.5 dB
        slider.setProgress(getSharedPreferences(STORE, MODE_PRIVATE).getInt("gain_half",0));
        lay.addView(slider);
        TextView tips = new TextView(this);
        tips.setText("0 dB                                         +6 dB");
        tips.setTextSize(12);
        tips.setTextColor(0xFF63718A);
        lay.addView(tips);
        Button apply = new Button(this);
        apply.setText("APLICAR GANANCIA");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1,dp(54));
        bp.topMargin=dp(20);
        lay.addView(apply,bp);
        Button off = new Button(this);
        off.setText("DESACTIVAR BOOST (+0 dB)");
        lay.addView(off,new LinearLayout.LayoutParams(-1,dp(55)));
        TextView stateLabel = new TextView(this);
        stateLabel.setText("\nEstado del DSP:");
        stateLabel.setTextSize(14);
        stateLabel.setTextColor(0xFF36445B);
        lay.addView(stateLabel);
        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(Color.BLACK);
        lay.addView(status);
        TextView small = new TextView(this);
        small.setText("\nSi aparece «Efecto no disponible», Android no admitió el DSP global; el módulo NO simulará amplificación. Con la FM saturada, no subas la ganancia: debes corregir la señal antes del amplificador.");
        small.setTextColor(0xFF5A6579);
        small.setTextSize(13);
        lay.addView(small);
        setContentView(lay);
        updateAmount();
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int value,boolean fromUser){updateAmount();}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){applyGain(slider.getProgress());}
        });
        apply.setOnClickListener(v -> applyGain(slider.getProgress()));
        off.setOnClickListener(v -> {slider.setProgress(0);applyGain(0);});
        refresh.run();
    }
    private void updateAmount() {amount.setText(String.format(java.util.Locale.US,"+%.1f dB",slider.getProgress()/2.0));}
    private void applyGain(int progress) {
        int safe=Math.max(0,Math.min(12,progress));
        getSharedPreferences(STORE, MODE_PRIVATE).edit().putInt("gain_half",safe).apply();
        Intent i = new Intent(this,BoosterService.class);
        i.putExtra("gain_half",safe);
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i);
        else startService(i);
    }
    @Override public void onDestroy(){handler.removeCallbacks(refresh);super.onDestroy();}
}
