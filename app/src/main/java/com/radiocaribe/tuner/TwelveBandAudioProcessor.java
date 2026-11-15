package com.radiocaribe.tuner;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Real, device-independent twelve-band stereo PCM equalizer for INTERNET radio.
 * ExoPlayer's decoded 16-bit PCM passes through twelve peaking biquad filters
 * plus the user-defined 0..25 dB pre-output amplifier.
 *
 * Does not process the Moto G3 physical FM audio patch: that hardware routes
 * analog FM audio directly to the wired output, bypassing ExoPlayer PCM.
 */
public final class TwelveBandAudioProcessor extends BaseAudioProcessor {
    public static final int BAND_COUNT = 12;
    public static final int[] FREQUENCIES = {
            32, 64, 125, 250, 500, 1000, 2000, 3000, 4000, 6000, 10000, 16000
    };
    public static final int MIN_BAND_DB = -12;
    public static final int MAX_BAND_DB = 12;
    public static final int MAX_GAIN_DB = 25;

    private static final double Q = 1.15;
    private static final double CLIP_KNEE = 0.92;
    private static final double CLIP_RANGE = 1.0 - CLIP_KNEE;

    private static final class Settings {
        final int[] bands;
        final int gainDb;
        final double gain;
        Settings(int[] values, int amplification) {
            bands = Arrays.copyOf(values, BAND_COUNT);
            for (int i = 0; i < BAND_COUNT; i++) {
                bands[i] = Math.max(MIN_BAND_DB, Math.min(MAX_BAND_DB, bands[i]));
            }
            gainDb = Math.max(0, Math.min(MAX_GAIN_DB, amplification));
            gain = Math.pow(10.0, gainDb / 20.0);
        }
    }

    private volatile Settings requested = new Settings(new int[BAND_COUNT], 10);
    private Settings prepared;
    private double[][] coefficients; // Each biquad: b0,b1,b2,a1,a2
    private double[][] z1;
    private double[][] z2;
    private boolean[] enabledBand;
    private int sampleRate;
    private int channels;
    private int nextChannel;

    /** Safe to call from the UI thread while streaming audio. */
    public void setValues(int[] bands, int amplification) {
        if (bands == null || bands.length != BAND_COUNT) {
            throw new IllegalArgumentException("12 EQ bands required");
        }
        requested = new Settings(bands, amplification);
    }

    @Override
    protected AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat format)
            throws AudioProcessor.UnhandledAudioFormatException {
        if (format.encoding != C.ENCODING_PCM_16BIT || format.channelCount < 1) {
            throw new AudioProcessor.UnhandledAudioFormatException(format);
        }
        sampleRate = format.sampleRate;
        channels = format.channelCount;
        prepared = null;
        resetFilters();
        return format;
    }

    @Override
    protected void onFlush() {
        prepared = null;
        nextChannel = 0;
        resetFilters();
    }

    @Override
    protected void onReset() {
        sampleRate = 0;
        channels = 0;
        nextChannel = 0;
        prepared = null;
        coefficients = null;
        z1 = null;
        z2 = null;
        enabledBand = null;
    }

    private void resetFilters() {
        if (channels < 1) return;
        coefficients = new double[BAND_COUNT][5];
        z1 = new double[BAND_COUNT][channels];
        z2 = new double[BAND_COUNT][channels];
        enabledBand = new boolean[BAND_COUNT];
    }

    private void prepare(Settings values) {
        if (sampleRate <= 0 || channels <= 0) return;
        if (coefficients == null) resetFilters();
        // Only rebuild on UI changes. Filter state persists for uninterrupted audio
        // and is reset when a band's parameters change, avoiding old coefficients.
        for (int band = 0; band < BAND_COUNT; band++) {
            Arrays.fill(z1[band], 0d);
            Arrays.fill(z2[band], 0d);
            int db = values.bands[band];
            double center = Math.min(FREQUENCIES[band], sampleRate * 0.45);
            if (db == 0 || center < 10 || center >= sampleRate / 2.0) {
                enabledBand[band] = false;
                continue;
            }
            double w0 = 2.0 * Math.PI * center / sampleRate;
            double cosine = Math.cos(w0);
            double alpha = Math.sin(w0) / (2.0 * Q);
            double A = Math.pow(10.0, db / 40.0);
            double a0 = 1.0 + alpha / A;
            double[] b = coefficients[band];
            b[0] = (1.0 + alpha * A) / a0;
            b[1] = (-2.0 * cosine) / a0;
            b[2] = (1.0 - alpha * A) / a0;
            b[3] = (-2.0 * cosine) / a0;
            b[4] = (1.0 - alpha / A) / a0;
            enabledBand[band] = true;
        }
        prepared = values;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int bytes = inputBuffer.remaining();
        ByteBuffer outputBuffer = replaceOutputBuffer(bytes);
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN);
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN);
        Settings values = requested;
        if (prepared != values) prepare(values);

        while (inputBuffer.remaining() >= 2) {
            short original = inputBuffer.getShort();
            int ch = nextChannel;
            if (++nextChannel == channels) nextChannel = 0;

            double x = original / 32768.0;
            boolean processed = false;
            for (int band = 0; band < BAND_COUNT; band++) {
                if (!enabledBand[band]) continue;
                processed = true;
                double[] b = coefficients[band];
                double y = b[0] * x + z1[band][ch];
                z1[band][ch] = b[1] * x - b[3] * y + z2[band][ch];
                z2[band][ch] = b[2] * x - b[4] * y;
                // Protect speaker/output from unstable numerical values.
                x = Double.isFinite(y) ? y : 0.0;
            }

            if (!processed && values.gainDb == 0) {
                outputBuffer.putShort(original); // Exact bit-for-bit bypass.
                continue;
            }
            double amplified = x * values.gain;
            // Gentle saturation above 92% prevents abrupt digital overflow.
            // At extreme gain levels compressed/clipped sound is unavoidable.
            double abs = Math.abs(amplified);
            if (abs > CLIP_KNEE) {
                amplified = Math.copySign(
                        CLIP_KNEE + CLIP_RANGE * Math.tanh((abs - CLIP_KNEE) / CLIP_RANGE),
                        amplified);
            }
            amplified = Math.max(-1.0, Math.min(1.0, amplified));
            outputBuffer.putShort((short) Math.round(amplified * 32767.0));
        }
        outputBuffer.flip();
    }
}
