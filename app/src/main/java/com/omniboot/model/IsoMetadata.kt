package com.omniboot.model

import android.net.Uri

enum class OsFamily {
    WINDOWS,
    LINUX,
    UNKNOWN
}

enum class Architecture {
    X86_64,
    ARM64,
    X86,
    UNKNOWN
}

enum class BootMode {
    UEFI_ONLY,
    BIOS_AND_UEFI,
    LEGACY_BIOS_ONLY
}

enum class FlashStrategy {
    RAW_DD_STREAM,              // Used for Linux IsoHybrid images (bit-for-bit sector copy)
    WINDOWS_SPLIT_WIM_FAT32,     // Standard UEFI bootable FAT32 with install.swm splitting (>4GB)
    WINDOWS_UEFI_NTFS_DUAL,      // Rufus style: NTFS data partition + UEFI:NTFS boot loader
    VENTOY_COMPATIBLE            // Prepares multi-boot Ventoy disk layout
}

/**
 * Metadata discovered after deep-inspecting the user's selected ISO file.
 */
data class IsoMetadata(
    val uri: Uri,
    val fileName: String,
    val fileSizeBytes: Long,
    val osFamily: OsFamily,
    val detectedOsName: String,
    val architecture: Architecture,
    val bootMode: BootMode,
    val isHybridIso: Boolean,
    val hasWimFile: Boolean,
    val wimFilePath: String? = null,
    val wimSizeBytes: Long = 0L,
    val hasEfiBootloader: Boolean = false,
    val recommendedStrategy: FlashStrategy = FlashStrategy.RAW_DD_STREAM
) {
    val formattedFileSize: String
        get() {
            val gb = fileSizeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            return if (gb >= 1.0) String.format("%.2f GB", gb)
            else String.format("%.1f MB", fileSizeBytes.toDouble() / (1024.0 * 1024.0))
        }

    val requiresWimSplit: Boolean
        get() = hasWimFile && wimSizeBytes >= 4_000_000_000L // Exceeds FAT32 4GB limit
}
