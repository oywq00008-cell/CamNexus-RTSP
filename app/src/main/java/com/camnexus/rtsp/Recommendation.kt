package com.camnexus.rtsp

import android.content.Context
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

    fun build(context: Context, current: StreamConfig, profile: DeviceProfile, sizes: List<Size>): Recommendation {
        val reasons = mutableListOf<String>()
        val hwEncoder = profile.h264?.hardware == true

        // 1. 编码格式：H.265 只在有硬件编码器时保留
        val codec = when {
            current.codec == StreamConfig.CODEC_H265 && profile.h265?.hardware == true ->
                StreamConfig.CODEC_H265
            current.codec == StreamConfig.CODEC_H265 -> {
                reasons += context.getString(R.string.rec_no_hw_h265)
                StreamConfig.CODEC_H264
            }
            else -> StreamConfig.CODEC_H264
        }
        if (codec == StreamConfig.CODEC_H265) {
            reasons += context.getString(R.string.rec_hw_h265_ok)
        }

        // 2. 编码器与内存
        when {
            profile.h264 == null -> reasons += context.getString(R.string.rec_no_h264)
            hwEncoder -> reasons += context.getString(R.string.rec_hw_encoder, profile.h264.name)
            else -> reasons += context.getString(R.string.rec_sw_encoder, profile.h264.name)
        }
        val ramGb = String.format(Locale.US, "%.1f", profile.ramMb / 1024.0)
        when {
            profile.lowRam -> reasons += context.getString(R.string.rec_low_ram, ramGb)
            hwEncoder && profile.ramMb >= 4096 -> reasons += context.getString(R.string.rec_ram_ok, ramGb)
            else -> reasons += context.getString(R.string.rec_ram, ramGb)
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
            size.width >= 1920 -> context.getString(R.string.rec_1080p)
            size.width >= 1280 -> context.getString(R.string.rec_720p)
            else -> context.getString(R.string.rec_custom, size.width, size.height)
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

        reasons += context.getString(
            R.string.rec_network,
            profile.wifiBand,
            profile.wifiLinkMbps,
            profile.wifiRssi,
            String.format(Locale.US, "%.2f", netFactor)
        )

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