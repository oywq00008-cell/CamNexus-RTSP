package com.camnexus.rtsp

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Size

data class CodecCap(
    val name: String,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Int
) {
    val label: String get() = (if (hardware) "硬件 · " else "软件 · ") + name
}

data class DeviceProfile(
    val soc: String,
    val abi: String,
    val cores: Int,
    val ramMb: Long,
    val lowRam: Boolean,
    val h264: CodecCap?,
    val h265: CodecCap?,
    val cameraId: String,
    val cameraMaxSize: Size,
    val cameraMaxFps: Int,
    val wifiBand: String,
    val wifiLinkMbps: Int,
    val wifiRssi: Int
)

/**
 * 采集本机硬件能力。全部本地读取，不联网。
 * 判断依据是厂商 HAL 报的编解码器与相机能力，而不是 Build.MODEL 之类的型号字符串。
 */
object DeviceProbe {

    fun collect(context: Context, cameraId: String): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memoryInfo)
        val ramMb = memoryInfo.totalMem / (1024 * 1024)
        val lowRam = am?.isLowRamDevice ?: (ramMb < 2048)

        val sizes = CameraCaps.resolutions(context, cameraId)
        val maxSize = sizes.maxByOrNull { it.width * it.height } ?: Size(1280, 720)
        val cameraMaxFps = runCatching {
            CameraCaps.maxFps(context, cameraId, maxSize.width, maxSize.height)
        }.getOrDefault(30)

        val (band, link, rssi) = wifiInfo(context)

        return DeviceProfile(
            soc = socName(),
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "—",
            cores = Runtime.getRuntime().availableProcessors(),
            ramMb = ramMb,
            lowRam = lowRam,
            h264 = capOf(Codecs.MIME_H264),
            h265 = capOf(Codecs.MIME_H265),
            cameraId = cameraId,
            cameraMaxSize = maxSize,
            cameraMaxFps = cameraMaxFps,
            wifiBand = band,
            wifiLinkMbps = link,
            wifiRssi = rssi
        )
    }

    @Suppress("DEPRECATION")
    private fun wifiInfo(context: Context): Triple<String, Int, Int> {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return Triple("未知", 0, 0)
        val info = runCatching { wm.connectionInfo }.getOrNull() ?: return Triple("未知", 0, 0)
        val freq = info.frequency
        val band = when {
            freq >= 4900 -> "5GHz"
            freq in 2400..2500 -> "2.4GHz"
            else -> "未知"
        }
        return Triple(band, info.linkSpeed, info.rssi)
    }

    private fun socName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val maker = Build.SOC_MANUFACTURER
            val model = Build.SOC_MODEL
            if (!maker.isNullOrBlank() || !model.isNullOrBlank()) {
                return listOf(maker, model)
                    .filter { !it.isNullOrBlank() }
                    .joinToString(" ")
            }
        }
        return Build.HARDWARE
    }

    private fun capOf(mime: String): CodecCap? {
        val infos = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        }.getOrDefault(emptyArray())
        val candidates = infos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        if (candidates.isEmpty()) return null
        val picked = candidates.firstOrNull { Codecs.isHardware(it) } ?: candidates.first()
        val video = runCatching {
            picked.getCapabilitiesForType(mime).videoCapabilities
        }.getOrNull() ?: return CodecCap(picked.name, Codecs.isHardware(picked), 0, 0, 0)

        val maxWidth = runCatching { video.supportedWidths.upper }.getOrDefault(0)
        val maxHeight = runCatching { video.supportedHeights.upper }.getOrDefault(0)
        val probeWidth = if (maxWidth > 0) minOf(maxWidth, 1920) else 1920
        val probeHeight = if (maxHeight > 0) minOf(maxHeight, 1080) else 1080
        val maxFps = runCatching {
            video.getSupportedFrameRatesFor(probeWidth, probeHeight).upper.toInt()
        }.getOrDefault(30)
        return CodecCap(picked.name, Codecs.isHardware(picked), maxWidth, maxHeight, maxFps)
    }
}