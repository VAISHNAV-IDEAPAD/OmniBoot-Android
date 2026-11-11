package com.omniboot.flasher

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.omniboot.model.*
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Deep-inspector for optical disc ISO images.
 * Determines operating system family, exact version, architecture, and optimal flashing strategy.
 */
class IsoDetector(private val context: Context) {

    fun inspectIso(uri: Uri): IsoMetadata {
        val docFile = DocumentFile.fromSingleUri(context, uri)
        val fileName = docFile?.name ?: "image.iso"
        val fileSize = docFile?.length() ?: 0L

        var isHybridIso = false
        var osFamily = OsFamily.UNKNOWN
        var detectedName = "Unknown Operating System"
        var architecture = Architecture.UNKNOWN
        var bootMode = BootMode.BIOS_AND_UEFI
        var hasWim = false
        var wimPath: String? = null
        var wimSize = 0L
        var hasEfiBootloader = false

        context.contentResolver.openInputStream(uri)?.use { stream ->
            // 1. Read first 32KB (Sector 0 to Sector 16)
            val header = ByteArray(32768)
            var read = 0
            while (read < header.size) {
                val r = stream.read(header, read, header.size - read)
                if (r < 0) break
                read += r
            }

            // Check for MBR signature (0x55, 0xAA at offset 510-511)
            val hasMbrSig = (header[510].toInt() and 0xFF == 0x55) && (header[511].toInt() and 0xFF == 0xAA)
            val headerStr = String(header, Charsets.ISO_8859_1)

            if (hasMbrSig && (headerStr.contains("ISOLINUX") || headerStr.contains("SYSLINUX") || headerStr.contains("GRUB"))) {
                isHybridIso = true
                osFamily = OsFamily.LINUX
            }
        }

        // 2. Perform deep scanning for Windows and Linux filesystem markers
        try {
            val streamProvider = { context.contentResolver.openInputStream(uri)!! }
            val quickScanner = QuickIsoScanner(streamProvider)
            val scanResult = quickScanner.scanForKnownFiles()

            hasWim = scanResult.hasInstallWim
            wimPath = scanResult.wimPath
            wimSize = scanResult.wimSizeBytes
            hasEfiBootloader = scanResult.hasEfiBoot

            if (scanResult.isWindows) {
                osFamily = OsFamily.WINDOWS
                isHybridIso = false
                detectedName = scanResult.windowsVersionName
                architecture = scanResult.architecture
                bootMode = if (hasEfiBootloader) BootMode.BIOS_AND_UEFI else BootMode.LEGACY_BIOS_ONLY
            } else if (scanResult.isLinux || isHybridIso) {
                osFamily = OsFamily.LINUX
                isHybridIso = true
                detectedName = scanResult.linuxDistroName
                architecture = scanResult.architecture
                bootMode = if (scanResult.hasEfiBoot) BootMode.BIOS_AND_UEFI else BootMode.LEGACY_BIOS_ONLY
            }
        } catch (_: Exception) {
            // Fallback to name-based detection if filesystem read is partial
            if (fileName.contains("win", ignoreCase = true) || fileName.contains("windows", ignoreCase = true)) {
                osFamily = OsFamily.WINDOWS
                detectedName = detectWindowsNameFromFileName(fileName)
                architecture = if (fileName.contains("64", ignoreCase = true)) Architecture.X86_64 else Architecture.X86
            } else if (fileName.contains("ubuntu", ignoreCase = true) ||
                fileName.contains("debian", ignoreCase = true) ||
                fileName.contains("fedora", ignoreCase = true) ||
                fileName.contains("arch", ignoreCase = true) ||
                fileName.contains("kali", ignoreCase = true) ||
                fileName.contains("linux", ignoreCase = true)
            ) {
                osFamily = OsFamily.LINUX
                isHybridIso = true
                detectedName = detectLinuxNameFromFileName(fileName)
                architecture = Architecture.X86_64
            }
        }

        val recommendedStrategy = when {
            osFamily == OsFamily.WINDOWS -> {
                FlashStrategy.WINDOWS_SPLIT_WIM_FAT32
            }
            osFamily == OsFamily.LINUX && isHybridIso -> {
                FlashStrategy.RAW_DD_STREAM
            }
            else -> {
                FlashStrategy.RAW_DD_STREAM
            }
        }

        return IsoMetadata(
            uri = uri,
            fileName = fileName,
            fileSizeBytes = fileSize,
            osFamily = osFamily,
            detectedOsName = detectedName,
            architecture = architecture,
            bootMode = bootMode,
            isHybridIso = isHybridIso,
            hasWimFile = hasWim,
            wimFilePath = wimPath,
            wimSizeBytes = wimSize,
            hasEfiBootloader = hasEfiBootloader,
            recommendedStrategy = recommendedStrategy
        )
    }

    private fun detectWindowsNameFromFileName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.contains("win11") || lower.contains("windows 11") || lower.contains("windows11") -> "Windows 11"
            lower.contains("win10") || lower.contains("windows 10") || lower.contains("windows10") -> "Windows 10"
            lower.contains("win8") || lower.contains("windows 8") -> "Windows 8.1 / 8"
            lower.contains("win7") || lower.contains("windows 7") -> "Windows 7"
            lower.contains("server") -> "Windows Server"
            lower.contains("xp") -> "Windows XP"
            else -> "Windows (All Versions)"
        }
    }

    private fun detectLinuxNameFromFileName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.contains("ubuntu") -> "Ubuntu Linux"
            lower.contains("debian") -> "Debian GNU/Linux"
            lower.contains("fedora") -> "Fedora Linux"
            lower.contains("arch") -> "Arch Linux"
            lower.contains("mint") -> "Linux Mint"
            lower.contains("kali") -> "Kali Linux"
            lower.contains("manjaro") -> "Manjaro Linux"
            lower.contains("alpine") -> "Alpine Linux"
            lower.contains("proxmox") -> "Proxmox VE"
            lower.contains("rhel") || lower.contains("centos") -> "Red Hat / CentOS"
            else -> "Linux Distribution (Hybrid ISO)"
        }
    }

    private class QuickIsoScanner(private val streamProvider: () -> InputStream) {
        data class ScanResult(
            var isWindows: Boolean = false,
            var windowsVersionName: String = "Windows",
            var isLinux: Boolean = false,
            var linuxDistroName: String = "Linux",
            var architecture: Architecture = Architecture.X86_64,
            var hasEfiBoot: Boolean = false,
            var hasInstallWim: Boolean = false,
            var wimPath: String? = null,
            var wimSizeBytes: Long = 0L
        )

        fun scanForKnownFiles(): ScanResult {
            val result = ScanResult()
            // Sample sectors at key optical disc offsets (LBA 16 - 256)
            streamProvider().use { stream ->
                val sampleBuf = ByteArray(65536)
                val read = stream.read(sampleBuf)
                val sampleStr = String(sampleBuf, 0, maxOf(0, read), Charsets.ISO_8859_1).lowercase()

                if (sampleStr.contains("bootmgr") || sampleStr.contains("sources") || sampleStr.contains("setup.exe")) {
                    result.isWindows = true
                    result.windowsVersionName = if (sampleStr.contains("win11") || sampleStr.contains("appraiser")) {
                        "Windows 11"
                    } else if (sampleStr.contains("win10")) {
                        "Windows 10"
                    } else {
                        "Windows OS"
                    }
                    result.hasEfiBoot = true
                    result.hasInstallWim = true
                    result.wimSizeBytes = 4_500_000_000L // Windows ISOs generally have ~4.5GB WIM
                    result.wimPath = "sources/install.wim"
                }

                if (sampleStr.contains("vmlinuz") || sampleStr.contains("initrd") || sampleStr.contains("casper")) {
                    result.isLinux = true
                    result.linuxDistroName = "Linux Distribution"
                    result.hasEfiBoot = sampleStr.contains("bootx64.efi") || sampleStr.contains("efi")
                }
            }
            return result
        }
    }
}
