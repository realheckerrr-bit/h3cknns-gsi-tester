# Limbo QEMU runtime

The GitHub Actions build fetches the ARM64 native runtime libraries from the pinned Limbo Android release:

- Source project: https://github.com/limboemu/limbo
- Release: `v6.0.1-LimboEmulator`
- Engine asset: `limbo-android-arm-6.0.1-qemu-5.1.0.apk`
- QEMU source/runtime version: QEMU 5.1.0
- License: GNU General Public License, version 2 or later, as provided by the Limbo project and its QEMU sources.

The binary is deliberately not committed to this repository. The workflow extracts the ARM64 companion libraries into the APK build directory, and the source repository remains reproducible from the pinned public release URL. Any redistribution of a resulting APK must preserve the applicable GPL notices and source availability.
