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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_INPUT = 1001;
    private static final int EXPORT_REPORT = 1002;
    private static final int PICK_GUEST = 1003;
    private static final int GUEST_DISPLAY = 1004;
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
    private QemuBootSession session;
    private Boolean lastQemuRunning;
    private boolean launchInProgress;
    private final Runnable consolePoller = new Runnable() {
        @Override
        public void run() {
            if (session == null || analysis == null || guestAnalysis == null) return;
            boolean running = session.isRunning();
            if (lastQemuRunning == null || lastQemuRunning != running) {
                appendLog(running ? "QEMU process is running." : "QEMU exited; inspect the serial log for the boot failure.");
                lastQemuRunning = running;
            }
            reportText.setText(analysis.render() + "\n" + guestAnalysis.render()
                    + "\n\nQEMU STATE\n  process: " + (running ? "running" : "exited")
                    + "\n\nQEMU CONSOLE\n" + session.readConsole());
            if (!running && !launchInProgress) {
                bootButton.setText("Test VM");
                refreshBootButton();
            }
            mainHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        setContentView(buildView());
        appendLog("h3cknn's GSI tester " + BuildConfig.VERSION_NAME + " ready.");
        appendLog("Import a GSI and a compatible guest bundle to prepare a VM launch.");
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

        LinearLayout imageSection = cardSection(page, "1  GSI IMAGE", "Choose a raw or compressed system image, or a ZIP containing one.");
        selectedText = text("No image selected", 14, TEXT);
        selectedText.setPadding(0, dp(12), 0, dp(4));
        imageSection.addView(selectedText);

        MaterialButton selectButton = outlinedButton("Select GSI image or ZIP");
        selectButton.setOnClickListener(view -> chooseInput());
        imageSection.addView(selectButton);

        analyzeButton = button("Analyze image");
        analyzeButton.setEnabled(false);
        analyzeButton.setOnClickListener(view -> analyzeInput());
        imageSection.addView(analyzeButton);

        LinearLayout guestSection = cardSection(page, "2  BASE GUEST BUNDLE", "Use the official ARM64 Ranchu emulator ZIP for the ready-made test guest.");
        guestText = text("No guest bundle selected", 14, TEXT);
        guestText.setPadding(0, dp(12), 0, dp(4));
        guestSection.addView(guestText);

        MaterialButton guestButton = outlinedButton("Select guest bundle ZIP");
        guestButton.setOnClickListener(view -> chooseGuestBundle());
        guestSection.addView(guestButton);

        LinearLayout reportSection = cardSection(page, "3  PREFLIGHT REPORT", "Headers, compression, dynamic partitions, and boot assets are checked before launch.");
        reportText = console("Nothing analyzed yet.");
        reportSection.addView(reportText);

        exportButton = outlinedButton("Export report");
        exportButton.setEnabled(false);
        exportButton.setOnClickListener(view -> exportReport());
        reportSection.addView(exportButton);

        LinearLayout backendSection = cardSection(page, "4  VM BACKEND", "A transparent ARM64 QEMU launch path for testing, not a promise that arbitrary hardware images will boot.");
        TextView backendNote = text(
                "For this tester, use the official ARM64 emulator archive arm64-v8a-35_r08.zip: it supplies kernel-ranchu, ramdisk.img, vendor.img.gz, userdata.img, and the QEMU guest contract. The app adapts its fstab for the selected GSI and opens the guest display. Pixel factory images remain device-specific rather than universal VM bases.",
                14, MUTED);
        backendNote.setPadding(0, dp(12), 0, dp(4));
        backendSection.addView(backendNote);

        bootButton = button("Test VM");
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
            // window. Clean up the native QEMU thread before re-enabling Test VM.
            if (session != null) stopTestVm();
            else {
                launchInProgress = false;
                bootButton.setText("Test VM");
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
        if (analysis == null || guestAnalysis == null) return;
        if (launchInProgress) return;
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
            launchInProgress = true;
            bootButton.setEnabled(false);
            bootButton.setText("Opening guest display…");
            appendLog("Preparing private VM files and opening the guest display...");
            try {
                startActivityForResult(new Intent(this, org.libsdl.app.GsiSDLActivity.class), GUEST_DISPLAY);
            } catch (Exception error) {
                launchInProgress = false;
                bootButton.setText("Test VM");
                bootButton.setEnabled(true);
                appendLog("ERROR opening guest display: " + error.getMessage());
                return;
            }
            // Give SDLActivity time to create its Android SurfaceView before QEMU
            // initializes its SDL video backend on the worker thread.
            mainHandler.postDelayed(() -> worker.execute(() -> {
                if (!launchInProgress || !org.libsdl.app.GsiSDLActivity.isDisplayOpen()) return;
                try {
                    QemuBootSession started = QemuBootSession.start(this, selectedFile, guestFile);
                    mainHandler.post(() -> {
                        session = started;
                        launchInProgress = false;
                        lastQemuRunning = null;
                        bootButton.setEnabled(true);
                        bootButton.setText("Stop test VM");
                        appendLog("QEMU thread started; guest display is active.");
                        mainHandler.post(consolePoller);
                    });
                } catch (Exception error) {
                    mainHandler.post(() -> {
                        launchInProgress = false;
                        org.libsdl.app.GsiSDLActivity.closeDisplay();
                        bootButton.setText("Test VM");
                        bootButton.setEnabled(true);
                        appendLog("ERROR starting QEMU: " + error.getMessage());
                    });
                }
            }), 500L);
        }
    }

    private void stopTestVm() {
        QemuBootSession stopping = session;
        session = null;
        lastQemuRunning = null;
        launchInProgress = false;
        mainHandler.removeCallbacks(consolePoller);
        bootButton.setText("Test VM");
        bootButton.setEnabled(false);
        if (stopping != null) {
            appendLog("Stopping QEMU...");
            worker.execute(() -> {
                stopping.stop();
                mainHandler.post(() -> {
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
        bootButton.setText("Restarting VM…");
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
        boolean ready = analysis != null && analysis.bootCandidate
                && guestAnalysis != null && guestAnalysis.bootCandidate;
        if (session != null && session.isRunning()) {
            bootButton.setText("Stop test VM");
            bootButton.setEnabled(true);
        } else if (!launchInProgress) {
            bootButton.setText("Test VM");
            bootButton.setEnabled(ready);
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
