package com.radiocaribe.tuner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Arrays;

/**
 * In-app landscape mixer. Custom vertical faders dispatch real, live changes
 * into TwelveBandAudioProcessor; Android's platform EQ band count is NOT used.
 */
final class EqualizerDialog {
    interface Listener { void changed(int[] bands, int gainDb); }

    private static final int ACCENT = Color.rgb(18, 139, 255);
    private static final int DARK_BG = Color.rgb(5, 13, 25);
    private static final int LIGHT_BG = Color.rgb(250, 251, 253);
    private static final int DARK_FG = Color.WHITE;
    private static final int LIGHT_FG = Color.rgb(8, 18, 34);

    private EqualizerDialog() {}

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static TextView text(Activity a, String text, int sizeSp, int color, boolean bold) {
        TextView v = new TextView(a);
        v.setText(text);
        v.setTextSize(sizeSp);
        v.setTextColor(color);
        v.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return v;
    }

    static void show(Activity a, boolean darkMode, boolean selectedFm, int[] stored, int gain,
            Listener changed) {
        final int[] bands = Arrays.copyOf(stored, TwelveBandAudioProcessor.BAND_COUNT);
        final int fg = darkMode ? DARK_FG : LIGHT_FG;
        final int secondary = darkMode ? Color.rgb(172, 188, 207) : Color.rgb(87, 102, 118);
        final int bg = darkMode ? DARK_BG : LIGHT_BG;

        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 8));
        panel.setBackgroundColor(bg);
        scroll.addView(panel);

        TextView title = text(a, "ECUALIZADOR  ·  12 BANDAS", 18, fg, true);
        panel.addView(title);

        TextView info = text(a, selectedFm
                ? "La FM física usa una ruta de hardware independiente. Estos ajustes se aplican a emisoras ONLINE."
                : "Ajustes en vivo para las emisoras ONLINE · Procesamiento digital real.", 11, secondary, false);
        info.setPadding(0, dp(a, 3), 0, dp(a, 7));
        panel.addView(info);

        LinearLayout gainHeader = new LinearLayout(a);
        gainHeader.setOrientation(LinearLayout.HORIZONTAL);
        gainHeader.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(gainHeader);
        TextView gainLabel = text(a, "AMPLIFICADOR", 12, fg, true);
        gainHeader.addView(gainLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView gainValue = text(a, "+" + gain + " dB", 17, ACCENT, true);
        gainHeader.addView(gainValue);

        SeekBar gainBar = new SeekBar(a);
        gainBar.setMax(25);
        gainBar.setProgress(Math.max(0, Math.min(25, gain)));
        gainBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        gainBar.setThumbTintList(ColorStateList.valueOf(ACCENT));
        panel.addView(gainBar, new LinearLayout.LayoutParams(-1, dp(a, 32)));

        TextView warning = text(a, "0 a +25 dB · Los niveles altos pueden causar distorsión o dañar altavoces.", 10, secondary, false);
        warning.setPadding(0, 0, 0, dp(a, 3));
        panel.addView(warning);

        HorizontalScrollView rail = new HorizontalScrollView(a);
        rail.setHorizontalScrollBarEnabled(false);
        rail.setFillViewport(true);
        LinearLayout bandRow = new LinearLayout(a);
        bandRow.setOrientation(LinearLayout.HORIZONTAL);
        bandRow.setGravity(Gravity.CENTER);
        rail.addView(bandRow, new HorizontalScrollView.LayoutParams(-2, -2));
        panel.addView(rail);

        final VerticalFader[] faders = new VerticalFader[bands.length];
        final int[] currentGain = {gainBar.getProgress()};
        gainBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar v, int progress, boolean fromUser) {
                currentGain[0] = progress;
                gainValue.setText("+" + progress + " dB");
                changed.changed(bands, progress);
            }
            @Override public void onStartTrackingTouch(SeekBar v) {}
            @Override public void onStopTrackingTouch(SeekBar v) {}
        });

        for (int i = 0; i < bands.length; i++) {
            final int index = i;
            VerticalFader fader = new VerticalFader(a, darkMode, i, bands[i], value -> {
                bands[index] = value;
                changed.changed(bands, currentGain[0]);
            });
            faders[i] = fader;
            bandRow.addView(fader, new LinearLayout.LayoutParams(dp(a, 43), dp(a, 170)));
        }

        LinearLayout footer = new LinearLayout(a);
        footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        footer.setPadding(0, dp(a, 4), 0, 0);
        panel.addView(footer);
        TextView legend = text(a, "Bandas de −12 a +12 dB", 11, secondary, false);
        footer.addView(legend, new LinearLayout.LayoutParams(0, -2, 1));
        TextView reset = text(a, "RESTABLECER", 12, ACCENT, true);
        reset.setPadding(dp(a, 10), dp(a, 8), dp(a, 8), dp(a, 8));
        reset.setClickable(true);
        reset.setContentDescription("Restablecer ecualizador a plano y amplificador a +10 decibelios");
        footer.addView(reset);
        reset.setOnClickListener(v -> {
            Arrays.fill(bands, 0);
            for (VerticalFader f : faders) f.setValue(0);
            gainBar.setProgress(10);
            changed.changed(bands, 10);
        });

        AlertDialog dialog = new AlertDialog.Builder(a)
                .setView(scroll)
                .setPositiveButton("CERRAR", (d, w) -> {})
                .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            int width = Math.min(a.getResources().getDisplayMetrics().widthPixels
                    - dp(a, 16), dp(a, 720));
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private interface BandListener { void changed(int value); }

    /** Draw/touch mapping is direct; rotated Android SeekBars may miss touches. */
    private static final class VerticalFader extends View {
        private final Activity activity;
        private final int band;
        private final boolean dark;
        private final BandListener listener;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int value;

        VerticalFader(Activity a, boolean dark, int band, int initial, BandListener listener) {
            super(a);
            this.activity = a;
            this.dark = dark;
            this.band = band;
            this.listener = listener;
            setValue(initial);
        }

        void setValue(int v) {
            value = Math.max(-12, Math.min(12, v));
            setContentDescription(TwelveBandAudioProcessor.FREQUENCIES[band]
                    + " hercios, " + value + " decibelios");
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float top = dp(activity, 28);
            float bottom = getHeight() - dp(activity, 28);
            float middle = (top + bottom) / 2;
            float marker = bottom - (value + 12f) / 24f * (bottom - top);
            int neutral = dark ? Color.rgb(168, 183, 201) : Color.rgb(104, 123, 143);

            p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(activity, 11));
            p.setColor(value == 0 ? neutral : ACCENT);
            canvas.drawText((value > 0 ? "+" : "") + value, cx, dp(activity, 16), p);

            p.setColor(dark ? Color.rgb(56, 73, 96) : Color.rgb(214, 225, 235));
            p.setStrokeWidth(dp(activity, 5));
            p.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(cx, top, cx, bottom, p);

            p.setColor(ACCENT);
            p.setStrokeWidth(dp(activity, 4));
            canvas.drawLine(cx, middle, cx, marker, p);

            p.setColor(neutral);
            p.setStrokeWidth(dp(activity, 1));
            canvas.drawLine(cx - dp(activity, 9), middle,
                    cx + dp(activity, 9), middle, p);

            p.setColor(ACCENT);
            canvas.drawRoundRect(cx - dp(activity, 10), marker - dp(activity, 5),
                    cx + dp(activity, 10), marker + dp(activity, 5),
                    dp(activity, 2), dp(activity, 2), p);

            int hz = TwelveBandAudioProcessor.FREQUENCIES[band];
            p.setColor(dark ? DARK_FG : LIGHT_FG);
            p.setTextSize(dp(activity, 10));
            canvas.drawText(hz >= 1000 ? (hz / 1000) + "k" : Integer.toString(hz),
                    cx, getHeight() - dp(activity, 8), p);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    // Fall through to update position on first press.
                case MotionEvent.ACTION_MOVE:
                    float top = dp(activity, 28);
                    float bottom = getHeight() - dp(activity, 28);
                    int next = Math.round((bottom - event.getY()) / (bottom - top) * 24f) - 12;
                    next = Math.max(-12, Math.min(12, next));
                    if (value != next) {
                        setValue(next);
                        listener.changed(next);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
