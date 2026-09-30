package org.libsdl.app;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import com.realheckerrr.gsilab.QemuRunner;

/** Android surface host for the QEMU SDL display. */
public final class GsiSDLActivity extends SDLActivity {
    private static volatile GsiSDLActivity active;

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
    }

    public static boolean isDisplayOpen() {
        return active != null;
    }

    public static boolean isDisplayReady() {
        return active != null && mIsSurfaceReady && mHasFocus;
    }

    public static void closeDisplay() {
        GsiSDLActivity display = active;
        if (display != null) display.runOnUiThread(display::finish);
    }

    @Override
    protected void onDestroy() {
        if (active == this) active = null;
        super.onDestroy();
    }

    @Override
    public void loadLibraries() {
        // QemuRunner loads SDL2 and its companion libraries before this activity is opened.
        QemuRunner.enginePresent(getApplicationInfo().nativeLibraryDir);
    }
}
