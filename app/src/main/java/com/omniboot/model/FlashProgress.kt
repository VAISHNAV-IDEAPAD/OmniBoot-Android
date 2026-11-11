package com.omniboot.model

enum class FlashPhase {
    IDLE,
    PREPARING,
    PARTITIONING,
    FORMATTING,
    WRITING_BLOCKS,
    EXTRACTING_FILES,
    SPLITTING_WIM,
    INJECTING_BOOTLOADER,
    FINALIZING_CACHE_SYNC,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class FlashProgress(
    val phase: FlashPhase = FlashPhase.IDLE,
    val progressPercent: Float = 0.0f,
    val bytesProcessed: Long = 0L,
    val totalBytes: Long = 0L,
    val currentSpeedMBps: Double = 0.0,
    val estimatedSecondsRemaining: Long = 0L,
    val currentItemName: String = "",
    val statusMessage: String = "Ready",
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null
) {
    val isRunning: Boolean
        get() = phase != FlashPhase.IDLE &&
                phase != FlashPhase.COMPLETED &&
                phase != FlashPhase.FAILED &&
                phase != FlashPhase.CANCELLED

    val formattedSpeed: String
        get() = String.format("%.1f MB/s", currentSpeedMBps)

    val formattedEta: String
        get() {
            if (estimatedSecondsRemaining <= 0) return "--:--"
            val minutes = estimatedSecondsRemaining / 60
            val seconds = estimatedSecondsRemaining % 60
            return String.format("%02d:%02d", minutes, seconds)
        }
}
