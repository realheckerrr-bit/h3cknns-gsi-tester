# h3cknn's GSI tester for Android

h3cknn's GSI tester is a from-scratch Android test harness for importing and preflighting Generic System Images. It accepts a raw `system.img`, gzip/XZ-compressed images, or a ZIP containing `system.img`, `system.img.gz`, or `system.img.xz`, calculates hashes, recognizes Android sparse/raw-ext4 headers, and reports whether the input is a plausible GSI candidate.

## Scope

A GSI is the generic system partition, not a complete device. A real guest also needs a compatible kernel, generic ramdisk, vendor interface, device model, and VM engine. Android's Virtualization Framework is privileged and its ordinary app flow is not a drop-in API for booting arbitrary GSIs.

The app packages a pinned ARM64 QEMU runtime and can make a real headless boot attempt on an ARM64 Android host. It streams the guest serial log into the report. The guest assets still need a matching Android `virt` or emulator-style boot contract.

## Guest bundle input

Select a ZIP containing these files:

```text
guest.zip
├── kernel                 # or kernel-ranchu / kernel-ranchu-64
├── ramdisk.img            # .gz/.xz are accepted
├── vendor.img             # vendor.img.gz/.xz and vendor_a.img are accepted
├── userdata.img           # optional; gzip/XZ is accepted
├── cache.img               # optional Ranchu/Cuttlefish cache disk
├── encryptionkey.img       # optional Ranchu encryption-key disk
└── libqemu-system-aarch64.so  # optional engine override
```

Pixel-style guest archives may provide `boot.img`, `vendor_boot.img`, or
`init_boot.img` instead of separate kernel/ramdisk files; the app extracts the
payloads during private boot preparation. This improves inspection and
experimentation, but Pixel kernel/vendor files remain device-specific and are
not treated as a universal Cuttlefish replacement.

For the recommended QEMU `virt` profile, use an ARM64 Cuttlefish image archive from [AOSP Continuous Integration](https://ci.android.com/builds/branches/aosp-android-latest-release/grid?legacy=1), target `aosp_cf_arm64_only_phone-userdebug`: `aosp_cf_arm64_only_phone-img-<build>.zip`. It contains the Cuttlefish kernel/ramdisk, `super.img`, and userdata; the app extracts the logical `vendor_a` partition from `super.img` and uses the selected GSI as the system image. Choose the matching 4K or 16K Cuttlefish artifacts for the page size of the GSI. AOSP's [Cuttlefish setup guide](https://source.android.com/docs/devices/cuttlefish/get-started) describes the same ARM64 target.

That Cuttlefish image ZIP is the intended ready-made base guest: select it directly in **BASE GUEST BUNDLE**; it does not need to be repacked. The app extracts `boot.img` (kernel and ramdisk), `vendor_a` from `super.img`, and `userdata.img` into private storage. The archive and GSI should come from compatible Android generations and page-size variants.

For a concrete test pair, the official Android Developers GSI page currently publishes an ARM64 AOSP GSI for Android 17 QPR2 (`CP41.260831.007`): [download the GSI ZIP](https://dl.google.com/developers/android/cinnamonbun/images/gsi/aosp_arm64-exp-CP41.260831.007-16416850-5e61c946.zip) and verify SHA-256 `5e61c946ee45680365336968827bf62509d90f66ed78938f88e4deedda40a7fe`. Use the Cuttlefish artifact from the same Android generation when available.

Official ARM64 Android Emulator SDK archives (`kernel-ranchu`, `ramdisk.img`, `vendor.img.gz`) are recognized as guest inputs, but they target Google's `ranchu` machine rather than this app's `virt` profile. A Pixel factory image is even more hardware-specific; its vendor and ramdisk are not a generic QEMU base and are intentionally not treated as a universal fallback.

If a Cuttlefish artifact is not available, the official SDK Manager package `system-images;android-35;google_apis;arm64-v8a` is another source for a test guest. Its direct package URL is [`arm64-v8a-35_r08.zip`](https://dl.google.com/android/repository/sys-img/google_apis/arm64-v8a-35_r08.zip); it contains the expected emulator files, including `kernel-ranchu`, `ramdisk.img`, `vendor.img.gz`, `userdata.img`, and `encryptionkey.img`. Import that ZIP as-is for inspection or experimentation, but prefer Cuttlefish for a real bundled-`virt` boot attempt.

Gzip assets are expanded into private app storage and imported files are never mounted or modified.

The analyzer only inspects names, headers, and hashes. The VM backend is the only component that executes a boot attempt.

## Build

GitHub Actions is configured in `.github/workflows/android.yml`.

1. Open the repository's **Actions** tab.
2. Run **Build h3cknn's GSI tester APK** or push to `main`.
3. Download the `h3cknns-gsi-tester-debug-apk` artifact.

For a real host-side boot check, run the separate **Cuttlefish GSI boot smoke test** workflow. Its defaults fetch a public ARM64 Cuttlefish image artifact and an official ARM64 AOSP GSI, launch the same headless `virt` layout, and upload the serial log. You can override the CI build ID, target, GSI URL, and checksum from the workflow form.

The project uses Java 17, Android Gradle Plugin 8.6.0, compile SDK 35, AndroidX, and Material Components 1.14.0. Unit tests cover raw/gzip GSI imports and official-style guest archives before the APK is assembled.

## Limitations

- The current VM milestone is headless serial output; display/input and ADB transport are next.
- Android dynamic partitions, AVB state, API/VNDK compatibility, and guest kernel configuration still determine whether a particular GSI boots.
- The APK currently ships the ARM64 QEMU backend, so the real boot path requires an ARM64 Android host.
- “Boot anything” is not a valid compatibility guarantee without a matching guest profile for each image family.
