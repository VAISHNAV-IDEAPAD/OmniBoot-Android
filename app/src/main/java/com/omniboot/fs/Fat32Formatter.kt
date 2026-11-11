package com.omniboot.fs

import com.omniboot.usb.UsbBlockDevice
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Formats a block device partition as bootable FAT32.
 * Initializes VBR, BPB, FSInfo, FAT1, FAT2, and Root Directory sectors.
 */
class Fat32Formatter(private val device: UsbBlockDevice) {

    companion object {
        const val SECTOR_SIZE = 512
        const val RESERVED_SECTORS = 32
        const val NUM_FATS = 2
        const val ROOT_CLUSTER = 2
        const val FSINFO_SECTOR = 1
        const val BACKUP_BOOT_SECTOR = 6
    }

    @Throws(IOException::class)
    fun formatPartition(
        startLba: Long,
        sectorCount: Long,
        volumeLabel: String = "BOOT_USB"
    ): Fat32Layout {
        require(sectorCount > RESERVED_SECTORS + 1000) {
            "Partition is too small for FAT32 ($sectorCount sectors)"
        }

        // Determine optimal sectors per cluster (4KB to 32KB clusters)
        val sectorsPerCluster = when {
            sectorCount < 16_777_216L -> 8   // < 8GB: 4 KB clusters
            sectorCount < 33_554_432L -> 16  // 8GB - 16GB: 8 KB clusters
            sectorCount < 67_108_864L -> 32  // 16GB - 32GB: 16 KB clusters
            else -> 64                       // > 32GB: 32 KB clusters
        }

        // Calculate Sectors per FAT
        // Each cluster entry is 4 bytes.
        // Approx clusters = (sectorCount - RESERVED_SECTORS) / sectorsPerCluster
        // FatBytes = clusters * 4
        // SectorsPerFat = (FatBytes + SECTOR_SIZE - 1) / SECTOR_SIZE
        val approxClusters = (sectorCount - RESERVED_SECTORS) / sectorsPerCluster
        val fatBytes = (approxClusters + 2) * 4
        val sectorsPerFat = ((fatBytes + SECTOR_SIZE - 1) / SECTOR_SIZE).toInt()

        val dataStartSector = RESERVED_SECTORS + (NUM_FATS * sectorsPerFat)
        val totalDataSectors = sectorCount - dataStartSector
        val totalClusters = (totalDataSectors / sectorsPerCluster).toInt()

        val volumeId = Random.nextInt()

        // 1. Construct Volume Boot Record (VBR)
        val vbr = ByteArray(SECTOR_SIZE)
        val bb = ByteBuffer.wrap(vbr).order(ByteOrder.LITTLE_ENDIAN)

        // Jump instruction & NOP
        bb.put(0xEB.toByte())
        bb.put(0x58.toByte())
        bb.put(0x90.toByte())

        // OEM Name (8 bytes)
        bb.put("MSWIN4.1".toByteArray(Charsets.US_ASCII))

        // Bios Parameter Block (BPB)
        bb.putShort(SECTOR_SIZE.toShort()) // Bytes per sector (512)
        bb.put(sectorsPerCluster.toByte()) // Sectors per cluster
        bb.putShort(RESERVED_SECTORS.toShort()) // Reserved sector count (32)
        bb.put(NUM_FATS.toByte()) // Number of FATs (2)
        bb.putShort(0.toShort()) // Root directory entries (0 for FAT32)
        bb.putShort(0.toShort()) // Total sectors 16 (0)
        bb.put(0xF8.toByte()) // Media descriptor (Fixed disk)
        bb.putShort(0.toShort()) // Sectors per FAT 16 (0)
        bb.putShort(63.toShort()) // Sectors per track (63)
        bb.putShort(255.toShort()) // Number of heads (255)
        bb.putInt(startLba.toInt()) // Hidden sectors (Partition start LBA)
        bb.putInt(sectorCount.toInt()) // Total sectors 32

        // FAT32 Extended BPB
        bb.putInt(sectorsPerFat) // Sectors per FAT 32
        bb.putShort(0.toShort()) // Extended flags
        bb.putShort(0.toShort()) // Filesystem version (0.0)
        bb.putInt(ROOT_CLUSTER) // Root cluster (2)
        bb.putShort(FSINFO_SECTOR.toShort()) // FSInfo sector (1)
        bb.putShort(BACKUP_BOOT_SECTOR.toShort()) // Backup boot sector (6)
        bb.put(ByteArray(12)) // Reserved 12 bytes
        bb.put(0x80.toByte()) // Drive number (0x80)
        bb.put(0x00.toByte()) // Reserved
        bb.put(0x29.toByte()) // Boot signature
        bb.putInt(volumeId) // Volume Serial Number

        // Volume Label (11 bytes space padded)
        val paddedLabel = volumeLabel.take(11).padEnd(11, ' ')
        bb.put(paddedLabel.toByteArray(Charsets.US_ASCII))

        // System identifier string (8 bytes)
        bb.put("FAT32   ".toByteArray(Charsets.US_ASCII))

        // Boot sector signature
        vbr[510] = 0x55.toByte()
        vbr[511] = 0xAA.toByte()

        // 2. Construct FSInfo Sector
        val fsInfo = ByteArray(SECTOR_SIZE)
        val infoBb = ByteBuffer.wrap(fsInfo).order(ByteOrder.LITTLE_ENDIAN)
        infoBb.putInt(0x41615252) // Lead signature "RRaA"
        infoBb.position(484)
        infoBb.putInt(0x61417272) // Struct signature "rrAa"
        infoBb.putInt(totalClusters - 1) // Free cluster count
        infoBb.putInt(3) // Next free cluster hint
        infoBb.position(508)
        infoBb.put(0x00.toByte())
        infoBb.put(0x00.toByte())
        infoBb.put(0x55.toByte())
        infoBb.put(0xAA.toByte())

        // 3. Construct FAT1 & FAT2 Initial Sectors
        val fatSector = ByteArray(SECTOR_SIZE)
        val fatBb = ByteBuffer.wrap(fatSector).order(ByteOrder.LITTLE_ENDIAN)
        fatBb.putInt(0x0FFFFFF8) // Cluster 0: Media Descriptor
        fatBb.putInt(0x0FFFFFFF) // Cluster 1: End of Cluster Mark
        fatBb.putInt(0x0FFFFFFF) // Cluster 2 (Root directory): End of Cluster Mark

        // Write VBR and FSInfo to Primary (Sectors 0, 1) and Backup (Sectors 6, 7)
        device.writeSectors(startLba, vbr)
        device.writeSectors(startLba + FSINFO_SECTOR, fsInfo)
        device.writeSectors(startLba + BACKUP_BOOT_SECTOR, vbr)
        device.writeSectors(startLba + BACKUP_BOOT_SECTOR + 1, fsInfo)

        // Write FAT1 and FAT2 initial tables
        val fat1Lba = startLba + RESERVED_SECTORS
        val fat2Lba = fat1Lba + sectorsPerFat
        device.writeSectors(fat1Lba, fatSector)
        device.writeSectors(fat2Lba, fatSector)

        // Clear Root Directory cluster (Cluster 2)
        val rootDirLba = startLba + dataStartSector
        val rootDirZero = ByteArray(sectorsPerCluster * SECTOR_SIZE)
        device.writeSectors(rootDirLba, rootDirZero)

        device.sync()

        return Fat32Layout(
            partitionStartLba = startLba,
            totalSectors = sectorCount,
            sectorsPerCluster = sectorsPerCluster,
            fat1StartLba = fat1Lba,
            fat2StartLba = fat2Lba,
            sectorsPerFat = sectorsPerFat,
            dataStartLba = startLba + dataStartSector,
            totalClusters = totalClusters
        )
    }

    data class Fat32Layout(
        val partitionStartLba: Long,
        val totalSectors: Long,
        val sectorsPerCluster: Int,
        val fat1StartLba: Long,
        val fat2StartLba: Long,
        val sectorsPerFat: Int,
        val dataStartLba: Long,
        val totalClusters: Int
    )
}
