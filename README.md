# h3cknn's GSI tester for Android

h3cknn's GSI tester is a from-scratch Android test harness for importing and preflighting Generic System Images. It accepts a raw `system.img` or a ZIP containing `system.img`, calculates hashes, recognizes Android sparse/raw-ext4 headers, and reports whether the input is a plausible GSI candidate before a test run.

## Important scope

This first milestone intentionally does not claim that a normal APK can boot every GSI. A GSI is the generic system partition, not a complete device. A bootable guest also needs a compatible kernel, generic ramdisk, vendor/ODM interface, device model, and a VM engine. Android's Virtualization Framework is privileged and its reference AVF app flow is built around Microdroid; it is not a drop-in “boot arbitrary GSI” API for ordinary Play/sideloaded apps.

The app therefore refuses to fake a boot. The **VM backend** button probes ARM64, AVF availability, and the privileged VM permission, then states exactly what is missing. The code leaves a clean backend seam for the next milestone: a privileged AVF/crosvm integration or a bundled QEMU engine paired with a complete Android guest bundle.

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
- Add a native VM engine appropriate to the target: privileged AVF/crosvm on a device image that grants the required permission, or a bundled QEMU/crosvm build with licensing and ABI review.
- Add a display/input bridge, serial/ADB log capture, VM lifecycle controls, and per-GSI compatibility profiles.
- Test against a known ARM64 Android emulator guest before widening the supported image set.

“Anything of GSIs” is not a valid compatibility guarantee: image API level, ABI, filesystem, AVB state, vendor interface, kernel, and device model all affect boot.
