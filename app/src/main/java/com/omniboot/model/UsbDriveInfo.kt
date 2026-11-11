package com.omniboot.model

import android.hardware.usb.UsbDevice

/**
 * Information describing a detected USB mass storage drive.
 */
data class UsbDriveInfo(
    val deviceId: Int,
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?,
    val totalCapacityBytes: Long,
    val sectorSize: Int = 512,
    val isRootDevice: Boolean = false,
    val rootDevicePath: String? = null,
    val nativeUsbDevice: UsbDevice? = null
) {
    val displayName: String
        get() {
            val name = listOfNotNull(manufacturerName?.trim(), productName?.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            return if (name.isNotBlank()) name else "USB Drive ($deviceName)"
        }

    val formattedCapacity: String
        get() {
            if (totalCapacityBytes <= 0) return "Unknown Capacity"
            val gb = totalCapacityBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            return String.format("%.2f GB", gb)
        }

    val totalSectors: Long
        get() = if (sectorSize > 0) totalCapacityBytes / sectorSize else 0L
}
