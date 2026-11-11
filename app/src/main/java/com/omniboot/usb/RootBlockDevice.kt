package com.omniboot.usb

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * High-speed direct block device accessor for rooted Android devices.
 * Directly writes to /dev/block/sdX or /dev/block/vold/
 */
class RootBlockDevice(
    private val blockDevicePath: String,
    override val totalCapacityBytes: Long,
    override val sectorSize: Int = 512
) : UsbBlockDevice {

    private var file: RandomAccessFile? = null

    init {
        val dev = File(blockDevicePath)
        require(dev.exists() && dev.canWrite()) {
            "Block device $blockDevicePath does not exist or is not writable."
        }
        file = RandomAccessFile(dev, "rwd")
    }

    override fun writeSectors(lba: Long, data: ByteArray, offset: Int, length: Int): Int {
        val raf = file ?: throw IOException("Device is closed")
        raf.seek(lba * sectorSize)
        raf.write(data, offset, length)
        return length
    }

    override fun readSectors(lba: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val raf = file ?: throw IOException("Device is closed")
        raf.seek(lba * sectorSize)
        return raf.read(buffer, offset, length)
    }

    override fun sync() {
        file?.fd?.sync()
    }

    override fun close() {
        try {
            sync()
        } catch (_: Exception) {}
        try {
            file?.close()
        } catch (_: Exception) {}
        file = null
    }
}
