package com.realheckerrr.gsilab;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textview.MaterialTextView;

import java.io.File;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_INPUT = 1001;
    private static final int EXPORT_REPORT = 1002;
    private static final int PICK_GUEST = 1003;
    private static final int GUEST_DISPLAY = 1004;
    private static final long GUEST_DISPLAY_WAIT_MS = 12000L;
    private static final long BOOT_MARKER_TIMEOUT_MS = 180000L;
    private static final String DEFAULT_RANCHU_URL =
            "https://dl.google.com/android/repository/sys-img/google_apis/arm64-v8a-35_r08.zip";
    private static final String DEFAULT_RANCHU_SHA256 =
            "dd0ed92f34600bd9edc6ec2f28d49bb6a12370edf36acc50b88425f73cbbcc6f";
    private static final int BACKGROUND = Color.rgb(11, 15, 20);
    private static final int SURFACE = Color.rgb(21, 28, 36);
    private static final int SURFACE_VARIANT = Color.rgb(38, 51, 61);
    private static final int TEXT = Color.rgb(213, 225, 231);
    private static final int MUTED = Color.rgb(174, 187, 197);
    private static final int ACCENT = Color.rgb(119, 214, 200);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private File selectedFile;
    private File guestFile;
    private GsiAnalysis analysis;
    private GuestBundleAnalysis guestAnalysis;
    private TextView selectedText;
    private TextView guestText;
    private TextView reportText;
    private TextView logText;
    private MaterialButton analyzeButton;
    private MaterialButton bootButton;
    private MaterialButton exportButton;
    private MaterialButton guestDownloadButton;
    private QemuBootSession session;
    private Boolean lastQemuRunning;
    private boolean launchInProgress;
    private boolean bootMarkerSeen;
    private long bootStartedAt;
    private int coldRetryCount;
    private int lastDisplayFrameState = -1;
    private final Runnable consolePoller = new Runnable() {
        @Override
        public void run() {
            if (session == null || analysis == null || guestAnalysis == null) return;
            boolean running = session.isRunning();
            if (lastQemuRunning == null || lastQemuRunning != running) {
                appendLog(running ? "QEMU process is running." : "QEMU exited; inspect the serial log for the boot failure.");
                lastQemuRunning = running;
            }
            String console = session.readConsole();
            if (session.hasAndroidBootMarker() || hasAndroidBootMarker(console)) {
                bootMarkerSeen = true;
            }
            org.libsdl.app.GsiSDLActivity.probeFrame();
            int displayFrameState = org.libsdl.app.GsiSDLActivity.getFrameState();
            if (displayFrameState != lastDisplayFrameState) {
                lastDisplayFrameState = displayFrameState;
                if (displayFrameState != org.libsdl.app.GsiSDLActivity.FRAME_UNKNOWN) {
                    appendLog("Guest display frame: " + displayFrameDescription(displayFrameState) + ".");
                }
            }
            reportText.setText(analysis.render() + "\n" + guestAnalysis.render()
                    + "\n\nQEMU STATE\n  process: " + (running ? "running" : "exited")
                    + "\n  android marker: " + (bootMarkerSeen ? "observed" : "waiting")
                    + "\n  display frame: " + displayFrameDescription(displayFrameState)
                    + "\n\nQEMU CONSOLE\n" + console);
            boolean markerTimedOut = bootStartedAt > 0
                    && System.currentTimeMillis() - bootStartedAt >= BOOT_MARKER_TIMEOUT_MS;
            if (!bootMarkerSeen && !launchInProgress && coldRetryCount == 0
                    && ((!running && bootStartedAt > 0) || (running && markerTimedOut))) {
                retryStalledVm();
                return;
            }
            if (!running && !launchInProgress) {
                org.libsdl.app.GsiSDLActivity.closeDisplay();
                bootButton.setText("Start VM");
                refreshBootButton();
            }
            mainHandler.postDelayed(this, 1000L);
        }
    };

    private static boolean hasAndroidBootMarker(String console) {
        if (console == null) return false;
        String lower = console.toLowerCase(Locale.US);
        return lower.contains("sys.boot_completed")
                || lower.contains("boot animation stopped")
                || lower.contains("starting service .zygote")
                || lower.contains("android runtime started")
                || (lower.contains("class_start main") && lower.contains("succeeded"));
    }

    private static String displayFrameDescription(int state) {
        switch (state) {
            case org.libsdl.app.GsiSDLActivity.FRAME_NONBLANK:
                return "non-black pixels observed";
            case org.libsdl.app.GsiSDLActivity.FRAME_BLANK:
                return "surface is blank";
            case org.libsdl.app.GsiSDLActivity.FRAME_UNAVAILABLE:
                return "PixelCopy unavailable on this Android version";
            default:
                return "waiting for a sample";
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        setContentView(buildView());
        appendLog("h3cknn's GSI tester " + BuildConfig.VERSION_NAME + " ready.");
        appendLog("Import a GSI and a compatible guest bundle to prepare a VM launch.");
        if (BuildConfig.DEBUG && getIntent().getBooleanExtra("ci_private_stage", false)) {
            prepareCiPrivateStage(
                    getIntent().getStringExtra("ci_gsi_name"),
                    getIntent().getStringExtra("ci_guest_name"));
        }
    }

    private View buildView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(10), dp(18), dp(24));
        scroll.addView(page);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("h3cknn's GSI tester");
        toolbar.setSubtitle("GSI test lab");
        toolbar.setTitleTextColor(TEXT);
        toolbar.setSubtitleTextColor(MUTED);
        toolbar.setBackgroundTintList(ColorStateList.valueOf(BACKGROUND));
        toolbar.setElevation(dp(2));
        page.addView(toolbar, wrapParams(0));

        TextView subtitle = text("Inspect generic system images before you risk a flash.", 15, MUTED);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        page.addView(subtitle);

        LinearLayout imageSection = cardSection(page, "1  GSI IMAGE", "Choose a raw/compressed image, ZIP, or 7z archive containing a GSI.");
        selectedText = text("No image selected", 14, TEXT);
        selectedText.setPadding(0, dp(12), 0, dp(4));
        imageSection.addView(selectedText);

        MaterialButton selectButton = outlinedButton("Select GSI image, ZIP, or 7z");
        selectButton.setOnClickListener(view -> chooseInput());
        imageSection.addView(selectButton);

        analyzeButton = button("Analyze image");
        analyzeButton.setEnabled(false);
        analyzeButton.setOnClickListener(view -> analyzeInput());
        imageSection.addView(analyzeButton);

        LinearLayout guestSection = cardSection(page, "2  BASE GUEST BUNDLE", "Use the official ARM64 Ranchu emulator ZIP for the broad-GSI test guest.");
        guestText = text("No guest bundle selected", 14, TEXT);
        guestText.setPadding(0, dp(12), 0, dp(4));
        guestSection.addView(guestText);

        MaterialButton guestButton = outlinedButton("Select guest bundle ZIP");
        guestButton.setOnClickListener(view -> chooseGuestBundle());
        guestSection.addView(guestButton);

        guestDownloadButton = outlinedButton("Download official Ranchu guest");
        guestDownloadButton.setOnClickListener(view -> downloadDefaultGuest());
        guestSection.addView(guestDownloadButton);

        LinearLayout reportSection = cardSection(page, "3  PREFLIGHT REPORT", "Headers, compression, dynamic partitions, and boot assets are checked before launch.");
        reportText = console("Nothing analyzed yet.");
        reportSection.addView(reportText);

        exportButton = outlinedButton("Export report");
        exportButton.setEnabled(false);
        exportButton.setOnClickListener(view -> exportReport());
        reportSection.addView(exportButton);

        LinearLayout backendSection = cardSection(page, "4  VM BACKEND", "A transparent ARM64 QEMU launch path for testing, not a promise that arbitrary hardware images will boot.");
        TextView backendNote = text(
                "Recommended: arm64-v8a-35_r08.zip. It supplies kernel-ranchu, ramdisk.img, vendor.img.gz, userdata.img, and the QEMU guest contract. The Ranchu profile has reached Android 17's main boot action with the official ARM64 GSI. The app adapts its fstab for the selected GSI and opens the guest display. Pixel factory images remain device-specific rather than universal VM bases.",
                14, MUTED);
        backendNote.setPadding(0, dp(12), 0, dp(4));
        backendSection.addView(backendNote);

        bootButton = button("Start VM");
        bootButton.setEnabled(false);
        bootButton.setOnClickListener(view -> {
            if (session == null) {
                probeRuntime();
            } else if (session.isRunning()) {
                stopTestVm();
            } else {
                restartTestVm();
            }
        });
        backendSection.addView(bootButton);

        LinearLayout logSection = cardSection(page, "ACTIVITY LOG", "The latest import, analysis, and QEMU events appear here.");
        logText = console("");
        logText.setTextSize(12);
        logSection.addView(logText);
        return scroll;
    }

    private LinearLayout cardSection(LinearLayout parent, String title, String description) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(ColorStateList.valueOf(SURFACE));
        card.setStrokeColor(ColorStateList.valueOf(Color.rgb(52, 69, 80)));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(18));
        card.setUseCompatPadding(true);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, dp(8), 0, dp(8));
        card.setLayoutParams(cardParams);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(8));
        card.addView(content);
        MaterialTextView heading = text(title, 13, ACCENT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(heading);
        content.addView(text(description, 13, MUTED));
        parent.addView(card);
        return content;
    }

    private MaterialTextView text(String value, int size, int color) {
        MaterialTextView view = new MaterialTextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private MaterialTextView console(String value) {
        MaterialTextView view = text(value, 13, TEXT);
        view.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        view.setTextIsSelectable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(SURFACE_VARIANT);
        background.setCornerRadius(dp(12));
        view.setBackground(background);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setLayoutParams(wrapParams(8));
        return view;
    }

    private MaterialButton button(String label) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(BACKGROUND);
        button.setTextSize(14);
        button.setMinHeight(dp(48));
        button.setCornerRadius(dp(14));
        button.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        button.setLayoutParams(wrapParams(8));
        return button;
    }

    private MaterialButton outlinedButton(String label) {
        MaterialButton button = button(label);
        button.setTextColor(ACCENT);
        button.setBackgroundTintList(ColorStateList.valueOf(SURFACE));
        button.setStrokeColor(ColorStateList.valueOf(ACCENT));
        button.setStrokeWidth(dp(1));
        return button;
    }

    private LinearLayout.LayoutParams wrapParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, topMargin, 0, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void chooseInput() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_INPUT);
    }

    private void chooseGuestBundle() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        startActivityForResult(intent, PICK_GUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == GUEST_DISPLAY) {
            // Returning from the guest display means the user closed the VM
            // window. Clean up the native QEMU thread before re-enabling Start VM.
            if (session != null) stopTestVm();
            else {
                launchInProgress = false;
                bootButton.setText("Start VM");
                refreshBootButton();
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == PICK_INPUT) importInput(data.getData());
        if (requestCode == EXPORT_REPORT) writeExport(data.getData());
        if (requestCode == PICK_GUEST) importGuestBundle(data.getData());
    }

    private void importInput(Uri uri) {
        final String displayName = displayName(uri);
        selectedText.setText("Copying: " + displayName);
        analysis = null;
        analyzeButton.setEnabled(false);
        bootButton.setEnabled(false);
        exportButton.setEnabled(false);
        appendLog("Import requested: " + displayName);
        worker.execute(() -> {
            try {
                File dir = new File(getFilesDir(), "imports");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create app import directory.");
                File destination = new File(dir, System.currentTimeMillis() + "_" + safeName(displayName));
                try (InputStream input = getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(destination)) {
                    if (input == null) throw new IOException("Android did not return a readable stream.");
                    byte[] buffer = new byte[1024 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                }
                selectedFile = destination;
                mainHandler.post(() -> {
                    selectedText.setText("Selected: " + displayName + "\nStored privately in app storage");
                    analyzeButton.setEnabled(true);
                    refreshBootButton();
                    appendLog("Import complete: " + formatBytes(destination.length()));
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    selectedText.setText("Import failed: " + error.getMessage());
                    appendLog("ERROR: " + error.getMessage());
                });
            }
        });
    }

    /**
     * Debug-only bridge for emulator smoke tests whose Android 35 shell cannot
     * write the emulated /sdcard.  The workflow copies the assets into this
     * debuggable app's private files/ci-inbox directory with run-as, and this
     * method feeds them through the same private import/analyzer path used by
     * the document picker.
     */
    private void prepareCiPrivateStage(String gsiName, String guestName) {
        if (gsiName == null || guestName == null) return;
        worker.execute(() -> {
            try {
                File inbox = new File(getFilesDir(), "ci-inbox");
                File gsiSource = new File(inbox, safeName(gsiName));
                File guestSource = new File(inbox, safeName(guestName));
                if (!gsiSource.isFile() || !guestSource.isFile()) {
                    throw new IOException("CI-staged assets are missing from private app storage.");
                }
                File imports = new File(getFilesDir(), "imports");
                File guests = new File(getFilesDir(), "guest-bundles");
                if (!imports.isDirectory() && !imports.mkdirs()) {
                    throw new IOException("Cannot create app import directory.");
                }
                if (!guests.isDirectory() && !guests.mkdirs()) {
                    throw new IOException("Cannot create guest bundle directory.");
                }
                File gsiDestination = new File(imports, "ci_" + safeName(gsiName));
                File guestDestination = new File(guests, "ci_" + safeName(guestName));
                copyFile(gsiSource, gsiDestination);
                copyFile(guestSource, guestDestination);
                GsiAnalysis gsiResult = GsiAnalyzer.analyze(gsiDestination);
                GuestBundleAnalysis guestResult = GuestBundleAnalyzer.analyze(guestDestination);
                selectedFile = gsiDestination;
                guestFile = guestDestination;
                mainHandler.post(() -> {
                    analysis = gsiResult;
                    guestAnalysis = guestResult;
                    selectedText.setText("Selected: " + gsiName + "\nStored privately in app storage");
                    guestText.setText("Selected: " + guestName + "\n"
                            + (guestResult.bootCandidate
                            ? "Kernel, ramdisk, and vendor entries found"
                            : "Bundle is incomplete; see the report"));
                    reportText.setText(guestResult.render());
                    analyzeButton.setEnabled(true);
                    exportButton.setEnabled(true);
                    refreshBootButton();
                    appendLog("CI-staged GSI and guest ready for the Material UI smoke flow.");
                });
            } catch (Exception error) {
                mainHandler.post(() -> appendLog("ERROR preparing CI-staged assets: " + error.getMessage()));
            }
        });
    }

    private static void copyFile(File source, File destination) throws IOException {
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
    }

    private void importGuestBundle(Uri uri) {
        final String displayName = displayName(uri);
        guestText.setText("Copying: " + displayName);
        guestAnalysis = null;
        bootButton.setEnabled(false);
        appendLog("Guest bundle import requested: " + displayName);
        worker.execute(() -> {
            try {
                File dir = new File(getFilesDir(), "guest-bundles");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create guest bundle directory.");
                File destination = new File(dir, System.currentTimeMillis() + "_" + safeName(displayName));
                try (InputStream input = getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(destination)) {
                    if (input == null) throw new IOException("Android did not return a readable stream.");
                    byte[] buffer = new byte[1024 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                }
                GuestBundleAnalysis result = GuestBundleAnalyzer.analyze(destination);
                guestFile = destination;
                mainHandler.post(() -> {
                    guestAnalysis = result;
                    guestText.setText("Selected: " + displayName + "\n" + (result.bootCandidate
                            ? "Kernel, ramdisk, and vendor entries found"
                            : "Bundle is incomplete; see the report"));
                    reportText.setText(result.render());
                    exportButton.setEnabled(analysis != null || guestAnalysis != null);
                    refreshBootButton();
                    appendLog("Guest bundle analyzed: " + formatBytes(destination.length()));
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    guestText.setText("Guest bundle failed: " + error.getMessage());
                    appendLog("ERROR: " + error.getMessage());
                });
            }
        });
    }

    private void downloadDefaultGuest() {
        if (launchInProgress) return;
        guestFile = null;
        guestAnalysis = null;
        bootButton.setEnabled(false);
        guestDownloadButton.setEnabled(false);
        guestText.setText("Downloading official Ranchu guest (about 1.7 GB)...");
        appendLog("Downloading and verifying the official ARM64 Ranchu guest...");
        worker.execute(() -> {
            File partial = null;
            try {
                File directory = new File(getFilesDir(), "guest-bundles");
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("Cannot create app guest-bundle directory.");
                }
                partial = new File(directory, "official-ranchu-android35.zip.partial");
                File destination = new File(directory, "official-ranchu-android35.zip");
                HttpURLConnection connection = (HttpURLConnection) new URL(DEFAULT_RANCHU_URL).openConnection();
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(120000);
                connection.setInstanceFollowRedirects(true);
                connection.connect();
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Guest download returned HTTP " + connection.getResponseCode());
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[1024 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                        output.write(buffer, 0, read);
                    }
                } finally {
                    connection.disconnect();
                }
                String actual = hex(digest.digest());
                if (!DEFAULT_RANCHU_SHA256.equals(actual)) {
                    throw new IOException("Guest checksum mismatch: " + actual);
                }
                if (destination.exists() && !destination.delete()) {
                    throw new IOException("Cannot replace the previous Ranchu guest.");
                }
                if (!partial.renameTo(destination)) {
                    throw new IOException("Cannot finalize the downloaded Ranchu guest.");
                }
                GuestBundleAnalysis result = GuestBundleAnalyzer.analyze(destination);
                guestFile = destination;
                mainHandler.post(() -> {
                    guestAnalysis = result;
                    guestDownloadButton.setEnabled(true);
                    guestText.setText("Official Ranchu guest ready\nStored privately in app storage");
                    reportText.setText(result.render());
                    exportButton.setEnabled(analysis != null || guestAnalysis != null);
                    refreshBootButton();
                    appendLog("Official Ranchu guest verified and ready: " + formatBytes(destination.length()));
                });
            } catch (Exception error) {
                if (partial != null) partial.delete();
                mainHandler.post(() -> {
                    guestDownloadButton.setEnabled(true);
                    guestText.setText("Guest download failed: " + error.getMessage());
                    refreshBootButton();
                    appendLog("ERROR downloading guest: " + error.getMessage());
                });
            }
        });
    }

    private void analyzeInput() {
        if (selectedFile == null) return;
        analyzeButton.setEnabled(false);
        bootButton.setEnabled(false);
        appendLog("Analyzing headers and calculating SHA-256...");
        worker.execute(() -> {
            try {
                GsiAnalysis result = GsiAnalyzer.analyze(selectedFile);
                mainHandler.post(() -> {
                    analysis = result;
                    reportText.setText(result.render());
                    exportButton.setEnabled(true);
                    refreshBootButton();
                    analyzeButton.setEnabled(true);
                    appendLog(result.bootCandidate
                            ? "Preflight passed: image is a boot candidate pending a real guest bundle."
                            : "Preflight did not pass: inspect the report before continuing.");
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    analyzeButton.setEnabled(true);
                    reportText.setText("Analysis failed\n\n" + error.getMessage());
                    appendLog("ERROR: " + error.getMessage());
                });
            }
        });
    }

    private void probeRuntime() {
        if (selectedFile == null) {
            appendLog("Select a GSI image or ZIP first.");
            refreshBootButton();
            return;
        }
        if (analysis == null) {
            appendLog("The GSI has not been analyzed yet; analyzing it now.");
            analyzeInput();
            return;
        }
        if (guestFile == null) {
            appendLog("No guest bundle is selected; downloading the official ARM64 Ranchu guest now.");
            downloadDefaultGuest();
            return;
        }
        if (guestAnalysis == null) {
            appendLog("The guest bundle has not finished analyzing yet.");
            refreshBootButton();
            return;
        }
        if (launchInProgress) return;
        if (!analysis.bootCandidate || !guestAnalysis.bootCandidate) {
            appendLog("Cannot start VM: fix the compatibility errors shown in the preflight report first.");
            refreshBootButton();
            return;
        }
        RuntimeProbe probe = RuntimeProbe.inspect(this);
        QemuBootPlan plan = QemuBootPlan.inspect(this);
        reportText.setText(analysis.render() + "\n" + guestAnalysis.render() + "\n"
                + probe.render() + "\n" + plan.render(analysis, guestAnalysis));
        appendLog("Runtime probe complete.");
        if (!plan.enginePresent) {
            appendLog("Boot not started: this APK has no bundled QEMU system engine.");
        } else if (!probe.arm64) {
            appendLog("Boot not started: the current guest path requires an ARM64 host.");
        } else {
            bootMarkerSeen = false;
            bootStartedAt = 0L;
            coldRetryCount = 0;
            lastDisplayFrameState = -1;
            launchInProgress = true;
            bootButton.setEnabled(false);
            bootButton.setText("Opening guest display...");
            appendLog("Preparing private VM files and opening the guest display...");
            try {
                startActivityForResult(new Intent(this, org.libsdl.app.GsiSDLActivity.class), GUEST_DISPLAY);
            } catch (Exception error) {
                launchInProgress = false;
                bootButton.setText("Start VM");
                bootButton.setEnabled(true);
                appendLog("ERROR opening guest display: " + error.getMessage());
                return;
            }
            // SDLActivity can need more than one frame to create its Android
            // SurfaceView. Poll for readiness so the button cannot get stuck
            // disabled when the display opens slowly on a real device.
            mainHandler.postDelayed(() -> waitForGuestDisplay(
                    System.currentTimeMillis() + GUEST_DISPLAY_WAIT_MS), 100L);
        }
    }

    private void waitForGuestDisplay(long deadline) {
        if (!launchInProgress) return;
        if (!org.libsdl.app.GsiSDLActivity.isDisplayReady()) {
            if (System.currentTimeMillis() < deadline) {
                mainHandler.postDelayed(() -> waitForGuestDisplay(deadline), 100L);
                return;
            }
            launchInProgress = false;
            org.libsdl.app.GsiSDLActivity.closeDisplay();
            bootButton.setText("Start VM");
            bootButton.setEnabled(true);
            appendLog("ERROR: guest display did not become ready in time.");
            return;
        }
        startQemuSession();
    }

    private void startQemuSession() {
        worker.execute(() -> {
            try {
                QemuBootSession started = QemuBootSession.start(
                        this, selectedFile, guestFile, coldRetryCount != 0);
                mainHandler.post(() -> {
                    session = started;
                    launchInProgress = false;
                    bootStartedAt = System.currentTimeMillis();
                    lastQemuRunning = null;
                    bootButton.setEnabled(true);
                    bootButton.setText("Stop test VM");
                    appendLog(coldRetryCount == 0
                            ? "QEMU thread started; guest display is active."
                            : "Cold retry started with the conservative ARM64 TCG profile; "
                            + "waiting for an Android boot marker.");
                    mainHandler.post(consolePoller);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    launchInProgress = false;
                    org.libsdl.app.GsiSDLActivity.closeDisplay();
                    bootButton.setText("Start VM");
                    bootButton.setEnabled(true);
                    appendLog("ERROR starting QEMU: " + error.getMessage());
                });
            }
        });
    }

    private void retryStalledVm() {
        QemuBootSession stalled = session;
        if (stalled == null || launchInProgress || coldRetryCount != 0) return;
        session = null;
        coldRetryCount = 1;
        launchInProgress = true;
        mainHandler.removeCallbacks(consolePoller);
        bootButton.setText("Cold retrying VM...");
        bootButton.setEnabled(false);
        appendLog("No Android boot marker; restarting QEMU once with the conservative "
                + "ARM64 TCG profile.");
        worker.execute(() -> {
            stalled.stop();
            mainHandler.post(() -> {
                if (!org.libsdl.app.GsiSDLActivity.isDisplayReady()) {
                    launchInProgress = false;
                    bootButton.setText("Start VM");
                    bootButton.setEnabled(true);
                    appendLog("Cold retry cancelled because the guest display closed.");
                    return;
                }
                startQemuSession();
            });
        });
    }

    private void stopTestVm() {
        QemuBootSession stopping = session;
        session = null;
        lastQemuRunning = null;
        launchInProgress = false;
        bootStartedAt = 0L;
        mainHandler.removeCallbacks(consolePoller);
        bootButton.setText("Start VM");
        bootButton.setEnabled(false);
        if (stopping != null) {
            appendLog("Stopping QEMU...");
            worker.execute(() -> {
                stopping.stop();
                mainHandler.post(() -> {
                    org.libsdl.app.GsiSDLActivity.closeDisplay();
                    refreshBootButton();
                    appendLog("QEMU stopped.");
                });
            });
        }
    }

    private void restartTestVm() {
        QemuBootSession finished = session;
        session = null;
        lastQemuRunning = null;
        launchInProgress = true;
        mainHandler.removeCallbacks(consolePoller);
        bootButton.setText("Restarting VM...");
        bootButton.setEnabled(false);
        worker.execute(() -> {
            finished.stop();
            mainHandler.post(() -> {
                launchInProgress = false;
                probeRuntime();
            });
        });
    }

    private void refreshBootButton() {
        if (session != null && session.isRunning()) {
            bootButton.setText("Stop test VM");
            bootButton.setEnabled(true);
        } else if (!launchInProgress) {
            boolean hasGsi = selectedFile != null;
            bootButton.setText(hasGsi && guestFile == null
                    ? "Download guest & continue" : "Start VM");
            // Keep the control actionable after the GSI is selected.  The
            // click handler now performs any missing analysis or starts the
            // official guest download instead of leaving a dead-looking
            // button until every preparation step is completed.
            bootButton.setEnabled(hasGsi);
        }
    }

    private void exportReport() {
        if (analysis == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, analysis.inputName + ".gsi-report.txt");
        startActivityForResult(intent, EXPORT_REPORT);
    }

    private void writeExport(Uri uri) {
        worker.execute(() -> {
            try {
                StringBuilder report = new StringBuilder();
                if (analysis != null) report.append(analysis.render()).append('\n');
                if (guestAnalysis != null) report.append(guestAnalysis.render()).append('\n');
                report.append(RuntimeProbe.inspect(this).render());
                report.append("\n\nVM DISPLAY\n  frame: ")
                        .append(displayFrameDescription(org.libsdl.app.GsiSDLActivity.getFrameState()))
                        .append('\n');
                ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "w");
                if (descriptor == null) throw new IOException("Could not open the export destination.");
                try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                    output.write(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                mainHandler.post(() -> appendLog("Report exported."));
            } catch (Exception error) {
                mainHandler.post(() -> appendLog("ERROR exporting report: " + error.getMessage()));
            }
        });
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {
            // Fall back to the URI below.
        }
        String path = uri.getLastPathSegment();
        return path == null ? "imported-image" : path;
    }

    private static String safeName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }

    private void appendLog(String message) {
        if (logText == null) return;
        String previous = logText.getText().toString();
        String line = String.format(Locale.US, "[%tT] %s", System.currentTimeMillis(), message);
        logText.setText(previous.isEmpty() ? line : previous + "\n" + line);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MiB", bytes / 1048576.0);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(consolePoller);
        QemuBootSession stopping = session;
        session = null;
        if (stopping != null) worker.execute(stopping::stop);
        worker.shutdownNow();
        super.onDestroy();
    }
}
