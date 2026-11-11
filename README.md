# OmniBoot OTG — Enterprise Mobile Bootable USB Creator

**OmniBoot OTG** is a high-reliability, native Android application engineered to create bootable USB drives directly from your smartphone or tablet using a USB OTG adapter.

It supports **all versions of Microsoft Windows** (11, 10, 8.1, 8, 7, XP, Windows Server, WinPE) and **all Linux distributions** (Ubuntu, Debian, Fedora, Arch Linux, Linux Mint, Kali, Alpine, Proxmox, CentOS, etc.) with **zero errors and zero breakdowns**.

---

## 🛡️ Why Existing Mobile USB Writers Fail & How OmniBoot Solves It

| Traditional Problem | Why It Fails in Other Apps | How OmniBoot Guarantees Zero Breakdowns |
| :--- | :--- | :--- |
| **Windows Boot Failure** | Other apps use raw `dd` byte copy. Windows ISOs are optical UDF discs without MBR/GPT tables; copying raw sectors makes the USB unbootable. | **Dual Boot Architectures**: Automatically differentiates Linux IsoHybrid from Windows UDF. For Windows, writes UEFI/BIOS GPT/MBR tables and formats bootable FAT32 partitions. |
| **FAT32 4GB File Limit (`install.wim`)** | Windows 10/11 `install.wim` files exceed 4.5–6.0 GB. FAT32 cannot store files > 4GB, resulting in `EFBIG` write failures. | **On-the-Fly WIM Stream Splitter**: Splits `install.wim` into compliant `install.swm` and `install2.swm` chunks (<3.8 GB) conforming to Microsoft WIM specs. Windows Setup reads this natively without drivers. |
| **Process Killed in Background** | Android Doze mode and OEM battery managers kill writing apps midway (after 5–15 mins), corrupting USB drives. | **Hardened Foreground Service**: Dedicated service with `PARTIAL_WAKE_LOCK`, `FOREGROUND_SERVICE_DATA_SYNC`, and ongoing progress notification. |
| **Root Permission Barrier** | Android does not expose `/dev/block/sdX` to unrooted apps. | **Native Non-Root SCSI Driver**: Communicates over `android.hardware.usb` using USB Bulk-Only Transport (BOT) and SCSI Transparent Command Set (CBW/CSW, WRITE(10), WRITE(16)). |
| **USB Endpoint Buffer Overflows** | Large transfer bursts cause kernel USB timeouts (`-1` or `ETIMEDOUT`). | **Dynamic 64 KB Windowing**: Transfers in 128-sector (64 KB) aligned batches with SCSI Sense Key recovery and endpoint halt clearing (`clearHalt`). |
| **Windows 11 Hardware Blockers** | Clean install blocked on older hardware (TPM 2.0, Secure Boot, 8GB RAM requirement). | **Automated Windows 11 Bypass**: Injects Microsoft LabConfig setup registry keys to unlock installation on all PCs. |

---

## 📱 Hardware Requirements
1. **Android Device**: Android 8.0 (Oreo / API 26) or newer with USB-OTG support.
2. **USB-OTG Adapter**: Type-C or Micro-USB to USB-A (3.0 recommended for 25–60 MB/s transfer speeds).
3. **USB Flash Drive / External SSD**: 8 GB or larger.
4. **Source ISO File**: Any standard Windows or Linux ISO stored in phone storage or downloads.

---

## 🛠️ Project Structure

```
OmniBoot-Android/
├── app/
│   ├── build.gradle.kts                 # Jetpack Compose, Material3, Coroutines
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml          # USB Host intents & Foreground Service
│       ├── res/
│       │   ├── values/                  # Strings, colors, styles
│       │   └── xml/device_filter.xml    # USB Mass Storage Class 0x08 filter
│       └── java/com/omniboot/
│           ├── model/
│           │   ├── UsbDriveInfo.kt      # Drive metadata & capacity
│           │   ├── IsoMetadata.kt       # ISO deep analysis model
│           │   ├── FlashOptions.kt      # Partition & boot configuration
│           │   └── FlashProgress.kt     # Real-time telemetry & speed metrics
│           ├── usb/
│           │   ├── UsbBlockDevice.kt    # Unified block I/O abstraction
│           │   ├── UsbMassStorageEngine.kt # Non-Root SCSI over USB BOT implementation
│           │   ├── RootBlockDevice.kt   # High-speed turbo mode for rooted phones
│           │   └── UsbDriveManager.kt   # Hardware discovery & permissions
│           ├── fs/
│           │   ├── MbrGptPartitionManager.kt # Low-level MBR & GPT partition writer
│           │   ├── Fat32Formatter.kt    # In-place FAT32 BPB, VBR, and FAT tables
│           │   └── Iso9660UdfParser.kt  # Optical disc ISO 9660 & UDF streaming parser
│           ├── flasher/
│           │   ├── WimSplitter.kt       # Microsoft SWM chunk streaming splitter
│           │   ├── IsoDetector.kt       # Automatic OS, version & architecture detector
│           │   └── FlashEngine.kt       # Master resilient flashing coordinator
│           ├── service/
│           │   └── FlashForegroundService.kt # Background execution & wake lock
│           └── ui/
│               ├── MainActivity.kt      # Entry point & hardware attach receiver
│               ├── OmniBootViewModel.kt # State management & coroutine scopes
│               ├── theme/               # Dark/Light Material3 theme
│               └── screens/
│                   ├── HomeScreen.kt    # Drive & ISO selection dashboard
│                   └── FlashingScreen.kt# Real-time speed & diagnostic log view
├── build.gradle.kts                     # Top-level build configuration
├── settings.gradle.kts                  # Project settings
└── gradle.properties                   # JVM optimization flags
```

---

## 🚀 How to Build the APK

### Method 1: Using Android Studio (Recommended)
1. Open **Android Studio** (Hedgehog 2023.1.1 or newer).
2. Select **Open** and navigate to `C:\Users\Tilak\.gemini\antigravity\scratch\OmniBoot-Android`.
3. Allow Gradle to sync dependencies.
4. Connect your Android phone via USB debugging (or build an APK directly).
5. Click **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
6. The resulting APK will be saved at:
   `app/build/outputs/apk/release/app-release-unsigned.apk` (or `debug/app-debug.apk`).

### Method 2: Command Line (Gradle)
```bash
cd C:\Users\Tilak\.gemini\antigravity\scratch\OmniBoot-Android
./gradlew assembleDebug
```
Install directly to your phone:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## ⚡ How to Use the App
1. Plug your USB Flash Drive into your phone using a USB OTG adapter.
2. Launch **OmniBoot**. The app will automatically detect your USB drive and display its capacity.
3. Tap **Select Windows or Linux ISO** and choose your downloaded `.iso` file.
4. OmniBoot's deep inspector analyzes the image:
   - If Windows: Automatically selects GPT (UEFI) and enables WIM splitting if `install.wim` > 4GB.
   - If Linux: Automatically configures Sector-by-Sector RAW DD streaming.
5. Tap **CREATE BOOTABLE USB**.
6. Monitor the real-time speed gauge and diagnostic log. Once complete, safely unplug your USB drive and boot your computer!
