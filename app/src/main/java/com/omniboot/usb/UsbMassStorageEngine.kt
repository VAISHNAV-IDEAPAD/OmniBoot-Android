package com.omniboot.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Enterprise-grade non-root USB Mass Storage driver.
 * Communicates directly with USB Flash Drives using USB Bulk-Only Transport (BOT)
 * and the SCSI Transparent Command Set.
 */
class UsbMassStorageEngine(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint
) : UsbBlockDevice {

    companion object {
        private const val CBW_SIGNATURE = 0x43425355 // 'USBC'
        private const val CSW_SIGNATURE = 0x53425355 // 'USBS'
        private const val CBW_LENGTH = 31
        private const val CSW_LENGTH = 13

        // SCSI Command Opcodes
        private const val SCSI_TEST_UNIT_READY: Byte = 0x00
        private const val SCSI_REQUEST_SENSE: Byte = 0x03
        private const val SCSI_INQUIRY: Byte = 0x12
        private const val SCSI_READ_CAPACITY_10: Byte = 0x25
        private const val SCSI_READ_10: Byte = 0x28
        private const val SCSI_WRITE_10: Byte = 0x2A
        private const val SCSI_WRITE_16: Byte = 0x8A.toByte()
        private const val SCSI_SYNCHRONIZE_CACHE_10: Byte = 0x35

        // Bulk-Only Direction flags
        private const val DIRECTION_OUT = 0x00
        private const val DIRECTION_IN = 0x80

        // Timeouts
        private const val TIMEOUT_CONTROL_MS = 3000
        private const val TIMEOUT_DATA_MS = 15000
        private const val TIMEOUT_SYNC_MS = 30000

        // Safe transfer chunk size (64 KB = 128 sectors)
        const val CHUNK_SECTOR_COUNT = 128
        const val CHUNK_SIZE_BYTES = CHUNK_SECTOR_COUNT * 512
    }

    private var tagCounter = 1
    private var detectedCapacity: Long = 0L
    private var detectedSectorSize: Int = 512

    override val totalCapacityBytes: Long get() = detectedCapacity
    override val sectorSize: Int get() = detectedSectorSize

    init {
        require(connection.claimInterface(usbInterface, true)) {
            "Failed to claim USB Mass Storage interface."
        }
        initDrive()
    }

    private fun initDrive() {
        // Step 1: Test Unit Ready (retry up to 3 times to allow spin-up)
        for (i in 0 until 3) {
            if (testUnitReady()) break
            Thread.sleep(100)
        }

        // Step 2: Read Capacity
        readCapacity()
    }

    private fun testUnitReady(): Boolean {
        val cdb = ByteArray(6).apply { this[0] = SCSI_TEST_UNIT_READY }
        return try {
            val tag = tagCounter++
            sendCbw(tag, 0, DIRECTION_OUT, cdb)
            val csw = receiveCsw(tag)
            csw.status == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun readCapacity() {
        val cdb = ByteArray(10).apply { this[0] = SCSI_READ_CAPACITY_10 }
        val tag = tagCounter++
        sendCbw(tag, 8, DIRECTION_IN, cdb)

        val buffer = ByteArray(8)
        val read = connection.bulkTransfer(inEndpoint, buffer, buffer.size, TIMEOUT_CONTROL_MS)
        val csw = receiveCsw(tag)

        if (csw.status == 0 && read == 8) {
            val bb = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
            val lastLba = bb.int.toLong() and 0xFFFFFFFFL
            detectedSectorSize = bb.int
            if (detectedSectorSize <= 0) detectedSectorSize = 512
            detectedCapacity = (lastLba + 1L) * detectedSectorSize
        } else {
            // Default fallback if query fails
            detectedSectorSize = 512
            detectedCapacity = 16L * 1024 * 1024 * 1024 // Assume 16GB default
        }
    }

    @Synchronized
    override fun writeSectors(lba: Long, data: ByteArray, offset: Int, length: Int): Int {
        require(length % detectedSectorSize == 0) {
            "Write length ($length) must be aligned to sector size ($detectedSectorSize)"
        }
        val numSectors = length / detectedSectorSize
        var sectorsWritten = 0
        var currentOffset = offset
        var currentLba = lba

        while (sectorsWritten < numSectors) {
            val batchSectors = minOf(numSectors - sectorsWritten, CHUNK_SECTOR_COUNT)
            val batchBytes = batchSectors * detectedSectorSize

            writeSectorBatch(currentLba, data, currentOffset, batchBytes, batchSectors)

            sectorsWritten += batchSectors
            currentOffset += batchBytes
            currentLba += batchSectors
        }

        return length
    }

    private fun writeSectorBatch(lba: Long, data: ByteArray, offset: Int, length: Int, sectorCount: Int) {
        val tag = tagCounter++
        val cdb = if (lba <= 0xFFFFFFFFL && sectorCount <= 0xFFFF) {
            // WRITE(10)
            ByteArray(10).apply {
                this[0] = SCSI_WRITE_10
                this[2] = ((lba shr 24) and 0xFF).toByte()
                this[3] = ((lba shr 16) and 0xFF).toByte()
                this[4] = ((lba shr 8) and 0xFF).toByte()
                this[5] = (lba and 0xFF).toByte()
                this[7] = ((sectorCount shr 8) and 0xFF).toByte()
                this[8] = (sectorCount and 0xFF).toByte()
            }
        } else {
            // WRITE(16) for disks > 2TB
            ByteArray(16).apply {
                this[0] = SCSI_WRITE_16
                for (i in 0..7) {
                    this[2 + i] = ((lba shr (56 - i * 8)) and 0xFF).toByte()
                }
                this[10] = ((sectorCount shr 24) and 0xFF).toByte()
                this[11] = ((sectorCount shr 16) and 0xFF).toByte()
                this[12] = ((sectorCount shr 8) and 0xFF).toByte()
                this[13] = (sectorCount and 0xFF).toByte()
            }
        }

        sendCbw(tag, length, DIRECTION_OUT, cdb)

        // Stream data out in hardware safe slices
        var transferred = 0
        while (transferred < length) {
            val sliceSize = minOf(length - transferred, 16384)
            val res = connection.bulkTransfer(
                outEndpoint,
                data,
                offset + transferred,
                sliceSize,
                TIMEOUT_DATA_MS
            )
            if (res < 0) {
                clearEndpointHalt(outEndpoint)
                throw IOException("USB Bulk write failed at LBA $lba, transferred $transferred of $length")
            }
            transferred += res
        }

        val csw = receiveCsw(tag)
        if (csw.status != 0) {
            requestSense()
            throw IOException("SCSI WRITE command failed at LBA $lba with status code ${csw.status}")
        }
    }

    @Synchronized
    override fun readSectors(lba: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        require(length % detectedSectorSize == 0) {
            "Read length must be aligned to sector size ($detectedSectorSize)"
        }
        val sectorCount = length / detectedSectorSize
        val tag = tagCounter++

        val cdb = ByteArray(10).apply {
            this[0] = SCSI_READ_10
            this[2] = ((lba shr 24) and 0xFF).toByte()
            this[3] = ((lba shr 16) and 0xFF).toByte()
            this[4] = ((lba shr 8) and 0xFF).toByte()
            this[5] = (lba and 0xFF).toByte()
            this[7] = ((sectorCount shr 8) and 0xFF).toByte()
            this[8] = (sectorCount and 0xFF).toByte()
        }

        sendCbw(tag, length, DIRECTION_IN, cdb)

        var transferred = 0
        while (transferred < length) {
            val sliceSize = minOf(length - transferred, 16384)
            val res = connection.bulkTransfer(
                inEndpoint,
                buffer,
                offset + transferred,
                sliceSize,
                TIMEOUT_DATA_MS
            )
            if (res < 0) {
                clearEndpointHalt(inEndpoint)
                throw IOException("USB Bulk read failed at LBA $lba")
            }
            transferred += res
        }

        val csw = receiveCsw(tag)
        if (csw.status != 0) {
            requestSense()
            throw IOException("SCSI READ command failed at LBA $lba with status ${csw.status}")
        }

        return transferred
    }

    @Synchronized
    override fun sync() {
        val tag = tagCounter++
        val cdb = ByteArray(10).apply {
            this[0] = SCSI_SYNCHRONIZE_CACHE_10
        }
        try {
            sendCbw(tag, 0, DIRECTION_OUT, cdb)
            receiveCsw(tag)
        } catch (e: Exception) {
            // Some cheap USB drives do not implement SYNCHRONIZE CACHE; ignore gracefully
        }
    }

    private fun sendCbw(tag: Int, transferLength: Int, direction: Int, cdb: ByteArray) {
        val cbw = ByteBuffer.allocate(CBW_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(CBW_SIGNATURE)
        cbw.putInt(tag)
        cbw.putInt(transferLength)
        cbw.put(direction.toByte())
        cbw.put(0.toByte()) // LUN 0
        cbw.put(cdb.size.toByte())
        cbw.put(cdb)
        // Pad rest of 16-byte CDB area with 0
        for (i in cdb.size until 16) {
            cbw.put(0.toByte())
        }

        val sent = connection.bulkTransfer(outEndpoint, cbw.array(), CBW_LENGTH, TIMEOUT_CONTROL_MS)
        if (sent != CBW_LENGTH) {
            throw IOException("Failed to send CBW to USB drive (sent $sent of $CBW_LENGTH bytes)")
        }
    }

    private fun receiveCsw(expectedTag: Int): Csw {
        val buffer = ByteArray(CSW_LENGTH)
        val read = connection.bulkTransfer(inEndpoint, buffer, CSW_LENGTH, TIMEOUT_DATA_MS)
        if (read != CSW_LENGTH) {
            clearEndpointHalt(inEndpoint)
            throw IOException("Failed to receive valid CSW (received $read bytes)")
        }

        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val signature = bb.int
        val tag = bb.int
        val residue = bb.int
        val status = bb.get().toInt() and 0xFF

        if (signature != CSW_SIGNATURE) {
            throw IOException("Invalid CSW signature: 0x${Integer.toHexString(signature)}")
        }

        return Csw(signature, tag, residue, status)
    }

    private fun requestSense() {
        try {
            val tag = tagCounter++
            val cdb = ByteArray(6).apply {
                this[0] = SCSI_REQUEST_SENSE
                this[4] = 18 // Allocation length
            }
            sendCbw(tag, 18, DIRECTION_IN, cdb)
            val sense = ByteArray(18)
            connection.bulkTransfer(inEndpoint, sense, 18, TIMEOUT_CONTROL_MS)
            receiveCsw(tag)
        } catch (_: Exception) {
        }
    }

    private fun clearEndpointHalt(endpoint: UsbEndpoint) {
        try {
            // Standard USB Clear Feature: ENDPOINT_HALT
            connection.controlTransfer(
                0x02, // USB_RECIP_ENDPOINT
                0x01, // CLEAR_FEATURE
                0x00, // ENDPOINT_HALT
                endpoint.address,
                null,
                0,
                TIMEOUT_CONTROL_MS
            )
        } catch (_: Exception) {
        }
    }

    override fun close() {
        try {
            sync()
        } catch (_: Exception) {
        }
        try {
            connection.releaseInterface(usbInterface)
            connection.close()
        } catch (_: Exception) {
        }
    }

    private data class Csw(
        val signature: Int,
        val tag: Int,
        val residue: Int,
        val status: Int
    )
}
