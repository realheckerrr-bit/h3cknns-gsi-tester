# h3cknn's GSI tester for Android

h3cknn's GSI tester is a from-scratch Android test harness for importing and preflighting Generic System Images. It accepts a raw `system.img` or a ZIP containing `system.img`, calculates hashes, recognizes Android sparse/raw-ext4 headers, and reports whether the input is a plausible GSI candidate before a test run.

## Important scope

This first milestone intentionally does not claim that a normal APK can boot every GSI. A GSI is the generic system partition, not a complete device. A bootable guest also needs a compatible kernel, generic ramdisk, vendor/ODM interface, device model, and a VM engine. Android's Virtualization Framework is privileged and its reference AVF app flow is built around Microdroid; it is not a drop-in “boot arbitrary GSI” API for ordinary Play/sideloaded apps.

The app therefore refuses to fake a boot. The **VM backend** button probes ARM64, AVF availability, the privileged VM permission, and the presence of a bundled QEMU engine. The GitHub build now packages a pinned ARM64 QEMU runtime and the app can make a real headless boot attempt, streaming the guest serial log into the report. The guest assets still need a matching Android `virt` boot contract; a random phone kernel/vendor pair is not interchangeable with a QEMU guest.

### Guest bundle format

The second input is a ZIP containing the device-independent boot assets needed alongside the GSI:

```text
guest.zip
├── kernel                 # or kernel-ranchu / kernel-ranchu-64
├── ramdisk.img
├── vendor.img
├── userdata.img           # optional, created or supplied per test run
└── libqemu-system-aarch64.so  # optional override; normal APK builds bundle the engine
```

The analyzer only inspects names and hashes; it does not execute or mount imported files.

## Build

The repository has a GitHub Actions build at `.github/workflows/android.yml`.

1. Open the **Actions** tab.
2. Run **Build h3cknn's GSI tester APK**.
3. Download the `h3cknns-gsi-tester-debug-apk` artifact.

The project uses Java 17, Android Gradle Plugin 8.6.0, compile SDK 35, and no third-party runtime dependencies.

## Local use

Install the debug APK, tap **Select GSI image or ZIP**, then **Analyze image**. The import is copied into private app storage; the source image is never modified or mounted. Use **Export report** to save a text report.

## Roadmap toward real boot testing

- Define and validate a complete guest bundle: `kernel`, `ramdisk`, `vendor.img`, `system.img`, and userdata/configuration.
- Add a display/input bridge and ADB transport for the QEMU session; the current milestone is headless serial output.
- Add per-GSI compatibility profiles and a GPT/super-partition builder for Android guests that need named dynamic partitions.
- Test against a known ARM64 Android `virt` guest before widening the supported image set. Google's emulator `ranchu` machine is a separate engine/profile and is not silently assumed here.

“Anything of GSIs” is not a valid compatibility guarantee: image API level, ABI, filesystem, AVB state, vendor interface, kernel, and device model all affect boot.
