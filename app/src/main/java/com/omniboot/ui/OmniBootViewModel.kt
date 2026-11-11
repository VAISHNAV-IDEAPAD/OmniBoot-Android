package com.omniboot.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omniboot.flasher.IsoDetector
import com.omniboot.model.*
import com.omniboot.service.FlashForegroundService
import com.omniboot.usb.UsbDriveManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OmniBootViewModel(application: Application) : AndroidViewModel(application) {

    private val driveManager = UsbDriveManager(application)
    private val isoDetector = IsoDetector(application)

    private val _drives = MutableStateFlow<List<UsbDriveInfo>>(emptyList())
    val drives: StateFlow<List<UsbDriveInfo>> = _drives.asStateFlow()

    private val _selectedDrive = MutableStateFlow<UsbDriveInfo?>(null)
    val selectedDrive: StateFlow<UsbDriveInfo?> = _selectedDrive.asStateFlow()

    private val _selectedIso = MutableStateFlow<IsoMetadata?>(null)
    val selectedIso: StateFlow<IsoMetadata?> = _selectedIso.asStateFlow()

    private val _options = MutableStateFlow(FlashOptions())
    val options: StateFlow<FlashOptions> = _options.asStateFlow()

    private val _isAnalyzingIso = MutableStateFlow(false)
    val isAnalyzingIso: StateFlow<Boolean> = _isAnalyzingIso.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val flashProgress: StateFlow<FlashProgress> = FlashForegroundService.currentProgress

    init {
        refreshDrives()
    }

    fun refreshDrives() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = driveManager.getAvailableDrives()
            _drives.value = list
            if (_selectedDrive.value == null && list.isNotEmpty()) {
                _selectedDrive.value = list.first()
            }
        }
    }

    fun selectDrive(drive: UsbDriveInfo) {
        _selectedDrive.value = drive
    }

    fun selectIsoUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isAnalyzingIso.value = true
            try {
                val meta = isoDetector.inspectIso(uri)
                _selectedIso.value = meta

                // Automatically configure the most robust, zero-breakdown options
                _options.value = _options.value.copy(
                    strategy = meta.recommendedStrategy,
                    partitionScheme = if (meta.bootMode == BootMode.UEFI_ONLY || meta.detectedOsName.contains("Windows 11")) {
                        PartitionScheme.GPT
                    } else {
                        PartitionScheme.MBR
                    },
                    autoSplitWim = meta.requiresWimSplit,
                    bypassWindows11Requirements = meta.detectedOsName.contains("Windows 11")
                )
            } catch (e: Exception) {
                _errorMessage.value = "Failed to inspect ISO: ${e.message}"
            } finally {
                _isAnalyzingIso.value = false
            }
        }
    }

    fun setPartitionScheme(scheme: PartitionScheme) {
        _options.value = _options.value.copy(partitionScheme = scheme)
    }

    fun toggleWimSplit(enabled: Boolean) {
        _options.value = _options.value.copy(autoSplitWim = enabled)
    }

    fun toggleWin11Bypass(enabled: Boolean) {
        _options.value = _options.value.copy(bypassWindows11Requirements = enabled)
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun startFlashing(): Boolean {
        val drive = _selectedDrive.value ?: run {
            _errorMessage.value = "Please connect and select a target USB drive."
            return false
        }
        val iso = _selectedIso.value ?: run {
            _errorMessage.value = "Please select an ISO image file."
            return false
        }

        // Check USB permission for native USB devices
        if (!drive.isRootDevice && drive.nativeUsbDevice != null) {
            if (!driveManager.hasPermission(drive.nativeUsbDevice)) {
                driveManager.requestPermission(drive.nativeUsbDevice)
                _errorMessage.value = "USB permission requested. Please approve the prompt and try again."
                return false
            }
        }

        val blockDevice = driveManager.openDrive(drive) ?: run {
            _errorMessage.value = "Could not open USB storage connection. Replug the drive and try again."
            return false
        }

        FlashForegroundService.activeDevice = blockDevice
        FlashForegroundService.activeIsoMetadata = iso
        FlashForegroundService.activeOptions = _options.value

        val intent = Intent(getApplication(), FlashForegroundService::class.java).apply {
            action = FlashForegroundService.ACTION_START_FLASH
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplication<Application>().startForegroundService(intent)
        } else {
            getApplication<Application>().startService(intent)
        }

        return true
    }

    fun cancelFlashing() {
        val intent = Intent(getApplication(), FlashForegroundService::class.java).apply {
            action = FlashForegroundService.ACTION_CANCEL_FLASH
        }
        getApplication<Application>().startService(intent)
    }
}
