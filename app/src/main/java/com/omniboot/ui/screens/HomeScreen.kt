package com.omniboot.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniboot.model.OsFamily
import com.omniboot.model.PartitionScheme
import com.omniboot.ui.OmniBootViewModel
import com.omniboot.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: OmniBootViewModel,
    onNavigateToFlashing: () -> Unit
) {
    val drives by viewModel.drives.collectAsState()
    val selectedDrive by viewModel.selectedDrive.collectAsState()
    val selectedIso by viewModel.selectedIso.collectAsState()
    val options by viewModel.options.collectAsState()
    val isAnalyzing by viewModel.isAnalyzingIso.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var showConfirmDialog by remember { mutableStateOf(false) }

    val isoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.selectIsoUri(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Usb,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "OmniBoot OTG",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Text(
                                "Universal Windows & Linux USB Flasher",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceNavy
                ),
                actions = {
                    IconButton(onClick = { viewModel.refreshDrives() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh USB", tint = CyanAccent)
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Error banner
            errorMessage?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = RedDanger.copy(alpha = 0.15f)),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(RedDanger)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = RedDanger)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(msg, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = TextSecondary)
                        }
                    }
                }
            }

            // SECTION 1: USB Target Drive
            Text("1. TARGET USB STORAGE", color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceNavy),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderNavy)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (drives.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.UsbOff, contentDescription = null, tint = OrangeWarning, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("No USB Drive Detected", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text("Connect a USB flash drive using an OTG adapter", color = TextSecondary, fontSize = 12.sp)
                            }
                            Button(
                                onClick = { viewModel.refreshDrives() },
                                colors = ButtonDefaults.buttonColors(containerColor = CardNavy)
                            ) {
                                Text("Scan", color = CyanAccent)
                            }
                        }
                    } else {
                        drives.forEach { drive ->
                            val isSelected = selectedDrive?.deviceId == drive.deviceId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) CardNavy else Color.Transparent)
                                    .border(
                                        width = if (isSelected) 1.5.dp else 0.dp,
                                        color = if (isSelected) CyanAccent else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { viewModel.selectDrive(drive) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.selectDrive(drive) },
                                    colors = RadioButtonDefaults.colors(selectedColor = CyanAccent)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(drive.displayName, color = TextPrimary, fontWeight = FontWeight.Medium)
                                    Text("${drive.formattedCapacity} • ${drive.deviceName}", color = TextSecondary, fontSize = 12.sp)
                                }
                                Box(
                                    modifier = Modifier
                                        .background(GreenSuccess.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("READY", color = GreenSuccess, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // SECTION 2: Source ISO Image
            Text("2. SOURCE ISO IMAGE", color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceNavy),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderNavy)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (selectedIso == null) {
                        OutlinedButton(
                            onClick = { isoPickerLauncher.launch(arrayOf("application/octet-stream", "application/x-iso9660-image", "*/*")) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(CyanAccent))
                        ) {
                            if (isAnalyzing) {
                                CircularProgressIndicator(color = CyanAccent, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Inspecting ISO Structures...", color = CyanAccent)
                            } else {
                                Icon(Icons.Default.FileOpen, contentDescription = null, tint = CyanAccent)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Select Windows or Linux ISO", color = CyanAccent, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        val iso = selectedIso!!
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (iso.osFamily == OsFamily.WINDOWS) Icons.Default.Window else Icons.Default.Terminal,
                                contentDescription = null,
                                tint = CyanAccent,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(iso.detectedOsName, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text("${iso.fileName} (${iso.formattedFileSize})", color = TextSecondary, fontSize = 12.sp)
                                Text("Architecture: ${iso.architecture} • Boot: ${iso.bootMode}", color = TextMuted, fontSize = 11.sp)
                            }
                            IconButton(onClick = {
                                isoPickerLauncher.launch(arrayOf("application/octet-stream", "application/x-iso9660-image", "*/*"))
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Change ISO", tint = CyanAccent)
                            }
                        }

                        if (iso.requiresWimSplit) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(BlueAccent.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = BlueAccent, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "install.wim > 4GB detected. Auto-split (.swm) enabled to guarantee zero FAT32 write errors.",
                                    color = TextPrimary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            // SECTION 3: Intelligent Boot Configuration
            Text("3. BOOT CONFIGURATION", color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceNavy),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderNavy)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Partition Scheme Selection
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Partition Scheme", color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text("Target computer architecture", color = TextSecondary, fontSize = 12.sp)
                        }
                        Row {
                            FilterChip(
                                selected = options.partitionScheme == PartitionScheme.GPT,
                                onClick = { viewModel.setPartitionScheme(PartitionScheme.GPT) },
                                label = { Text("GPT (UEFI)") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanAccent,
                                    selectedLabelColor = Color.Black
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            FilterChip(
                                selected = options.partitionScheme == PartitionScheme.MBR,
                                onClick = { viewModel.setPartitionScheme(PartitionScheme.MBR) },
                                label = { Text("MBR (BIOS)") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanAccent,
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }

                    HorizontalDivider(color = BorderNavy)

                    // Windows 11 Bypass Switch
                    if (selectedIso?.detectedOsName?.contains("Windows 11") == true) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Bypass Windows 11 Checks", color = TextPrimary, fontWeight = FontWeight.Medium)
                                Text("Removes TPM 2.0, Secure Boot, & RAM barriers", color = TextSecondary, fontSize = 12.sp)
                            }
                            Switch(
                                checked = options.bypassWindows11Requirements,
                                onCheckedChange = { viewModel.toggleWin11Bypass(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = CyanAccent)
                            )
                        }
                        HorizontalDivider(color = BorderNavy)
                    }

                    // Large WIM Splitting Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Zero-Error WIM Splitting", color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text("Splits files >4GB on-the-fly for FAT32 compatibility", color = TextSecondary, fontSize = 12.sp)
                        }
                        Switch(
                            checked = options.autoSplitWim,
                            onCheckedChange = { viewModel.toggleWimSplit(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = CyanAccent)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action: Start Flashing Button
            val isReady = selectedDrive != null && selectedIso != null && !isAnalyzing
            Button(
                onClick = { showConfirmDialog = true },
                enabled = isReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanAccent,
                    disabledContainerColor = CardNavy
                )
            ) {
                Icon(Icons.Default.FlashOn, contentDescription = null, tint = if (isReady) Color.Black else TextMuted)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "CREATE BOOTABLE USB",
                    color = if (isReady) Color.Black else TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }

    // Confirmation Alert Dialog
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = {
                Text("Confirm Drive Format", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "All existing data on ${selectedDrive?.displayName} (${selectedDrive?.formattedCapacity}) will be permanently wiped.\n\n" +
                    "OmniBoot will write ${selectedIso?.detectedOsName} using ${options.partitionScheme} partition scheme without breakdowns or errors.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        if (viewModel.startFlashing()) {
                            onNavigateToFlashing()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedDanger)
                ) {
                    Text("Erase & Create", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceNavy
        )
    }
}
