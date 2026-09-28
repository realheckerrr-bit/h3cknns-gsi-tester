package com.realheckerrr.gsilab;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
    private static final int PAD = 18;

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
    private Button analyzeButton;
    private Button bootButton;
    private Button exportButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(11, 15, 20));
        getWindow().setNavigationBarColor(Color.rgb(11, 15, 20));
        setContentView(buildView());
        appendLog("h3cknn's GSI tester 0.2.0 ready.");
        appendLog("Import a GSI and a compatible guest bundle to prepare a VM launch.");
    }

    private View buildView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(11, 15, 20));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(PAD, PAD, PAD, PAD);
        scroll.addView(page);

        TextView title = text("h3cknn's GSI tester", 28, Color.rgb(240, 244, 248));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        page.addView(title);
        TextView subtitle = text("Inspect generic system images before you risk a flash.", 15, Color.rgb(174, 187, 197));
        subtitle.setPadding(0, 4, 0, 20);
        page.addView(subtitle);

        page.addView(sectionTitle("1  GSI IMAGE"));
        selectedText = text("No image selected", 14, Color.rgb(214, 225, 232));
        selectedText.setPadding(0, 10, 0, 12);
        page.addView(selectedText);

        Button selectButton = button("Select GSI image or ZIP");
        selectButton.setOnClickListener(view -> chooseInput());
        page.addView(selectButton);

        analyzeButton = button("Analyze image");
        analyzeButton.setEnabled(false);
        analyzeButton.setOnClickListener(view -> analyzeInput());
        page.addView(analyzeButton);

        page.addView(sectionTitle("2  BASE GUEST BUNDLE"));
        guestText = text("No guest bundle selected", 14, Color.rgb(214, 225, 232));
        guestText.setPadding(0, 10, 0, 12);
        page.addView(guestText);

        Button guestButton = button("Select guest bundle ZIP");
        guestButton.setOnClickListener(view -> chooseGuestBundle());
        page.addView(guestButton);

        page.addView(sectionTitle("3  PREFLIGHT REPORT"));
        reportText = console("Nothing analyzed yet.");
        page.addView(reportText);

        exportButton = button("Export report");
        exportButton.setEnabled(false);
        exportButton.setOnClickListener(view -> exportReport());
        page.addView(exportButton);

        page.addView(sectionTitle("4  VM BACKEND"));
        TextView backendNote = text(
                "The guest ZIP must contain a compatible ARM64 kernel, ramdisk.img, and vendor.img. The app also needs a bundled QEMU system engine. This button produces a transparent launch plan and refuses to fake a boot when those pieces are unavailable.",
                14, Color.rgb(174, 187, 197));
        backendNote.setPadding(0, 8, 0, 12);
        page.addView(backendNote);

        bootButton = button("Check VM backend / prepare launch");
        bootButton.setEnabled(false);
        bootButton.setOnClickListener(view -> probeRuntime());
        page.addView(bootButton);

        page.addView(sectionTitle("ACTIVITY LOG"));
        logText = console("");
        logText.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        logText.setTextSize(12);
        page.addView(logText);
        return scroll;
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 12, Color.rgb(119, 214, 200));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, 24, 0, 0);
        return title;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private TextView console(String value) {
        TextView view = text(value, 13, Color.rgb(213, 225, 231));
        view.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        view.setBackgroundColor(Color.rgb(21, 28, 36));
        view.setPadding(14, 14, 14, 14);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.rgb(11, 15, 20));
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setAllCaps(false);
        button.setMinHeight(48);
        button.setBackgroundColor(Color.rgb(119, 214, 200));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 10);
        button.setLayoutParams(params);
        return button;
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
            appendLog("Boot plan prepared; display/input bridge and runtime boot validation remain.");
        }
    }

    private void refreshBootButton() {
        bootButton.setEnabled(analysis != null && analysis.bootCandidate
                && guestAnalysis != null && guestAnalysis.bootCandidate);
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
        worker.shutdownNow();
        super.onDestroy();
    }
}
