package com.omniboot.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.omniboot.model.UsbDriveInfo
import java.io.File

class UsbDriveManager(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.omniboot.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /**
     * Enumerates all connected USB flash drives and mass storage peripherals.
     */
    fun getAvailableDrives(): List<UsbDriveInfo> {
        val drives = mutableListOf<UsbDriveInfo>()

        // 1. Check Android USB Host devices
        val deviceList = usbManager.deviceList
        for ((_, device) in deviceList) {
            val massStorageInterface = findMassStorageInterface(device)
            if (massStorageInterface != null) {
                val hasPerm = usbManager.hasPermission(device)
                var capacity = 0L

                // If we already have permission, peek drive capacity
                if (hasPerm) {
                    try {
                        openDevice(device)?.use { blockDev ->
                            capacity = blockDev.totalCapacityBytes
                        }
                    } catch (_: Exception) {}
                }

                drives.add(
                    UsbDriveInfo(
                        deviceId = device.deviceId,
                        deviceName = device.deviceName,
                        vendorId = device.vendorId,
                        productId = device.productId,
                        manufacturerName = device.manufacturerName,
                        productName = device.productName,
                        totalCapacityBytes = capacity,
                        nativeUsbDevice = device
                    )
                )
            }
        }

        // 2. Check for root block devices (/dev/block/sd*)
        checkRootBlockDevices(drives)

        return drives
    }

    private fun checkRootBlockDevices(list: MutableList<UsbDriveInfo>) {
        val devDir = File("/dev/block")
        if (devDir.exists() && devDir.canRead()) {
            val sdBranches = devDir.listFiles { _, name ->
                name.matches(Regex("sd[a-z]$")) // only whole disks, not partitions like sda1
            } ?: emptyArray()

            for (file in sdBranches) {
                // Ignore if already mapped via USB Host API
                val size = file.length()
                list.add(
                    UsbDriveInfo(
                        deviceId = file.absolutePath.hashCode(),
                        deviceName = file.name,
                        vendorId = 0,
                        productId = 0,
                        manufacturerName = "Direct Block",
                        productName = file.absolutePath,
                        totalCapacityBytes = size,
                        isRootDevice = true,
                        rootDevicePath = file.absolutePath
                    )
                )
            }
        }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) },
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    fun openDevice(device: UsbDevice): UsbBlockDevice? {
        val usbInterface = findMassStorageInterface(device) ?: return null
        val inEndpoint = findEndpoint(usbInterface, UsbConstants.USB_DIR_IN) ?: return null
        val outEndpoint = findEndpoint(usbInterface, UsbConstants.USB_DIR_OUT) ?: return null

        val connection = usbManager.openDevice(device) ?: return null
        return UsbMassStorageEngine(connection, usbInterface, inEndpoint, outEndpoint)
    }

    fun openDrive(driveInfo: UsbDriveInfo): UsbBlockDevice? {
        if (driveInfo.isRootDevice && driveInfo.rootDevicePath != null) {
            return RootBlockDevice(driveInfo.rootDevicePath, driveInfo.totalCapacityBytes)
        }
        val nativeDevice = driveInfo.nativeUsbDevice ?: return null
        return openDevice(nativeDevice)
    }

    private fun findMassStorageInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // Class 0x08 = Mass Storage
            if (iface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                return iface
            }
        }
        return null
    }

    private fun findEndpoint(usbInterface: UsbInterface, direction: Int): UsbEndpoint? {
        for (i in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(i)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                endpoint.direction == direction
            ) {
                return endpoint
            }
        }
        return null
    }
}
