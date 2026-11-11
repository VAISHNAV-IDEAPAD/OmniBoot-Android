package com.omniboot.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniboot.model.FlashPhase
import com.omniboot.ui.OmniBootViewModel
import com.omniboot.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashingScreen(
    viewModel: OmniBootViewModel,
    onNavigateBack: () -> Unit
) {
    val progress by viewModel.flashProgress.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll logs to bottom as new messages arrive
    LaunchedEffect(progress.logs.size) {
        if (progress.logs.isNotEmpty()) {
            listState.animateScrollToItem(progress.logs.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Flashing Pipeline", color = TextPrimary, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceNavy),
                navigationIcon = {
                    if (!progress.isRunning) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = CyanAccent)
                        }
                    }
                }
            )
        },
        containerColor = DeepNavy
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Live Status Card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceNavy),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderNavy)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Phase Badge
                    val phaseColor = when (progress.phase) {
                        FlashPhase.COMPLETED -> GreenSuccess
                        FlashPhase.FAILED, FlashPhase.CANCELLED -> RedDanger
                        else -> CyanAccent
                    }

                    Box(
                        modifier = Modifier
                            .background(phaseColor.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            progress.phase.name.replace("_", " "),
                            color = phaseColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Big Percentage Counter
                    Text(
                        "${progress.progressPercent.toInt()}%",
                        color = TextPrimary,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Progress Bar
                    LinearProgressIndicator(
                        progress = { progress.progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = phaseColor,
                        trackColor = CardNavy
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Telemetry Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Speed", color = TextSecondary, fontSize = 12.sp)
                            Text(progress.formattedSpeed, color = TextPrimary, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Time Remaining", color = TextSecondary, fontSize = 12.sp)
                            Text(progress.formattedEta, color = TextPrimary, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Processed", color = TextSecondary, fontSize = 12.sp)
                            val mb = progress.bytesProcessed / (1024 * 1024)
                            Text("$mb MB", color = TextPrimary, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        progress.statusMessage,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 2
                    )
                }
            }

            // Real-Time Hardware & SCSI Logs
            Card(
                colors = CardDefaults.cardColors(containerColor = DeepNavy),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderNavy)),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("DIAGNOSTIC TELEMETRY LOG", color = CyanAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Icon(Icons.Default.Terminal, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(progress.logs) { log ->
                            Text(
                                "> $log",
                                color = if (log.contains("fail", ignoreCase = true) || log.contains("error", ignoreCase = true)) RedDanger else TextSecondary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Bottom Action Button
            if (progress.isRunning) {
                OutlinedButton(
                    onClick = { viewModel.cancelFlashing() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(RedDanger))
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = RedDanger)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ABORT FLASHING", color = RedDanger, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                ) {
                    Icon(Icons.Default.Done, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("RETURN HOME", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
