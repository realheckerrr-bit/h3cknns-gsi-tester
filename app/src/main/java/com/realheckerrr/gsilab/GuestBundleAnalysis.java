package com.realheckerrr.gsilab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Describes the non-GSI files required to boot an Android guest. */
public final class GuestBundleAnalysis {
    public final String inputName;
    public final int entryCount;
    public final String kernel;
    public final String ramdisk;
    public final String vendor;
    public final String userdata;
    public final String cache;
    public final String encryptionKey;
    public final String qemu;
    public final String sha256;
    public final boolean bootCandidate;
    public final List<String> warnings;
    public final List<String> errors;

    public GuestBundleAnalysis(
            String inputName,
            int entryCount,
            String kernel,
            String ramdisk,
            String vendor,
            String userdata,
            String cache,
            String encryptionKey,
            String qemu,
            String sha256,
            boolean bootCandidate,
            List<String> warnings,
            List<String> errors) {
        this.inputName = inputName;
        this.entryCount = entryCount;
        this.kernel = kernel;
        this.ramdisk = ramdisk;
        this.vendor = vendor;
        this.userdata = userdata;
        this.cache = cache;
        this.encryptionKey = encryptionKey;
        this.qemu = qemu;
        this.sha256 = sha256;
        this.bootCandidate = bootCandidate;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("GUEST BUNDLE\n");
        out.append("  name: ").append(inputName).append('\n');
        out.append("  entries: ").append(entryCount).append('\n');
        out.append("  kernel: ").append(value(kernel)).append('\n');
        out.append("  ramdisk: ").append(value(ramdisk)).append('\n');
        out.append("  vendor: ").append(value(vendor)).append('\n');
        out.append("  userdata: ").append(value(userdata)).append('\n');
        out.append("  cache: ").append(value(cache)).append('\n');
        out.append("  encryption key: ").append(value(encryptionKey)).append('\n');
        out.append("  bundled QEMU: ").append(value(qemu)).append('\n');
        out.append("  SHA-256: ").append(sha256).append('\n');
        out.append("  boot assets: ").append(bootCandidate ? "present" : "incomplete").append('\n');
        for (String warning : warnings) out.append("  warning: ").append(warning).append('\n');
        for (String error : errors) out.append("  error: ").append(error).append('\n');
        return out.toString();
    }

    private static String value(String value) {
        return value == null ? "missing" : value;
    }
}
