# h3cknn's GSI tester for Android

h3cknn's GSI tester is a from-scratch Android test harness for importing and preflighting Generic System Images. It accepts a raw `system.img`, a gzip-compressed image, or a ZIP containing `system.img`/`system.img.gz`, calculates hashes, recognizes Android sparse/raw-ext4 headers, and reports whether the input is a plausible GSI candidate.

## Scope

A GSI is the generic system partition, not a complete device. A real guest also needs a compatible kernel, generic ramdisk, vendor interface, device model, and VM engine. Android's Virtualization Framework is privileged and its ordinary app flow is not a drop-in API for booting arbitrary GSIs.

The app packages a pinned ARM64 QEMU runtime and can make a real headless boot attempt on an ARM64 Android host. It streams the guest serial log into the report. The guest assets still need a matching Android `virt` or emulator-style boot contract.

## Guest bundle input

Select a ZIP containing these files:

```text
guest.zip
├── kernel                 # or kernel-ranchu / kernel-ranchu-64
├── ramdisk.img            # .gz is accepted
├── vendor.img             # vendor.img.gz and vendor_a.img are accepted
├── userdata.img           # optional; gzip is accepted
└── libqemu-system-aarch64.so  # optional engine override
```

The app also accepts an official ARM64 Android Emulator SDK system-image archive directly. Those archives commonly contain `kernel-ranchu`, `ramdisk.img`, `vendor.img.gz`, `userdata.img`, and `system.img.gz`; the selected GSI replaces the archive's system image for the test run. Gzip assets are expanded into private app storage and imported files are never mounted or modified.

The analyzer only inspects names, headers, and hashes. The VM backend is the only component that executes a boot attempt.

## Build

GitHub Actions is configured in `.github/workflows/android.yml`.

1. Open the repository's **Actions** tab.
2. Run **Build h3cknn's GSI tester APK** or push to `main`.
3. Download the `h3cknns-gsi-tester-debug-apk` artifact.

The project uses Java 17, Android Gradle Plugin 8.6.0, compile SDK 35, and no third-party runtime dependencies. Unit tests cover raw/gzip GSI imports and official-style guest archives before the APK is assembled.

## Limitations

- The current VM milestone is headless serial output; display/input and ADB transport are next.
- Android dynamic partitions, AVB state, API/VNDK compatibility, and guest kernel configuration still determine whether a particular GSI boots.
- The APK currently ships the ARM64 QEMU backend, so the real boot path requires an ARM64 Android host.
- “Boot anything” is not a valid compatibility guarantee without a matching guest profile for each image family.
