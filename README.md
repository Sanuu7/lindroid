# Lindroid

Lindroid runs an ARM64 Debian 12 userland on Android through PRoot. It uses the
phone's Android kernel; it is not a virtual machine and does not require root.

## Current capabilities

- Installs the official `debian:12-slim` ARM64 OCI image from Docker Hub.
- Verifies every downloaded filesystem layer using its SHA-256 digest.
- Runs a persistent interactive Bash process through an Android-native PRoot.
- Provides command input, live terminal output, shortcuts, and start/stop controls.
- Keeps Debian files and `/root/storage` across sessions.
- Detects optional Shizuku ADB/root access and requests permission when available.
- Uses Material 3 Expressive motion, shapes, dynamic color, and adaptive layouts.

## Build

Use Java 17 and an Android SDK containing API 37:

```bash
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug
```

The initial build targets ARM64 Android 8.0 and newer. The generated APK is in
`app/build/outputs/apk/debug/`.

## Runtime limitations

PRoot supplies a Linux userland on the Android kernel. Rootless mode cannot run
Docker, load kernel modules, create real mounts, or provide a normal systemd boot.
Some full-screen terminal programs also require a future PTY-backed terminal
renderer; the current console is intended for command-oriented workflows.

## Bundled native components

The APK bundles the official Termux ARM64 builds of PRoot 5.1.107.92,
libandroid-shmem 0.7, and libtalloc 2.4.3. The PRoot dependency name is adjusted
from `libtalloc.so.2` to the APK-compatible `libtalloc.so`; program behavior is
otherwise unchanged. See `app/src/main/assets/THIRD_PARTY_NOTICES.md`.
