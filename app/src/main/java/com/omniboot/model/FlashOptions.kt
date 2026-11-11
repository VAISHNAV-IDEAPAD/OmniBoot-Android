package com.omniboot.model

enum class PartitionScheme {
    GPT,    // Recommended for modern UEFI systems (Windows 11, modern Linux)
    MBR     // Recommended for older BIOS/CSM systems and universal compatibility
}

enum class TargetFilesystem {
    FAT32,
    NTFS,
    EXFAT,
    RAW_CLONE
}

/**
 * User and automatic configuration options for the flashing operation.
 */
data class FlashOptions(
    val partitionScheme: PartitionScheme = PartitionScheme.GPT,
    val filesystem: TargetFilesystem = TargetFilesystem.FAT32,
    val strategy: FlashStrategy = FlashStrategy.RAW_DD_STREAM,
    val volumeLabel: String = "BOOT_USB",
    val autoSplitWim: Boolean = true,
    val splitChunkSizeMB: Int = 3800, // 3.8 GB chunks to guarantee safety within FAT32 4GB limit
    val bypassWindows11Requirements: Boolean = true, // Bypass TPM 2.0 / Secure Boot / 8GB RAM check
    val verifyAfterWrite: Boolean = true,
    val wipeWholeDisk: Boolean = true
)
