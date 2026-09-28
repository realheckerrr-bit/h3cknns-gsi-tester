package com.realheckerrr.gsilab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable result of inspecting one imported GSI container. */
public final class GsiAnalysis {
    public final String inputName;
    public final String containerType;
    public final String systemEntry;
    public final long systemBytes;
    public final String imageFormat;
    public final String sha256;
    public final boolean bootCandidate;
    public final List<String> warnings;
    public final List<String> errors;

    public GsiAnalysis(
            String inputName,
            String containerType,
            String systemEntry,
            long systemBytes,
            String imageFormat,
            String sha256,
            boolean bootCandidate,
            List<String> warnings,
            List<String> errors) {
        this.inputName = inputName;
        this.containerType = containerType;
        this.systemEntry = systemEntry;
        this.systemBytes = systemBytes;
        this.imageFormat = imageFormat;
        this.sha256 = sha256;
        this.bootCandidate = bootCandidate;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("INPUT\n");
        out.append("  name: ").append(inputName).append('\n');
        out.append("  container: ").append(containerType).append('\n');
        out.append("  system entry: ").append(systemEntry).append('\n');
        out.append("  system size: ").append(formatBytes(systemBytes)).append('\n');
        out.append("  image format: ").append(imageFormat).append('\n');
        out.append("  SHA-256: ").append(sha256).append('\n');
        out.append("\nCOMPATIBILITY GATE\n");
        out.append("  candidate: ").append(bootCandidate ? "yes" : "no").append('\n');
        if (warnings.isEmpty() && errors.isEmpty()) {
            out.append("  no warnings\n");
        }
        for (String warning : warnings) {
            out.append("  warning: ").append(warning).append('\n');
        }
        for (String error : errors) {
            out.append("  error: ").append(error).append('\n');
        }
        return out.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "unknown";
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MiB", bytes / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GiB", bytes / 1073741824.0);
    }
}
