package com.omniboot.flasher

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles splitting Windows Imaging Format (.wim) files into (.swm) chunks.
 * Solves the critical FAT32 4GB file size limit issue seamlessly.
 *
 * Windows Setup natively supports split WIM files named:
 *   sources/install.swm
 *   sources/install2.swm
 *   sources/install3.swm
 */
class WimSplitter(
    private val maxChunkSizeBytes: Long = 3800L * 1024L * 1024L // 3.8 GB
) {

    companion object {
        const val WIM_HEADER_SIZE = 208
        const val WIM_MAGIC = "MSWIM\u0000\u0000\u0000"
        const val FLAG_HEADER_SPANNED = 0x00000008
    }

    /**
     * Splits a WIM input stream into multiple chunk outputs, adjusting WIM header flags.
     */
    fun splitStream(
        inputStream: InputStream,
        totalWimSize: Long,
        chunkOutputProvider: (partIndex: Int) -> OutputStream,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit
    ) {
        val totalParts = ((totalWimSize + maxChunkSizeBytes - 1) / maxChunkSizeBytes).toInt().coerceAtLeast(1)
        val headerBuffer = ByteArray(WIM_HEADER_SIZE)
        var totalRead = 0

        while (totalRead < WIM_HEADER_SIZE) {
            val r = inputStream.read(headerBuffer, totalRead, WIM_HEADER_SIZE - totalRead)
            if (r < 0) break
            totalRead += r
        }

        require(totalRead == WIM_HEADER_SIZE) { "Failed to read WIM header" }

        val magic = String(headerBuffer, 0, 8, Charsets.US_ASCII)
        val isStandardWim = magic == WIM_MAGIC

        var bytesProcessed = 0L
        val dataBuffer = ByteArray(64 * 1024) // 64 KB streaming buffer

        for (part in 1..totalParts) {
            val currentChunkTarget = if (part == totalParts) {
                totalWimSize - bytesProcessed
            } else {
                maxChunkSizeBytes
            }

            var chunkWritten = 0L
            val output = chunkOutputProvider(part)

            try {
                // If standard WIM, patch header for this split part
                if (isStandardWim) {
                    val patchedHeader = headerBuffer.copyOf()
                    val bb = ByteBuffer.wrap(patchedHeader).order(ByteOrder.LITTLE_ENDIAN)

                    // Offset 16: Flags (add FLAG_HEADER_SPANNED)
                    val flags = bb.getInt(16) or FLAG_HEADER_SPANNED
                    bb.putInt(16, flags)

                    // Offset 40: Part Number (ushort)
                    bb.putShort(40, part.toShort())

                    // Offset 42: Total Parts (ushort)
                    bb.putShort(42, totalParts.toShort())

                    output.write(patchedHeader)
                    chunkWritten += WIM_HEADER_SIZE
                    bytesProcessed += WIM_HEADER_SIZE
                }

                while (chunkWritten < currentChunkTarget) {
                    val toRead = minOf(dataBuffer.size.toLong(), currentChunkTarget - chunkWritten).toInt()
                    val read = inputStream.read(dataBuffer, 0, toRead)
                    if (read < 0) break

                    output.write(dataBuffer, 0, read)
                    chunkWritten += read
                    bytesProcessed += read
                    onProgress(bytesProcessed, totalWimSize)
                }

                output.flush()
            } finally {
                try { output.close() } catch (_: Exception) {}
            }
        }
    }
}
