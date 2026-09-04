package dev.lindroid.app.runtime

/**
 * A container flavor: which rootfs image is pulled, which architecture it runs
 * as, and what desktop it provides. ARM64 containers run natively under PRoot;
 * x86_64 containers run through the bundled QEMU user-mode emulator.
 */
enum class DistroFlavor(
    val label: String,
    val description: String,
    val registryRepository: String,
    val registryTag: String,
    val architecture: String,
    val needsEmulation: Boolean,
    val desktopLabel: String,
    val desktopSessionCommand: String,
    val minimumDesktopBytes: Long,
) {
    DEBIAN(
        label = "Debian 12",
        description = "The official Debian 12 slim ARM64 image. Runs natively.",
        registryRepository = "library/debian",
        registryTag = "12-slim",
        architecture = "arm64",
        needsEmulation = false,
        desktopLabel = "XFCE",
        desktopSessionCommand = "dbus-launch --exit-with-session startxfce4",
        minimumDesktopBytes = 1_200_000_000L,
    ),
    MINT(
        label = "Linux Mint 22",
        description = "Ubuntu 24.04 base with the real Mint repositories, Xfce edition. Experimental: x86_64 software runs emulated through QEMU and is noticeably slower.",
        registryRepository = "library/ubuntu",
        registryTag = "24.04",
        architecture = "amd64",
        needsEmulation = true,
        desktopLabel = "Xfce",
        desktopSessionCommand = "dbus-launch --exit-with-session startxfce4",
        minimumDesktopBytes = 2_500_000_000L,
    ),
}
