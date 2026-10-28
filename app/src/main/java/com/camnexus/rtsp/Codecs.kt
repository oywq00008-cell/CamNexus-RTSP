package com.camnexus.rtsp

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * 查询本机可用的视频编码器，用来：
 * 1. 告诉用户当前实际会走硬件还是软件编码；
 * 2. 判断某编码格式（H.265）本机是否支持，避免选了以后启动失败。
 */
object Codecs {

    const val MIME_H264 = "video/avc"
    const val MIME_H265 = "video/hevc"

    data class Info(val name: String, val hardware: Boolean) {
        val label: String get() = if (hardware) "$name（硬件）" else "$name（软件）"
    }

    fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase()
            !name.startsWith("omx.google") && !name.startsWith("c2.android")
        }

    fun find(mime: String, preferHardware: Boolean): Info? {
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
            .getOrDefault(emptyArray())
        val candidates = infos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        if (candidates.isEmpty()) return null
        // 强制软件时要真的挑一个软件编码器，否则列表第一个通常是硬件
        val picked = if (preferHardware) {
            candidates.firstOrNull { isHardware(it) } ?: candidates.first()
        } else {
            candidates.firstOrNull { !isHardware(it) } ?: candidates.first()
        }
        return Info(picked.name, isHardware(picked))
    }

    fun has(mime: String): Boolean = find(mime, preferHardware = true) != null
}