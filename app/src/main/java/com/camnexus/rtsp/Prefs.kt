package com.camnexus.rtsp

import android.content.Context
import android.content.SharedPreferences

/**
 * 推流与网络参数。所有字段都能在设置界面修改并持久化。
 */
data class StreamConfig(
    val cameraId: String = "0",
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitrate: Int = 2_000_000,
    val codec: String = CODEC_H264,
    val port: Int = DEFAULT_PORT,
    val authEnabled: Boolean = true,
    val username: String = DEFAULT_USER,
    val password: String = DEFAULT_PASSWORD,
    val powerSaving: Boolean = false,
    val encoder: String = ENCODER_AUTO
) {
    val resolutionText: String get() = "${width}x$height"
    val codecLabel: String get() = if (codec == CODEC_H265) "H.265" else "H.264"

    companion object {
        const val CODEC_H264 = "H264"
        const val CODEC_H265 = "H265"
        const val DEFAULT_PORT = 8554
        const val DEFAULT_USER = "camnexus"
        const val DEFAULT_PASSWORD = "camnexus"
        const val ENCODER_AUTO = "auto"
        const val ENCODER_HARDWARE = "hardware"
        const val ENCODER_SOFTWARE = "software"
    }
}

object Prefs {

    private const val NAME = "camnexus"
    private const val KEY_CAMERA = "cameraId"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_FPS = "fps"
    private const val KEY_BITRATE = "bitrate"
    private const val KEY_CODEC = "codec"
    private const val KEY_PORT = "port"
    private const val KEY_AUTH = "authEnabled"
    private const val KEY_USER = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_POWER = "powerSaving"
    private const val KEY_ENCODER = "encoder"

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(context: Context): StreamConfig {
        val p = sp(context)
        return StreamConfig(
            cameraId = p.getString(KEY_CAMERA, "0") ?: "0",
            width = p.getInt(KEY_WIDTH, 1280),
            height = p.getInt(KEY_HEIGHT, 720),
            fps = p.getInt(KEY_FPS, 30),
            bitrate = p.getInt(KEY_BITRATE, 2_000_000),
            codec = p.getString(KEY_CODEC, StreamConfig.CODEC_H264) ?: StreamConfig.CODEC_H264,
            port = p.getInt(KEY_PORT, StreamConfig.DEFAULT_PORT),
            authEnabled = p.getBoolean(KEY_AUTH, true),
            username = p.getString(KEY_USER, StreamConfig.DEFAULT_USER) ?: StreamConfig.DEFAULT_USER,
            password = p.getString(KEY_PASSWORD, StreamConfig.DEFAULT_PASSWORD)
                ?: StreamConfig.DEFAULT_PASSWORD,
            powerSaving = p.getBoolean(KEY_POWER, false),
            encoder = p.getString(KEY_ENCODER, StreamConfig.ENCODER_AUTO) ?: StreamConfig.ENCODER_AUTO
        )
    }

    fun save(context: Context, config: StreamConfig) {
        sp(context).edit()
            .putString(KEY_CAMERA, config.cameraId)
            .putInt(KEY_WIDTH, config.width)
            .putInt(KEY_HEIGHT, config.height)
            .putInt(KEY_FPS, config.fps)
            .putInt(KEY_BITRATE, config.bitrate)
            .putString(KEY_CODEC, config.codec)
            .putInt(KEY_PORT, config.port)
            .putBoolean(KEY_AUTH, config.authEnabled)
            .putString(KEY_USER, config.username)
            .putString(KEY_PASSWORD, config.password)
            .putBoolean(KEY_POWER, config.powerSaving)
            .putString(KEY_ENCODER, config.encoder)
            .apply()
    }

    /** 只改部分字段时用：读出来、改掉、再存回。 */
    fun update(context: Context, block: (StreamConfig) -> StreamConfig): StreamConfig {
        val next = block(load(context))
        save(context, next)
        return next
    }

    private const val KEY_ONBOARDED = "onboarded"

    fun isOnboarded(context: Context): Boolean = sp(context).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_ONBOARDED, value).apply()
    }
}