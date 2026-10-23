package com.radiocaribe.tuner;

import com.android.fmradio.FmNative;

/** Explicit root-only FM hardware probe, invoked by the owner from ADB. */
public final class FmRootProbe {
    public static void main(String[] args) {
        final String base = "/data/local/tmp/radiocaribe-fm/";
        boolean opened = false;
        boolean powered = false;
        try {
            System.out.println("UID=" + android.os.Process.myUid());
            System.load(base + "libc++.so");
            System.load(base + "libfmjni.so");
            System.out.println("JNI_LIBRARIES_OK");
            opened = FmNative.openDev();
            System.out.println("FM_OPEN=" + opened);
            if (!opened) return;
            FmNative.switchAntenna(0);
            powered = FmNative.powerUp(103.4f);
            System.out.println("FM_POWERUP_103_4=" + powered);
            if (powered) {
                try { FmNative.setMute(false); } catch (Throwable ignored) {}
                Thread.sleep(8000);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            try { if (powered) FmNative.powerDown(0); } catch (Throwable ignored) {}
            try { if (opened) FmNative.closeDev(); } catch (Throwable ignored) {}
            System.out.println("FM_PROBE_DONE");
        }
    }
}
