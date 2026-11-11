package com.omniboot.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.omniboot.R
import com.omniboot.flasher.FlashEngine
import com.omniboot.model.FlashOptions
import com.omniboot.model.FlashPhase
import com.omniboot.model.FlashProgress
import com.omniboot.model.IsoMetadata
import com.omniboot.ui.MainActivity
import com.omniboot.usb.UsbBlockDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground Service dedicated to maintaining continuous CPU and I/O execution
 * throughout the long-running USB writing operation. Prevents Android OS from killing
 * the process.
 */
class FlashForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "omniboot_flash_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_FLASH = "com.omniboot.START_FLASH"
        const val ACTION_CANCEL_FLASH = "com.omniboot.CANCEL_FLASH"

        private val _currentProgress = MutableStateFlow(FlashProgress())
        val currentProgress: StateFlow<FlashProgress> = _currentProgress.asStateFlow()

        var activeDevice: UsbBlockDevice? = null
        var activeIsoMetadata: IsoMetadata? = null
        var activeOptions: FlashOptions? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: PowerManager.WakeLock? = null
    private var flashEngine: FlashEngine? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FLASH -> {
                startForeground(NOTIFICATION_ID, buildNotification("Starting bootable USB creation...", 0))
                startFlashing()
            }
            ACTION_CANCEL_FLASH -> {
                flashEngine?.cancel()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmniBoot::FlashWakeLock").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L) // 4 hours maximum timeout
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun startFlashing() {
        val dev = activeDevice ?: run {
            stopSelf()
            return
        }
        val iso = activeIsoMetadata ?: run {
            stopSelf()
            return
        }
        val opts = activeOptions ?: FlashOptions()

        flashEngine = FlashEngine(this, dev)

        serviceScope.launch {
            flashEngine?.execute(iso, opts) { progress ->
                _currentProgress.value = progress

                // Update notification
                val notification = buildNotification(
                    text = "${progress.statusMessage} • ${progress.formattedSpeed} • ETA ${progress.formattedEta}",
                    progressPercent = progress.progressPercent.toInt()
                )
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)

                if (progress.phase == FlashPhase.COMPLETED ||
                    progress.phase == FlashPhase.FAILED ||
                    progress.phase == FlashPhase.CANCELLED
                ) {
                    serviceScope.launch {
                        delay(2000)
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun buildNotification(text: String, progressPercent: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FlashForegroundService::class.java).apply { action = ACTION_CANCEL_FLASH },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Creating Bootable USB Drive")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .setProgress(100, progressPercent, progressPercent == 0)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        serviceScope.cancel()
        try {
            activeDevice?.close()
        } catch (_: Exception) {}
        activeDevice = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
