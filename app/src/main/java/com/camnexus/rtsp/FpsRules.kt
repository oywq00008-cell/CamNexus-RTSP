package com.camnexus.rtsp

/**
 * 帧率的"配置值 vs 实际值"规则集中在这里，避免各处各写一遍导致显示不一致。
 *
 * - 增强夜景：为了长曝光，编码帧率会被压到 [STRONG_NIGHT_FPS]
 * - 省电降档：无人观看一段时间后降到 [LOW_POWER_FPS]
 */
object FpsRules {

    const val STRONG_NIGHT_FPS = 15
    const val LOW_POWER_FPS = 10

    /** 当前配置下预期会使用的帧率。 */
    fun effective(config: StreamConfig, lowPower: Boolean = false): Int = when {
        lowPower -> LOW_POWER_FPS
        config.nightMode == StreamConfig.NIGHT_STRONG && config.fps > STRONG_NIGHT_FPS ->
            STRONG_NIGHT_FPS
        else -> config.fps
    }

    /** 设置项上显示的文案：配置值，必要时附上实际值。 */
    fun label(config: StreamConfig, lowPower: Boolean, format: String): String {
        val actual = effective(config, lowPower)
        return if (actual != config.fps) String.format(format, config.fps, actual)
        else "${config.fps} fps"
    }
}
