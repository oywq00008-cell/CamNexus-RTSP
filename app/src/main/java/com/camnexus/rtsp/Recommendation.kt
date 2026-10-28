package com.camnexus.rtsp

import android.util.Size
import java.util.Locale

data class Recommendation(
    val config: StreamConfig,
    val reasons: List<String>
)

/**
 * 根据设备能力给出一组推荐参数。纯函数，方便单测。
 * 判定顺序：先定编码格式（决定码率系数），再定分辨率，最后算码率。
 */
object Recommender {

    fun build(current: StreamConfig, profile: DeviceProfile, sizes: List<Size>): Recommendation {
        val reasons = mutableListOf<String>()
        val hwEncoder = profile.h264?.hardware == true

        // 1. 编码格式：H.265 只在有硬件编码器时保留
        val codec = when {
            current.codec == StreamConfig.CODEC_H265 && profile.h265?.hardware == true ->
                StreamConfig.CODEC_H265
            current.codec == StreamConfig.CODEC_H265 -> {
                reasons += "本机没有硬件 H.265 编码器，已改回 H.264"
                StreamConfig.CODEC_H264
            }
            else -> StreamConfig.CODEC_H264
        }
        if (codec == StreamConfig.CODEC_H265) {
            reasons += "H.265 硬编可用，同画质体积约省 30%"
        }

        // 2. 编码器与内存
        when {
            profile.h264 == null -> reasons += "没有找到 H.264 编码器，只能尝试软件编码"
            hwEncoder -> reasons += "检测到硬件编码器（${profile.h264.name}）"
            else -> reasons += "只有软件编码器（${profile.h264.name}），分辨率压在 720p 以内"
        }
        val ramGb = String.format(Locale.US, "%.1f", profile.ramMb / 1024.0)
        when {
            profile.lowRam -> reasons += "内存约 $ramGb GB，属于低内存设备"
            hwEncoder && profile.ramMb >= 4096 -> reasons += "内存约 $ramGb GB，足够跑 1080p"
            else -> reasons += "内存约 $ramGb GB"
        }

        // 3. 分辨率：相机 / 编码器 / 内存三道约束取最小
        val codecMaxWidth = profile.h264?.maxWidth ?: 0
        val limit = listOf(
            profile.cameraMaxSize.width,
            if (codecMaxWidth > 0) codecMaxWidth else 1920,
            if (hwEncoder && !profile.lowRam && profile.ramMb >= 4096) 1920 else 1280,
            1920
        ).minOrNull() ?: 1280

        val candidates = sizes.filter { it.width <= limit && it.height <= limit }
        val size = candidates.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(1280, 720)

        reasons += when {
            size.width >= 1920 -> "推荐 1080p：编码器和内存都够用"
            size.width >= 1280 -> "推荐 720p：兼顾画质与稳定性"
            else -> "推荐 ${size.width}x${size.height}：更高分辨率在这台设备上不稳"
        }

        // 4. 帧率：相机上限、编码器上限、30 取最小
        val cameraFps = profile.cameraMaxFps.coerceAtLeast(1)
        val codecFps = profile.h264?.maxFps?.takeIf { it > 0 } ?: 30
        val fps = listOf(cameraFps, codecFps, 30).minOrNull() ?: 30

        // 5. 码率：分辨率基线 × 网络系数 × 编码系数（用最终编码格式算）
        val base = when {
            size.width >= 1920 -> 4_500_000
            size.width >= 1280 -> 2_000_000
            else -> 1_000_000
        }
        val netFactor = when {
            profile.wifiBand == "5GHz" && profile.wifiLinkMbps >= 400 -> 1.0
            profile.wifiBand == "5GHz" -> 0.85
            profile.wifiLinkMbps >= 100 -> 0.7
            else -> 0.5
        }
        val codecFactor = if (codec == StreamConfig.CODEC_H265) 0.7 else 1.0
        val bitrate = ((base * netFactor * codecFactor) / 100_000).toInt() * 100_000

        reasons += "网络：${profile.wifiBand}，链路 ${profile.wifiLinkMbps}Mbps，" +
            "信号 ${profile.wifiRssi}dBm → 码率系数 ${String.format(Locale.US, "%.2f", netFactor)}"

        val config = current.copy(
            width = size.width,
            height = size.height,
            fps = fps,
            bitrate = bitrate.coerceIn(500_000, 10_000_000),
            codec = codec,
            encoder = if (hwEncoder) StreamConfig.ENCODER_AUTO else StreamConfig.ENCODER_SOFTWARE
        )
        return Recommendation(config, reasons)
    }
}