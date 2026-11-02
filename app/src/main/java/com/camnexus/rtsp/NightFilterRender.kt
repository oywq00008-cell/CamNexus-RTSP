package com.camnexus.rtsp

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 夜视曲线滤镜。
 *
 * 库自带的 BrightnessFilter 是加法（rgb + b），会把黑位整体抬成灰；
 * GammaFilter 单独用又会把暗部噪点一起提亮、丢掉对比。
 * 这里做一条完整的色调曲线：
 *
 *   1. pow(rgb, gamma)      提亮阴影（gamma < 1）
 *   2. 减去黑位并重新归一化   把被提起来的底噪压回黑，避免"灰蒙蒙"
 *   3. 对比度 S 形回拉       恢复层次
 *
 * 三步都在 GPU 上完成，单次采样，开销与一个普通滤镜相当。
 */
class NightFilterRender : BaseFilterRender() {

    private var program = -1
    private var aPositionHandle = -1
    private var aTextureHandle = -1
    private var uMVPMatrixHandle = -1
    private var uSTMatrixHandle = -1
    private var uSamplerHandle = -1
    private var uGammaHandle = -1
    private var uBlackHandle = -1
    private var uContrastHandle = -1
    private var uDenoiseHandle = -1
    private var uDetailHandle = -1
    private var uTexelHandle = -1

    /** pow 的指数，越小越亮。1.0 = 不变。 */
    var gamma: Float = 0.72f
        set(value) { field = value.coerceIn(0.2f, 2.0f) }

    /** 黑位，0 表示不移除；0.15 表示把 15% 以下的亮度压回纯黑。 */
    var black: Float = 0.10f
        set(value) { field = value.coerceIn(0f, 0.45f) }

    /** 对比度，1.0 = 不变。 */
    var contrast: Float = 1.05f
        set(value) { field = value.coerceIn(0.5f, 2.0f) }

    /** 空间降噪强度，0 = 关闭，1 = 最强。 */
    var denoise: Float = 0.0f
        set(value) { field = value.coerceIn(0f, 1f) }

    /** 细节回灌比例：0 = 全平滑，1 = 完全保留原细节。 */
    var detail: Float = 0.35f
        set(value) { field = value.coerceIn(0f, 1f) }

    private var texelW = 1f / 1920f
    private var texelH = 1f / 1080f

    override fun setPreviewSize(width: Int, height: Int) {
        super.setPreviewSize(width, height)
        if (width > 0 && height > 0) {
            texelW = 1f / width
            texelH = 1f / height
        }
    }

    /**
     * 显式指定源尺寸。库不会调用 setPreviewSize，必须由业务方传入，
     * 否则采样步长会用默认值；内容为竖屏时横纵会颠倒，降噪核被拉长。
     */
    fun setSourceSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            texelW = 1f / width
            texelH = 1f / height
        }
    }

    // 基类不会自动准备顶点缓冲，必须由子类填充（与库内滤镜一致）
    private val squareVertexData = floatArrayOf(
        -1f, -1f, 0f, 0f, 0f,
        1f, -1f, 0f, 1f, 0f,
        -1f, 1f, 0f, 0f, 1f,
        1f, 1f, 0f, 1f, 1f
    )

    init {
        squareVertex = ByteBuffer.allocateDirect(squareVertexData.size * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        squareVertex.put(squareVertexData).position(0)
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)
    }

    private val vertex = """
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        varying vec2 vTextureCoord;
        void main() {
          gl_Position = uMVPMatrix * aPosition;
          vTextureCoord = (uSTMatrix * aTextureCoord).xy;
        }
    """.trimIndent()

    private val fragment = """
        precision mediump float;
        uniform sampler2D uSampler;
        uniform float uGamma;
        uniform float uBlack;
        uniform float uContrast;
        uniform float uDenoise;
        uniform float uDetail;
        uniform vec2 uTexel;
        varying vec2 vTextureCoord;

        const vec3 LUMA = vec3(0.299, 0.587, 0.114);

        void main() {
          vec3 color = texture2D(uSampler, vTextureCoord).rgb;

          if (uDenoise > 0.001) {
            float y0 = dot(color, LUMA);
            vec3 c0 = color - vec3(y0);

            // 亮度保边（权重严）、色度平滑（权重松）；色噪主要在色度
            float sensL = mix(120.0, 40.0, uDenoise);
            float sensC = mix(20.0, 3.0, uDenoise);

            vec3 sum = color;
            float weightSum = 1.0;
            for (int dy = -2; dy <= 2; dy++) {
              for (int dx = -2; dx <= 2; dx++) {
                if (dx == 0 && dy == 0) continue;
                vec2 offset = vec2(float(dx), float(dy)) * uTexel * 1.5;
                vec3 s = texture2D(uSampler, vTextureCoord + offset).rgb;
                float sy = dot(s, LUMA);
                vec3 sc = s - vec3(sy);
                float dY = sy - y0;
                float dC = distance(sc, c0);
                float w = exp(-(dY * dY * sensL + dC * dC * sensC))
                        * exp(-float(dx * dx + dy * dy) * 0.18)
                        * uDenoise;
                sum += s * w;
                weightSum += w;
              }
            }
            vec3 base = sum / weightSum;

            // 细节回灌：只把亮度高频加回去，色度保持降噪后的干净结果
            float detailY = dot(color - base, LUMA);
            color = base + vec3(detailY * uDetail);
          }

          // 只提暗部：用亮度遮罩保住已经够亮的地方，避免整体过曝
          float luma = dot(color, LUMA);
          float keep = smoothstep(0.20, 0.70, luma);   // 0=暗部, 1=亮部
          vec3 lifted = pow(max(color, vec3(0.0)), vec3(uGamma));
          vec3 c = mix(lifted, color, keep);

          // 黑位锚定 + 对比度
          c = clamp((c - vec3(uBlack)) / max(1.0 - uBlack, 0.001), 0.0, 1.0);
          c = clamp((c - vec3(0.5)) * uContrast + vec3(0.5), 0.0, 1.0);
          gl_FragColor = vec4(c, 1.0);
        }
    """.trimIndent()

    override fun initGlFilter(context: Context) {
        program = GlUtil.createProgram(vertex, fragment)
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uSamplerHandle = GLES20.glGetUniformLocation(program, "uSampler")
        uGammaHandle = GLES20.glGetUniformLocation(program, "uGamma")
        uBlackHandle = GLES20.glGetUniformLocation(program, "uBlack")
        uContrastHandle = GLES20.glGetUniformLocation(program, "uContrast")
        uDenoiseHandle = GLES20.glGetUniformLocation(program, "uDenoise")
        uDetailHandle = GLES20.glGetUniformLocation(program, "uDetail")
        uTexelHandle = GLES20.glGetUniformLocation(program, "uTexel")
    }

    override fun drawFilter() {
        GLES20.glUseProgram(program)

        squareVertex.position(SQUARE_VERTEX_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(
            aPositionHandle, 3, GLES20.GL_FLOAT, false,
            SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex
        )
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        squareVertex.position(SQUARE_VERTEX_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(
            aTextureHandle, 2, GLES20.GL_FLOAT, false,
            SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex
        )
        GLES20.glEnableVertexAttribArray(aTextureHandle)

        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, MVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, STMatrix, 0)
        GLES20.glUniform1f(uGammaHandle, gamma)
        GLES20.glUniform1f(uBlackHandle, black)
        GLES20.glUniform1f(uContrastHandle, contrast)
        GLES20.glUniform1f(uDenoiseHandle, denoise)
        GLES20.glUniform1f(uDetailHandle, detail)
        GLES20.glUniform2f(uTexelHandle, texelW, texelH)

        GLES20.glUniform1i(uSamplerHandle, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
    }

    override fun disableResources() {
        GlUtil.disableResources(aTextureHandle, aPositionHandle)
    }

    override fun release() {
        GLES20.glDeleteProgram(program)
    }
}
