package org.libsdl.app;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import com.realheckerrr.gsilab.QemuRunner;

/** Android surface host for the QEMU SDL display. */
public final class GsiSDLActivity extends SDLActivity {
    public static final int FRAME_UNKNOWN = 0;
    public static final int FRAME_NONBLANK = 1;
    public static final int FRAME_BLANK = 2;
    public static final int FRAME_UNAVAILABLE = 3;

    private static volatile GsiSDLActivity active;
    private static volatile int frameState = FRAME_UNKNOWN;
    private static volatile boolean frameProbeInFlight;
    private static volatile long lastFrameProbeAt;
    private static final Handler FRAME_HANDLER = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (mBrokenLibraries) return;

        // QEMU owns the native event loop; SDLActivity only supplies the Android surface.
        mExternalNativeLoop = true;
        mSurface = new SDLSurface(this);
        RelativeLayout layout = new RelativeLayout(this);
        layout.addView(mSurface, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(layout);
        active = this;
        frameState = FRAME_UNKNOWN;
        frameProbeInFlight = false;
        lastFrameProbeAt = 0L;
    }

    public static boolean isDisplayOpen() {
        return active != null;
    }

    public static boolean isDisplayReady() {
        return active != null && mIsSurfaceReady && mHasFocus;
    }

    /**
     * Requests a small PixelCopy sample from the guest surface. This confirms
     * that SDL/QEMU is producing pixels, not merely that the Activity opened.
     */
    public static void probeFrame() {
        if (Build.VERSION.SDK_INT < 24 || !isDisplayReady() || frameProbeInFlight) {
            if (Build.VERSION.SDK_INT < 24 && active != null) frameState = FRAME_UNAVAILABLE;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastFrameProbeAt < 1500L) return;
        Surface surface = mSurface == null ? null : mSurface.getNativeSurface();
        if (surface == null || !surface.isValid()) return;
        lastFrameProbeAt = now;
        frameProbeInFlight = true;
        Bitmap sample = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        PixelCopy.request(surface, sample, result -> {
            try {
                if (result != PixelCopy.SUCCESS) {
                    frameState = FRAME_UNKNOWN;
                    return;
                }
                int[] pixels = new int[sample.getWidth() * sample.getHeight()];
                sample.getPixels(pixels, 0, sample.getWidth(), 0, 0,
                        sample.getWidth(), sample.getHeight());
                int nonBlack = 0;
                for (int pixel : pixels) {
                    int red = (pixel >>> 16) & 0xff;
                    int green = (pixel >>> 8) & 0xff;
                    int blue = pixel & 0xff;
                    if (red + green + blue > 24) nonBlack++;
                }
                frameState = nonBlack >= pixels.length / 100
                        ? FRAME_NONBLANK : FRAME_BLANK;
            } finally {
                sample.recycle();
                frameProbeInFlight = false;
            }
        }, FRAME_HANDLER);
    }

    public static int getFrameState() {
        return frameState;
    }

    public static void closeDisplay() {
        GsiSDLActivity display = active;
        if (display != null) display.runOnUiThread(display::finish);
    }

    @Override
    protected void onDestroy() {
        if (active == this) active = null;
        frameProbeInFlight = false;
        frameState = FRAME_UNKNOWN;
        super.onDestroy();
    }

    @Override
    public void loadLibraries() {
        // QemuRunner loads SDL2 and its companion libraries before this activity is opened.
        QemuRunner.enginePresent(getApplicationInfo().nativeLibraryDir);
    }
}
