package com.omniboot.fs

import com.omniboot.model.PartitionScheme
import com.omniboot.usb.UsbBlockDevice
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/**
 * Handles creation of Master Boot Record (MBR) and GUID Partition Table (GPT)
 * directly onto physical storage sectors.
 */
class MbrGptPartitionManager(private val device: UsbBlockDevice) {

    companion object {
        const val SECTOR_SIZE = 512
        const val FIRST_USABLE_LBA = 2048L // 1MB alignment (standard for flash memory)

        // GPT Signatures and GUIDs
        private const val GPT_SIGNATURE = 0x5452415020494645L // "EFI PART" in little endian
        // EFI System Partition (ESP) GUID: C12A7328-F81F-11D2-BA4B-00A0C93EC93B
        val EFI_SYSTEM_PARTITION_GUID = UUID.fromString("c12a7328-f81f-11d2-ba4b-00a0c93ec93b")
        // Microsoft Basic Data Partition GUID: EBD0A0A2-B9E5-4433-87C0-68B6B72699C7
        val BASIC_DATA_PARTITION_GUID = UUID.fromString("ebd0a0a2-b9e5-4433-87c0-68b6b72699c7")
    }

    /**
     * Initializes the drive with the specified partition scheme.
     * Returns the starting LBA and total sector count of the primary boot partition.
     */
    @Throws(IOException::class)
    fun createPartitionTable(
        scheme: PartitionScheme,
        volumeLabel: String = "BOOT_USB"
    ): PartitionInfo {
        val totalSectors = device.totalSectors
        require(totalSectors > FIRST_USABLE_LBA + 2048) {
            "Drive is too small to partition (Total sectors: $totalSectors)"
        }

        return when (scheme) {
            PartitionScheme.MBR -> writeMbr(totalSectors)
            PartitionScheme.GPT -> writeGpt(totalSectors, volumeLabel)
        }
    }

    /**
     * Writes standard MBR with a single primary active FAT32 bootable partition.
     */
    private fun writeMbr(totalSectors: Long): PartitionInfo {
        val sector0 = ByteArray(SECTOR_SIZE)
        val startLba = FIRST_USABLE_LBA
        val sectorCount = (totalSectors - startLba - 34).coerceAtMost(0xFFFFFFFFL)

        val bb = ByteBuffer.wrap(sector0).order(ByteOrder.LITTLE_ENDIAN)

        // Jump instruction & NOP
        bb.put(0xEB.toByte())
        bb.put(0x3C.toByte())
        bb.put(0x90.toByte())

        // Partition 1 Entry at offset 446 (0x1BE)
        bb.position(446)
        bb.put(0x80.toByte()) // Bootable / Active flag
        bb.put(0x20.toByte()) // Starting Head
        bb.put(0x21.toByte()) // Starting Sector / Cylinder
        bb.put(0x00.toByte()) // Starting Cylinder
        bb.put(0x0C.toByte()) // Partition Type: FAT32 LBA
        bb.put(0xFE.toByte()) // Ending Head
        bb.put(0xFF.toByte()) // Ending Sector / Cylinder
        bb.put(0xFF.toByte()) // Ending Cylinder
        bb.putInt(startLba.toInt()) // Starting LBA
        bb.putInt(sectorCount.toInt()) // Sector Count

        // MBR Boot Signature at bytes 510-511
        sector0[510] = 0x55.toByte()
        sector0[511] = 0xAA.toByte()

        // Write sector 0 to USB device
        device.writeSectors(0L, sector0)
        device.sync()

        return PartitionInfo(
            startLba = startLba,
            sectorCount = sectorCount,
            scheme = PartitionScheme.MBR
        )
    }

    /**
     * Writes modern UEFI GPT partition table:
     * - Protective MBR at LBA 0
     * - Primary GPT Header at LBA 1
     * - Partition Entries at LBA 2..33
     * - Backup GPT Header at last sector
     */
    private fun writeGpt(totalSectors: Long, volumeLabel: String): PartitionInfo {
        val lastLba = totalSectors - 1L
        val startLba = FIRST_USABLE_LBA
        val endLba = lastLba - 34L
        val partitionSectorCount = endLba - startLba + 1L

        // 1. Protective MBR at LBA 0
        val protectiveMbr = ByteArray(SECTOR_SIZE)
        val mbrBb = ByteBuffer.wrap(protectiveMbr).order(ByteOrder.LITTLE_ENDIAN)
        mbrBb.position(446)
        mbrBb.put(0x00.toByte()) // Not bootable in MBR terms
        mbrBb.put(0x00.toByte()) // CHS Start
        mbrBb.put(0x02.toByte())
        mbrBb.put(0x00.toByte())
        mbrBb.put(0xEE.toByte()) // GPT Protective MBR type
        mbrBb.put(0xFF.toByte()) // CHS End
        mbrBb.put(0xFF.toByte())
        mbrBb.put(0xFF.toByte())
        mbrBb.putInt(1) // Starting LBA 1
        val protectiveSize = (totalSectors - 1L).coerceAtMost(0xFFFFFFFFL)
        mbrBb.putInt(protectiveSize.toInt())
        protectiveMbr[510] = 0x55.toByte()
        protectiveMbr[511] = 0xAA.toByte()
        device.writeSectors(0L, protectiveMbr)

        // 2. Prepare Partition Array (128 entries * 128 bytes = 16,384 bytes = 32 sectors)
        val entriesBuffer = ByteArray(32 * SECTOR_SIZE)
        val entBb = ByteBuffer.wrap(entriesBuffer).order(ByteOrder.LITTLE_ENDIAN)

        // Entry 1: EFI System / Basic Data boot partition
        putUuid(entBb, BASIC_DATA_PARTITION_GUID) // Partition Type GUID
        putUuid(entBb, UUID.randomUUID()) // Unique Partition GUID
        entBb.putLong(startLba) // Starting LBA
        entBb.putLong(endLba) // Ending LBA
        entBb.putLong(0L) // Attributes

        // Partition Name (UTF-16LE, up to 36 chars)
        val nameBytes = volumeLabel.take(36).toByteArray(Charsets.UTF_16LE)
        entBb.put(nameBytes)

        // Calculate CRC32 of Partition Entries array
        val entriesCrc = CRC32().apply { update(entriesBuffer) }.value

        // 3. Primary GPT Header at LBA 1
        val diskGuid = UUID.randomUUID()
        val primaryHeader = createGptHeader(
            currentLba = 1L,
            backupLba = lastLba,
            firstUsableLba = 34L,
            lastUsableLba = endLba,
            diskGuid = diskGuid,
            partitionEntriesLba = 2L,
            entriesCrc = entriesCrc
        )

        // 4. Backup GPT Header at last LBA
        val backupHeader = createGptHeader(
            currentLba = lastLba,
            backupLba = 1L,
            firstUsableLba = 34L,
            lastUsableLba = endLba,
            diskGuid = diskGuid,
            partitionEntriesLba = lastLba - 32L,
            entriesCrc = entriesCrc
        )

        // Write Primary GPT (LBA 1) and Entries (LBA 2..33)
        device.writeSectors(1L, primaryHeader)
        device.writeSectors(2L, entriesBuffer)

        // Write Backup Entries (LBA lastLba - 32) and Backup Header (lastLba)
        device.writeSectors(lastLba - 32L, entriesBuffer)
        device.writeSectors(lastLba, backupHeader)

        device.sync()

        return PartitionInfo(
            startLba = startLba,
            sectorCount = partitionSectorCount,
            scheme = PartitionScheme.GPT
        )
    }

    private fun createGptHeader(
        currentLba: Long,
        backupLba: Long,
        firstUsableLba: Long,
        lastUsableLba: Long,
        diskGuid: UUID,
        partitionEntriesLba: Long,
        entriesCrc: Long
    ): ByteArray {
        val header = ByteArray(SECTOR_SIZE)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        bb.putLong(GPT_SIGNATURE) // Signature
        bb.putInt(0x00010000) // Revision 1.0
        bb.putInt(92) // Header size
        bb.putInt(0) // CRC32 placeholder
        bb.putInt(0) // Reserved
        bb.putLong(currentLba)
        bb.putLong(backupLba)
        bb.putLong(firstUsableLba)
        bb.putLong(lastUsableLba)
        putUuid(bb, diskGuid)
        bb.putLong(partitionEntriesLba)
        bb.putInt(128) // Number of partition entries
        bb.putInt(128) // Size of each partition entry
        bb.putInt(entriesCrc.toInt()) // CRC32 of partition array

        // Calculate Header CRC32 (first 92 bytes with CRC field set to 0)
        val crc = CRC32()
        crc.update(header, 0, 92)
        val headerCrc = crc.value

        // Write computed CRC32 at offset 16
        bb.position(16)
        bb.putInt(headerCrc.toInt())

        return header
    }

    private fun putUuid(bb: ByteBuffer, uuid: UUID) {
        // GUIDs in GPT use mixed-endian representation
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits

        val data1 = (msb shr 32).toInt()
        val data2 = ((msb shr 16) and 0xFFFF).toShort()
        val data3 = (msb and 0xFFFF).toShort()

        bb.putInt(data1)
        bb.putShort(data2)
        bb.putShort(data3)

        val bbBig = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        bbBig.putLong(lsb)
        bb.put(bbBig.array())
    }

    data class PartitionInfo(
        val startLba: Long,
        val sectorCount: Long,
        val scheme: PartitionScheme
    )
}
