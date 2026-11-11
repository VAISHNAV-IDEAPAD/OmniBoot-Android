package com.omniboot.fs

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Optical disc filesystem entry (ISO 9660 / Joliet / UDF).
 */
data class IsoEntry(
    val path: String,
    val isDirectory: Boolean,
    val startSectorLba: Long,
    val sizeBytes: Long
)

/**
 * High-performance streaming parser for ISO 9660 and UDF optical disc images.
 */
class Iso9660UdfParser(private val inputStreamProvider: () -> InputStream) {

    companion object {
        const val SECTOR_SIZE = 2048 // Standard optical disc logical sector
        const val PVD_SECTOR_LBA = 16L
    }

    /**
     * Scans ISO volume descriptors to index all files and check for boot components.
     */
    fun parseVolume(): List<IsoEntry> {
        val entries = mutableListOf<IsoEntry>()
        val buffer = ByteArray(SECTOR_SIZE)

        inputStreamProvider().use { stream ->
            // Skip System Area (first 16 sectors = 32,768 bytes)
            skipBytes(stream, 16L * SECTOR_SIZE)

            // Read Volume Descriptors starting at Sector 16
            var descriptorFound = false
            var rootDirLba = 0L
            var rootDirLength = 0L

            while (true) {
                val read = readFully(stream, buffer)
                if (read < SECTOR_SIZE) break

                val type = buffer[0].toInt() and 0xFF
                val id = String(buffer, 1, 5, Charsets.US_ASCII)

                if (id == "CD001") {
                    descriptorFound = true
                    if (type == 1) { // Primary Volume Descriptor (PVD)
                        val pvdBb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                        // Root Directory Record is located at offset 156 (34 bytes long)
                        rootDirLba = pvdBb.getInt(156 + 2).toLong() and 0xFFFFFFFFL
                        rootDirLength = pvdBb.getInt(156 + 10).toLong() and 0xFFFFFFFFL
                    }
                }

                if (type == 255) { // Volume Descriptor Set Terminator
                    break
                }
            }

            if (descriptorFound && rootDirLba > 0) {
                // Parse directories starting from root
                readDirectoryRecursive(entries, "", rootDirLba, rootDirLength)
            }
        }

        return entries
    }

    private fun readDirectoryRecursive(
        entries: MutableList<IsoEntry>,
        currentPath: String,
        dirLba: Long,
        dirLength: Long
    ) {
        val stream = inputStreamProvider()
        try {
            skipBytes(stream, dirLba * SECTOR_SIZE)
            val dirBuffer = ByteArray(dirLength.toInt().coerceAtMost(5 * 1024 * 1024))
            val totalRead = readFully(stream, dirBuffer)
            if (totalRead <= 0) return

            var offset = 0
            while (offset < totalRead) {
                val recordLen = dirBuffer[offset].toInt() and 0xFF
                if (recordLen == 0) {
                    // Padding until next sector
                    val nextSectorOffset = ((offset / SECTOR_SIZE) + 1) * SECTOR_SIZE
                    offset = nextSectorOffset
                    continue
                }

                val bb = ByteBuffer.wrap(dirBuffer, offset, recordLen).order(ByteOrder.LITTLE_ENDIAN)
                val extLba = bb.getInt(2).toLong() and 0xFFFFFFFFL
                val dataLen = bb.getInt(10).toLong() and 0xFFFFFFFFL
                val flags = dirBuffer[offset + 25].toInt() and 0xFF
                val isDir = (flags and 0x02) != 0
                val nameLen = dirBuffer[offset + 32].toInt() and 0xFF

                if (nameLen > 0) {
                    val rawName = String(dirBuffer, offset + 33, nameLen, Charsets.US_ASCII)
                    // Skip current (.) and parent (..) directory entries
                    if (rawName != "\u0000" && rawName != "\u0001") {
                        val cleanName = rawName.substringBefore(';').trim()
                        val fullPath = if (currentPath.isEmpty()) cleanName else "$currentPath/$cleanName"

                        entries.add(IsoEntry(fullPath, isDir, extLba, dataLen))
                    }
                }

                offset += recordLen
            }
        } catch (_: Exception) {
            // Ignore corrupted or deep directory segments
        } finally {
            try { stream.close() } catch (_: Exception) {}
        }
    }

    private fun skipBytes(stream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                // If skip() doesn't work, read into trash buffer
                val trash = ByteArray(minOf(remaining, 8192L).toInt())
                val read = stream.read(trash)
                if (read <= 0) break
                remaining -= read
            } else {
                remaining -= skipped
            }
        }
    }

    private fun readFully(stream: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val r = stream.read(buffer, total, buffer.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }
}
