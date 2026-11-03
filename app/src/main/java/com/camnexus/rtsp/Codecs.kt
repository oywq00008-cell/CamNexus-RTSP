package com.camnexus.rtsp

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * 查询本机可用的视频编码器，用来：
 * 1. 告诉用户当前实际会走硬件还是软件编码；
 * 2. 判断某编码格式（H.265）本机是否支持，避免选了以后启动失败；
 * 3. 按「实际可达帧率」筛选分辨率，避免 HAL 虚标 4K。
 */
object Codecs {

    const val MIME_H264 = "video/avc"
    const val MIME_H265 = "video/hevc"

    data class Info(val name: String, val hardware: Boolean) {
        fun label(context: Context): String =
            name + context.getString(if (hardware) R.string.encoder_hw_suffix else R.string.encoder_sw_suffix)
    }

    fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase()
            !name.startsWith("omx.google") && !name.startsWith("c2.android")
        }

    private fun pick(mime: String, preferHardware: Boolean): MediaCodecInfo? {
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
            .getOrDefault(emptyArray())
        val candidates = infos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        if (candidates.isEmpty()) return null
        return if (preferHardware) {
            candidates.firstOrNull { isHardware(it) } ?: candidates.first()
        } else {
            candidates.firstOrNull { !isHardware(it) } ?: candidates.first()
        }
    }

    fun find(mime: String, preferHardware: Boolean): Info? {
        val picked = pick(mime, preferHardware) ?: return null
        return Info(picked.name, isHardware(picked))
    }

    /**
     * 该编码器在该尺寸下的「实际可达帧率」。
     *
     * Android 10+ 用 getAchievableFrameRatesFor（厂商会按真实性能上报）；
     * 更低版本退回理论值 getSupportedFrameRatesFor。
     * 返回 0 表示无法判断。
     */
    fun achievableFps(mime: String, preferHardware: Boolean, width: Int, height: Int): Int {
        val codec = pick(mime, preferHardware) ?: return 0
        val caps = runCatching { codec.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: return 0
        val upper = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                caps.getAchievableFrameRatesFor(width, height)?.upper
            } else {
                caps.getSupportedFrameRatesFor(width, height)?.upper
            }
        }.getOrNull() ?: return 0
        return upper.toInt()
    }

    fun has(mime: String): Boolean = find(mime, preferHardware = true) != null
}
