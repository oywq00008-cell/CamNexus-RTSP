package com.camnexus.rtsp

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.Matrix
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把一张与视频同尺寸的透明位图（OSD）叠加到画面上。
 *
 * 位图由 [OsdOverlay] 在后台线程生成，本滤镜只在 GL 线程上传纹理并做一次 alpha 混合。
 */
class OsdFilterRender : BaseFilterRender() {

    @Volatile
    private var pending: Bitmap? = null

    @Volatile
    private var generation = 0

    @Volatile
    private var visible = false

    private var uploadedGeneration = -1
    private var textureId = 0
    private var textureWidth = 0
    private var textureHeight = 0
    private var hasTexture = false
    private var uploadBuffer: ByteBuffer? = null

    private var program = -1
    private var aPositionHandle = -1
    private var aTextureHandle = -1
    private var uMVPMatrixHandle = -1
    private var uSTMatrixHandle = -1
    private var uSamplerHandle = -1
    private var uOsdHandle = -1
    private var uHasOsdHandle = -1

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

    /** 由业务线程调用；同一张位图可以被重复使用，靠 generation 判断是否需要重新上传。 */
    fun update(bitmap: Bitmap?) {
        pending = bitmap
        visible = bitmap != null
        generation++
    }

    private val vertex = """
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        varying vec2 vTextureCoord;
        varying vec2 vOsdCoord;
        void main() {
          gl_Position = uMVPMatrix * aPosition;
          vTextureCoord = (uSTMatrix * aTextureCoord).xy;
          vOsdCoord = aTextureCoord.xy;
        }
    """.trimIndent()

    private val fragment = """
        precision mediump float;
        uniform sampler2D uSampler;
        uniform sampler2D uOsd;
        uniform float uHasOsd;
        varying vec2 vTextureCoord;
        varying vec2 vOsdCoord;
        void main() {
          vec4 base = texture2D(uSampler, vTextureCoord);
          if (uHasOsd > 0.5) {
            // Canvas 位图行序自上而下，GL 纹理自下而上；同时与采集纹理的镜像方向相反，因此 xy 都翻转
            vec2 osdUv = vec2(vOsdCoord.x, 1.0 - vOsdCoord.y);
            vec4 osd = texture2D(uOsd, osdUv);
            base.rgb = mix(base.rgb, osd.rgb, osd.a);
          }
          gl_FragColor = vec4(base.rgb, 1.0);
        }
    """.trimIndent()

    override fun initGlFilter(context: Context) {
        program = GlUtil.createProgram(vertex, fragment)
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uSamplerHandle = GLES20.glGetUniformLocation(program, "uSampler")
        uOsdHandle = GLES20.glGetUniformLocation(program, "uOsd")
        uHasOsdHandle = GLES20.glGetUniformLocation(program, "uHasOsd")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun uploadIfNeeded() {
        if (generation == uploadedGeneration) return
        val bitmap = pending ?: return
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return

        // 分辨率切换时业务线程可能刚回收旧位图，这里必须容错，
        // 否则 GL 线程异常会直接杀掉推流进程。
        val ok = runCatching {
            val size = bitmap.byteCount
            val buffer = uploadBuffer?.takeIf { it.capacity() >= size } ?: ByteBuffer
                .allocateDirect(size)
                .order(ByteOrder.nativeOrder())
                .also { uploadBuffer = it }
            buffer.clear()
            bitmap.copyPixelsToBuffer(buffer)
            buffer.position(0)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            if (bitmap.width != textureWidth || bitmap.height != textureHeight) {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    bitmap.width, bitmap.height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
                )
                textureWidth = bitmap.width
                textureHeight = bitmap.height
            } else {
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D, 0, 0, 0,
                    bitmap.width, bitmap.height,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
                )
            }
        }.isSuccess
        if (!ok) return

        hasTexture = true
        uploadedGeneration = generation
    }

    override fun drawFilter() {
        uploadIfNeeded()

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

        GLES20.glUniform1i(uSamplerHandle, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)

        GLES20.glUniform1i(uOsdHandle, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1f(uHasOsdHandle, if (hasTexture && visible) 1f else 0f)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    override fun disableResources() {
        GlUtil.disableResources(aTextureHandle, aPositionHandle)
    }

    override fun release() {
        GLES20.glDeleteProgram(program)
        val ids = intArrayOf(textureId)
        GLES20.glDeleteTextures(1, ids, 0)
    }
}
