package com.camnexus.rtsp

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import kotlin.math.hypot
import kotlin.math.roundToInt

data class CameraEntry(val id: String, val label: String)

/**
 * 摄像头能力查询。
 *
 * 逻辑多摄会把同一颗物理传感器暴露成多个 cameraId（例如 id 0 与 id 2 是同一颗主摄），
 * 直接列出来会让用户看到重复项。这里用「朝向 + 传感器尺寸 + 焦距」做指纹去重，
 * 并按等效焦距给出超广角/主摄/长焦的称呼，比裸 ID 更容易辨认。
 */
object CameraCaps {

    private const val DEFAULT_FPS = 30
    private const val MAX_FPS = 60
    private const val FULL_FRAME_DIAGONAL_MM = 43.27f
    private val CANDIDATE_FPS = listOf(15, 20, 24, 25, 30, 50, 60)

    private fun manager(context: Context): CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    /** LENS_FACING：0=前置，1=后置，2=外接。 */
    fun facing(context: Context, cameraId: String): Int? {
        val cm = manager(context) ?: return null
        val chars = runCatching { cm.getCameraCharacteristics(cameraId) }.getOrNull()
            ?: return null
        return chars.get(CameraCharacteristics.LENS_FACING)
    }

    fun cameras(context: Context): List<CameraEntry> {
        val cm = manager(context) ?: return emptyList()
        data class Raw(
            val id: String,
            val facing: Int,
            val equivalent: Float,
            val signature: String
        )

        val raws = mutableListOf<Raw>()
        for (id in cm.cameraIdList) {
            val chars = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            // 景深/TOF 这类相机不支持普通预览，开了也出不了画面，直接跳过
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val backwardCompatible = capabilities?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
            ) ?: false
            if (!backwardCompatible) continue

            val facing = chars.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK
            val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val focal = focals?.firstOrNull() ?: 0f
            val diagonal = if (sensor != null) hypot(sensor.width, sensor.height) else 0f
            val equivalent = if (diagonal > 0f && focal > 0f) {
                focal * FULL_FRAME_DIAGONAL_MM / diagonal
            } else {
                0f
            }

            // 指纹只取"物理上决定是哪颗镜头"的信息，忽略 id 与像素阵列
            val signature = buildString {
                append(facing)
                append('|')
                append(sensor?.width?.roundToInt() ?: -1)
                append('x')
                append(sensor?.height?.roundToInt() ?: -1)
                append('|')
                append(focals?.joinToString(",") { String.format(java.util.Locale.US, "%.2f", it) } ?: "?")
            }
            raws.add(Raw(id, facing, equivalent, signature))
        }

        // 同一颗传感器只保留 id 最小的那个
        val unique = LinkedHashMap<String, Raw>()
        raws.sortedBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }.forEach { raw ->
            if (!unique.containsKey(raw.signature)) unique[raw.signature] = raw
        }

        return unique.values
            .sortedWith(
                compareBy(
                    { if (it.facing == CameraCharacteristics.LENS_FACING_BACK) 0 else 1 },
                    { if (it.equivalent > 0f) it.equivalent else 999f }
                )
            )
            .map { CameraEntry(it.id, labelOf(it.facing, it.equivalent, it.id)) }
    }

    private fun labelOf(facing: Int, equivalent: Float, id: String): String {
        val facingText = when (facing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "前置"
            CameraCharacteristics.LENS_FACING_BACK -> "后置"
            else -> "外接"
        }
        if (equivalent <= 0f) return "$facingText（ID $id）"
        val lens = when {
            equivalent < 20f -> "超广角"
            equivalent <= 40f -> "主摄"
            equivalent <= 90f -> "长焦"
            else -> "超长焦"
        }
        return "$facingText · $lens ${equivalent.roundToInt()}mm（ID $id）"
    }

    /**
     * 摄像头能输出的、且适合视频编码的尺寸。
     * 相机数据经 SurfaceTexture 交给 OpenGL 再进编码器，所以查 SurfaceTexture 的输出尺寸。
     */
    fun resolutions(context: Context, cameraId: String): List<Size> {
        val cm = manager(context) ?: return emptyList()
        val chars = runCatching { cm.getCameraCharacteristics(cameraId) }.getOrNull()
            ?: return emptyList()
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
        val sizes = runCatching { map.getOutputSizes(SurfaceTexture::class.java) }
            .getOrNull() ?: return emptyList()
        return sizes
            .asSequence()
            .filter { it.width >= 320 && it.height >= 240 }
            .filter { it.width <= 3840 && it.height <= 2160 }
            .filter { it.width % 2 == 0 && it.height % 2 == 0 }
            .filter { isCommonAspect(it.width, it.height) }
            .distinct()
            .sortedByDescending { it.width * it.height }
            .toList()
    }

    /**
     * 该摄像头在该尺寸下能跑的最高帧率（由 minFrameDuration 反算）。
     */
    fun maxFps(context: Context, cameraId: String, width: Int, height: Int): Int {
        val cm = manager(context) ?: return DEFAULT_FPS
        val chars = runCatching { cm.getCameraCharacteristics(cameraId) }.getOrNull()
            ?: return DEFAULT_FPS
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return DEFAULT_FPS
        val durationNanos = runCatching {
            map.getOutputMinFrameDuration(SurfaceTexture::class.java, Size(width, height))
        }.getOrDefault(0L)
        if (durationNanos <= 0L) return DEFAULT_FPS
        return (1_000_000_000L / durationNanos).toInt().coerceIn(1, 120)
    }

    /** 帧率档位：只给出该摄像头在该尺寸下真正支持的。 */
    fun fpsOptions(context: Context, cameraId: String, width: Int, height: Int): List<Int> {
        val max = maxFps(context, cameraId, width, height).coerceAtMost(MAX_FPS)
        val supported = CANDIDATE_FPS.filter { it <= max }
        return supported.ifEmpty { listOf(max.coerceAtLeast(1)) }
    }

    private fun isCommonAspect(width: Int, height: Int): Boolean {
        val longSide = maxOf(width, height).toDouble()
        val shortSide = minOf(width, height).toDouble()
        if (shortSide == 0.0) return false
        val ratio = longSide / shortSide
        return kotlin.math.abs(ratio - 16.0 / 9.0) < 0.03 ||
            kotlin.math.abs(ratio - 4.0 / 3.0) < 0.03
    }
}