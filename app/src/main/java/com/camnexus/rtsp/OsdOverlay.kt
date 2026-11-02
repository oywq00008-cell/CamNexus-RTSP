package com.camnexus.rtsp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把日期 / 电量 / 电量图标组合成一张与视频同尺寸的透明位图，交给 GL 滤镜叠加。
 *
 * 旋转、翻转、四角定位都在这里用 Canvas 完成，shader 只做一次 alpha 混合。
 */
object OsdOverlay {

    val DATE_FORMATS = arrayOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss",
        "MM/dd HH:mm:ss",
        "HH:mm:ss"
    )

    private const val PADDING_RATIO = 0.025f
    private const val TEXT_RATIO = 0.0224f

    /** 读一次当前电量。 */
    fun battery(context: Context): Pair<Int, Boolean> {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return 0 to false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (scale > 0 && level >= 0) level * 100 / scale else 0
        return pct.coerceIn(0, 100) to charging
    }

    fun iconFor(level: Int, charging: Boolean): Int = when {
        charging -> R.drawable.ic_battery_charging
        level >= 95 -> R.drawable.ic_battery_full
        level >= 80 -> R.drawable.ic_battery_5
        level >= 60 -> R.drawable.ic_battery_4
        level >= 40 -> R.drawable.ic_battery_3
        level >= 20 -> R.drawable.ic_battery_2
        else -> R.drawable.ic_battery_1
    }

    /**
     * 把叠加内容画进 [target]（会先清空）。返回 false 表示当前没有任何要显示的内容。
     */
    fun draw(
        context: Context,
        config: StreamConfig,
        target: Bitmap,
        batteryLevel: Int,
        charging: Boolean
    ): Boolean {
        val width = target.width
        val height = target.height
        val canvas = Canvas(target)
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        if (!config.osdDate && !config.osdBattery) return false

        // 用长边算字号：横屏时按高度算会明显偏小，长边能保证横竖屏观感一致
        val longSide = maxOf(width, height)
        val textSize = (longSide * TEXT_RATIO).coerceAtLeast(14f)
        val block = buildBlock(context, config, textSize, batteryLevel, charging) ?: return false

        val margin = height * PADDING_RATIO
        // 旋转 90/270 时宽高互换，按旋转后的外框贴边，避免转完跑到画面外
        val rotated = config.osdRotation == 90 || config.osdRotation == 270
        val boxWidth = if (rotated) block.height else block.width
        val boxHeight = if (rotated) block.width else block.height
        val left = if (config.osdPosition % 2 == 0) margin else width - margin - boxWidth
        val top = if (config.osdPosition < 2) margin else height - margin - boxHeight
        val cx = left + boxWidth / 2f
        val cy = top + boxHeight / 2f

        canvas.save()
        // 绕旋转后外框的中心旋转 / 翻转
        canvas.rotate(config.osdRotation.toFloat(), cx, cy)
        val flipH = config.osdFlip == 1 || config.osdFlip == 3
        val flipV = config.osdFlip == 2 || config.osdFlip == 3
        if (flipH || flipV) {
            canvas.scale(if (flipH) -1f else 1f, if (flipV) -1f else 1f, cx, cy)
        }
        canvas.drawBitmap(block, cx - block.width / 2f, cy - block.height / 2f, null)
        canvas.restore()
        block.recycle()
        return true
    }

    /** 生成一行 "日期  电量图标 85%"。 */
    private fun buildBlock(
        context: Context,
        config: StreamConfig,
        textSize: Float,
        batteryLevel: Int,
        charging: Boolean
    ): Bitmap? {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            isFakeBoldText = true
            setShadowLayer(textSize * 0.09f, 0f, textSize * 0.03f, 0xB0000000.toInt())
        }

        val date = if (config.osdDate) {
            runCatching {
                SimpleDateFormat(DATE_FORMATS[config.osdDateFormat.coerceIn(0, DATE_FORMATS.size - 1)], Locale.getDefault())
                    .format(Date())
            }.getOrDefault("")
        } else {
            ""
        }
        val batteryText = if (config.osdBattery) "$batteryLevel%" else ""

        val gap = textSize * 0.4f
        // 字号缩小后图标单独放大：比旧值（1.15×旧字号）大约 10%
        val iconSize = textSize * 1.8f
        val iconGap = if (config.osdBatteryIcon && config.osdBattery) textSize * 0.2f else 0f

        val dateWidth = if (date.isNotEmpty()) paint.measureText(date) else 0f
        val battWidth = if (batteryText.isNotEmpty()) paint.measureText(batteryText) else 0f
        val iconWidth = if (config.osdBatteryIcon && config.osdBattery) iconSize else 0f

        var totalWidth = dateWidth + battWidth + iconWidth
        if (date.isNotEmpty() && (battWidth > 0f || iconWidth > 0f)) totalWidth += gap
        if (iconWidth > 0f && battWidth > 0f) totalWidth += iconGap
        if (totalWidth <= 0f) return null

        val pad = textSize * 0.25f
        val blockWidth = (totalWidth + pad * 2).toInt().coerceAtLeast(1)
        val blockHeight = (textSize * 1.6f + pad * 2).toInt().coerceAtLeast(1)
        val block = Bitmap.createBitmap(blockWidth, blockHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(block)

        val baseline = pad + textSize
        var cursor = pad

        if (date.isNotEmpty()) {
            canvas.drawText(date, cursor, baseline, paint)
            cursor += dateWidth + gap
        }
        if (config.osdBattery) {
            if (config.osdBatteryIcon) {
                val icon = ContextCompat.getDrawable(context, iconFor(batteryLevel, charging))
                if (icon != null) {
                    icon.setTint(Color.WHITE)
                    // 图标中心对齐文字的视觉中心（含 ascent/descent），避免看起来偏下
                    val fm = paint.fontMetrics
                    val textCenterY = baseline + (fm.ascent + fm.descent) / 2f
                    val top = (textCenterY - iconSize / 2f).toInt()
                    icon.setBounds(cursor.toInt(), top, (cursor + iconSize).toInt(), top + iconSize.toInt())
                    icon.draw(canvas)
                }
                cursor += iconSize + iconGap
            }
            canvas.drawText(batteryText, cursor, baseline, paint)
        }
        return block
    }
}
