package com.omniboot.usb

import java.io.Closeable
import java.io.IOException

/**
 * Common abstraction representing a raw block storage device (USB flash drive, SSD, etc.)
 */
interface UsbBlockDevice : Closeable {
    val totalCapacityBytes: Long
    val sectorSize: Int
    val totalSectors: Long get() = if (sectorSize > 0) totalCapacityBytes / sectorSize else 0L

    /**
     * Writes raw bytes starting at sector index [lba].
     * Data size must be an exact multiple of [sectorSize].
     */
    @Throws(IOException::class)
    fun writeSectors(lba: Long, data: ByteArray, offset: Int = 0, length: Int = data.size): Int

    /**
     * Reads raw bytes starting at sector index [lba].
     */
    @Throws(IOException::class)
    fun readSectors(lba: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int

    /**
     * Flushes internal drive write-back cache to physical NAND flash storage.
     */
    @Throws(IOException::class)
    fun sync()
}
