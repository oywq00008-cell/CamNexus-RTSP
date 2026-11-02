package com.camnexus.rtsp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.common.StreamingStatsReport
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.utils.CodecUtil
import com.pedro.rtspserver.RtspServerStream
import com.pedro.rtspserver.server.ClientListener
import com.pedro.rtspserver.server.IpType
import com.pedro.rtspserver.server.ServerClient

/**
 * 承载编码器与 RTSP 服务器的前台服务。
 *
 * 约定：
 * 1. 服务和界面解耦，Activity 退出后推流继续；
 * 2. 预览是可选挂件，息屏时只解绑预览，不影响相机与编码；
 * 3. 所有编码器调用都在主线程；
 * 4. 客户端事件只能从 ClientListener 拿到（库内部不会回调 ConnectChecker）。
 */
class StreamService : Service(), ConnectChecker {

    companion object {
        const val ACTION_START = "com.camnexus.rtsp.action.START"
        const val ACTION_STOP = "com.camnexus.rtsp.action.STOP"
        const val ACTION_APPLY = "com.camnexus.rtsp.action.APPLY"
    const val ACTION_IDLE = "com.camnexus.rtsp.action.IDLE"
    const val ACTION_CONFIG_CHANGED = "com.camnexus.rtsp.action.CONFIG_CHANGED"

        private const val CHANNEL_ID = "camnexus_stream"
        private const val NOTIFICATION_ID = 0x434E0001
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE = 128_000

        private const val IDLE_DOWNGRADE_MS = 60_000L
        private const val LOW_WIDTH = 640
        private const val LOW_HEIGHT = 480
        private const val LOW_FPS = FpsRules.LOW_POWER_FPS
        private const val TAG = "CamNexus"
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 4000L
    private const val STALL_TICKS = 12
    private const val LOW_BITRATE = 500_000

        fun start(context: Context) {
            val intent = Intent(context, StreamService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, StreamService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }

        /** 只启动服务与网页控制，不开启推流。 */
    fun ensureHttp(context: Context) {
        val intent = Intent(context, StreamService::class.java).setAction(ACTION_IDLE)
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    fun apply(context: Context) {
            val intent = Intent(context, StreamService::class.java).setAction(ACTION_APPLY)
            runCatching { context.startService(intent) }
        }
    }

    data class State(
        val running: Boolean = false,
        val url: String = "",
        val clients: Int = 0,
        val bitrate: Long = 0L,
        val message: String = "",
        val lowPower: Boolean = false,
        val resolution: String = "",
        val fps: Int = 0
    )

    interface Listener {
        fun onState(state: State)
    }

    inner class LocalBinder : Binder() {
        fun getService(): StreamService = this@StreamService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var encoder: RtspServerStream? = null
    private var videoSource: Camera2Source? = null

    private var torchMinutes = 0
    private val torchRunnable = Runnable {
        runCatching { videoSource?.disableLantern() }
        torchMinutes = 0
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var previewSurface: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var streaming = false

    @Volatile
    private var config = StreamConfig()
    private var effective = StreamConfig()
    private var lowPower = false

    private var currentUrl = ""
    @Volatile private var lastBitrate = 0L
    private var retryCount = 0
    private var stallTicks = 0
    private var httpServer: HttpStatusServer? = null
    private val applyRunnable = Runnable { runCatching { StreamService.apply(this) } }
    private var logoCache: ByteArray? = null
    private var osdFilter: OsdFilterRender? = null
    private var osdBuffers: Array<android.graphics.Bitmap?> = arrayOfNulls(2)
    private var osdIndex = 0
    @Volatile private var httpPort = 0
    private var httpConfigKey = ""
    @Volatile private var startedAtMs = 0L
    private val clientSeen = ConcurrentHashMap<ServerClient, Long>()
    private var lastMessage = ""

    private var lastBytesSend = 0L
    private var lastTickAt = 0L
    private var idleSince = 0L

    private val clientListener = object : ClientListener {
        override fun onClientConnected(client: ServerClient) {
            clientSeen[client] = System.currentTimeMillis()
            lastMessage = "客户端已连接"
            push()
        }

        override fun onClientDisconnected(client: ServerClient) {
            clientSeen.remove(client)
            lastMessage = "客户端已断开"
            push()
        }

        override fun onClientNewBitrate(bitrate: Long, client: ServerClient) {
            lastBitrate = bitrate
        }

        override fun onClientStreamingStats(report: StreamingStatsReport, client: ServerClient) = Unit
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!streaming) return
            refreshStats()
            updateOsd()
            if (evaluatePowerSaving()) return
            push()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                return START_STICKY
            }
            ACTION_IDLE -> {
                // 待机：只保活服务与网页，不开摄像头
                if (!startForegroundSafely()) return START_NOT_STICKY
                if (!streaming) {
                    config = Prefs.load(this)
                    lastMessage = getString(R.string.state_ready)
                    updateNotification(getString(R.string.notification_idle))
                    if (httpServer == null) startHttpServer()
                    push()
                }
                return START_STICKY
            }
            ACTION_APPLY -> {
                config = Prefs.load(this)
                if (streaming) {
                    boot(config, false)
                } else if (!config.httpEnabled) {
                    // 待机中把网页控制关掉：不再需要常驻，直接停止服务
                    shutdown()
                } else {
                    // 待机状态：让新配置（端口/鉴权）立刻生效
                    if (!startForegroundSafely()) return START_NOT_STICKY
                    startHttpServer()
                    lastMessage = getString(R.string.state_ready)
                    updateNotification(getString(R.string.notification_idle))
                    push()
                }
                return START_STICKY
            }
        }
        if (!startForegroundSafely()) return START_NOT_STICKY
        startStreaming()
        return START_STICKY
    }

    override fun onDestroy() {
        stopTicker()
        // 避免服务已销毁后重试任务仍在跑，导致对已销毁对象调用 boot()
        retryCount = 0
        mainHandler.removeCallbacks(retryRunnable)
        streaming = false
        releaseEncoder()
        releaseLocks()
        listener = null
        super.onDestroy()
    }

    fun setListener(value: Listener?) {
        listener = value
        value?.onState(currentState())
    }

    /**
     * 补光灯：0 表示关闭，>0 表示开启并在 N 分钟后自动关闭。
     * 返回空字符串表示成功，否则返回给用户看的错误信息。
     */
    fun setTorch(minutes: Int): String {
        // 关闭永远允许（未开播时也可以复位）
        if (minutes <= 0) {
            mainHandler.removeCallbacks(torchRunnable)
            runCatching { videoSource?.disableLantern() }
            torchMinutes = 0
            return ""
        }
        if (!streaming) return getString(R.string.torch_need_service)
        val source = videoSource ?: return getString(R.string.torch_need_service)
        mainHandler.removeCallbacks(torchRunnable)
        val enabled = runCatching {
            source.enableLantern()
            source.isLanternEnabled()
        }.getOrDefault(false)
        if (!enabled) return getString(R.string.torch_unsupported)
        torchMinutes = minutes
        mainHandler.postDelayed(torchRunnable, minutes * 60_000L)
        return ""
    }

    fun currentTorchMinutes(): Int = torchMinutes
    fun isRunning(): Boolean = streaming

    fun currentConfig(): StreamConfig = config

    fun attachPreview(surface: Surface, width: Int, height: Int) {
        previewSurface = surface
        previewWidth = width
        previewHeight = height
        attachPreviewInternal()
    }

    fun detachPreview() {
        previewSurface = null
        val stream = encoder ?: return
        runCatching { if (stream.isOnPreview) stream.stopPreview() }
    }

    /** 切摄像头、改分辨率/帧率/端口/编码：统一重建一次管线。 */
    fun applyConfig(newConfig: StreamConfig) {
        config = newConfig
        Prefs.save(this, newConfig)
        if (!streaming) {
            push()
            return
        }
        boot(newConfig, false)
    }

    /** 码率可以在线改，不中断。 */
    fun setBitrate(bitsPerSecond: Int) {
        config = config.copy(bitrate = bitsPerSecond)
        effective = effective.copy(bitrate = bitsPerSecond)
        Prefs.save(this, config)
        runCatching { encoder?.setVideoBitrateOnFly(bitsPerSecond) }
        push()
    }

    /** 夜景模式：GL 滤镜链，增强档额外拉曝光补偿。 */
    private fun applyNightFilters() {
        val stream = encoder ?: return
        val gl = stream.getGlInterface()
        val mode = config.nightMode
        runCatching {
            gl.clearFilters()

            if (mode != StreamConfig.NIGHT_OFF || config.denoiseEnabled) {
                val strong = mode == StreamConfig.NIGHT_STRONG
                // 自定义曲线：提亮 + 黑位锚定 + 对比度，单次采样
                gl.addFilter(NightFilterRender().apply {
                    setSourceSize(minOf(effective.width, effective.height), maxOf(effective.width, effective.height))
                    // 高光保护 + 双边降噪：降噪由设置页开关与滑块控制
                    gamma = if (mode == StreamConfig.NIGHT_OFF) 1f else if (strong) 0.70f else 0.82f
                    black = if (mode == StreamConfig.NIGHT_OFF) 0f else if (strong) 0.02f else 0.01f
                    contrast = if (mode == StreamConfig.NIGHT_OFF) 1f else if (strong) 1.04f else 1.02f
                    denoise = if (config.denoiseEnabled) config.denoiseLevel / 100f else 0f
                })
                videoSource?.setExposure(if (strong) 4 else 0)
            } else {
                videoSource?.setExposure(0)
            }

            if (config.osdEnabled) {
                val filter = OsdFilterRender()
                gl.addFilter(filter)
                osdFilter = filter
                updateOsd()
            } else {
                osdFilter = null
            }
        }.onFailure { Log.w(TAG, "applyNightFilters failed: ${it.message}") }
    }

    /** 每秒刷新一次叠加内容（时间会走，电量会变）。 */
    private fun updateOsd() {
        val filter = osdFilter ?: return
        if (!config.osdEnabled) return
        // 按 APP 的方向合成：内容方向跟随界面，横屏界面用横版位图，否则文字会被拉伸
        val shortSide = minOf(effective.width, effective.height)
        val longSide = maxOf(effective.width, effective.height)
        val width = if (config.uiLandscape) longSide else shortSide
        val height = if (config.uiLandscape) shortSide else longSide
        if (width <= 0 || height <= 0) return

        val buffers = osdBuffers
        if (buffers[0]?.width != width || buffers[0]?.height != height) {
            // 不在这里 recycle：GL 线程可能仍在上传上一帧，交给 GC 回收更安全
            buffers[0] = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            buffers[1] = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            osdIndex = 0
        }
        osdIndex = (osdIndex + 1) % 2
        val target = buffers[osdIndex] ?: return

        val (level, charging) = OsdOverlay.battery(this)
        val drawn = runCatching {
            OsdOverlay.draw(this, config, target, level, charging)
        }.getOrDefault(false)
        filter.update(if (drawn) target else null)
    }

    /**
     * 只读 HTTP 状态接口。8080 被占用时依次尝试 8081、8082。
     */
    private fun startHttpServer() {
        if (!config.httpEnabled) {
            stopHttpServer()
            Log.i(TAG, "http status disabled")
            return
        }
        // 端口与账号没变就复用现有服务器：频繁重建会让网页会话失效、页面掉回登录
        val key = "${config.httpPort}|${config.httpUser}|${config.httpPass}"
        if (httpServer != null && httpConfigKey == key) return
        stopHttpServer()
        startedAtMs = System.currentTimeMillis()
        val base = config.httpPort.coerceIn(1024, 65533)
        for (port in intArrayOf(base, base + 1, base + 2)) {
            val server = HttpStatusServer(
                port = port,
                user = config.httpUser,
                pass = config.httpPass,
                idleTimeoutMs = httpIdleTimeoutMs(),
                statusJson = { buildStatusJson() },
                settingsJson = { editableSettingsJson() },
                capabilitiesJson = { capabilitiesJson() },
                applySettings = { body -> applySettingsJson(body) },
                requestStream = { enabled -> requestStreaming(enabled) },
                setTorchMinutes = { minutes ->
                    if (minutes < 0 || minutes > 60) "时长需在 0–60 分钟之间"
                    else setTorch(minutes)
                },
                logoBytes = { logoPngBytes() }
            )
            val ok = runCatching { server.start(2000, false) }.isSuccess
            if (ok) {
                httpServer = server
                httpPort = port
                httpConfigKey = key
                Log.i(TAG, "http status listening on 0.0.0.0:$port")
                return
            }
            runCatching { server.stop() }
        }
        httpPort = 0
        httpConfigKey = ""
        Log.w(TAG, "http status server could not bind any port")
    }

    private fun stopHttpServer() {
        runCatching { httpServer?.stop() }
        httpServer = null
        httpPort = 0
        httpConfigKey = ""
    }

    /** 网页会话闲置超时，默认 15 分钟；调试时可用系统属性覆盖（5 秒–24 小时）。 */
    private fun httpIdleTimeoutMs(): Long {
        val override = runCatching {
            android.provider.Settings.Global.getLong(contentResolver, "camnexus_http_idle_ms", 0L)
        }.getOrDefault(0L)
        return if (override in 5_000L..86_400_000L) override else 15 * 60 * 1000L
    }

    private fun buildStatusJson(): String {
        val client = runCatching { encoder?.getStreamClient() }.getOrNull()
        val now = System.currentTimeMillis()
        val endpoint = runCatching { client?.getEndPointConnection() ?: "" }.getOrDefault("")

        val root = JSONObject()
        root.put("app", JSONObject().apply {
            put("version", appVersion())
            put("uptimeSec", if (startedAtMs > 0L) (now - startedAtMs) / 1000 else 0)
        })
        root.put("server", JSONObject().apply {
            put("running", streaming)
            put("ip", endpoint.substringAfter("://").substringBefore(":").ifBlank { localWifiIp() })
            put("endpoint", endpoint)
            put("rtspPort", config.port)
            put("httpPort", httpPort)
        })
        // 待机时没有实际推流参数，展示当前配置而不是上次推流的残留值
        val shown = if (streaming) effective else config
        root.put("stream", JSONObject().apply {
            put("codec", shown.codecLabel)
            put("width", shown.width)
            put("height", shown.height)
            put("fps", shown.fps)
            put("fpsConfigured", config.fps)
            put("bitrate", shown.bitrate)
            put("encoder", shown.encoder)
            put("nightMode", config.nightMode)
            put("denoiseEnabled", config.denoiseEnabled)
            put("denoiseLevel", config.denoiseLevel)
            put("powerSaving", lowPower)
            put("torchMinutes", torchMinutes)
            put("authEnabled", config.authEnabled)
            put("osdEnabled", config.osdEnabled)
            put("osdPosition", config.osdPosition)
            put("osdRotation", config.osdRotation)
            put("osdFlip", config.osdFlip)
        })
        root.put("clients", JSONObject().apply {
            put("count", runCatching { client?.getNumClients() ?: 0 }.getOrDefault(0))
        })
        root.put("stats", JSONObject().apply {
            put("bytesSent", runCatching { client?.getBytesSend() ?: 0L }.getOrDefault(0L))
            put("bitrate", lastBitrate)
        })
        return root.toString()
    }
    private fun logoPngBytes(): ByteArray? {
        logoCache?.let { return it }
        val bytes = runCatching {
            val bitmap = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.logo_watermark)
                ?: return@runCatching null
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }.getOrNull() ?: return null
        logoCache = bytes
        return bytes
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /** 待机（未推流）时没有 RTSP 地址，用 WiFi IP 兜底给网页显示。 */
    private fun localWifiIp(): String = NetInfo.localIpv4()


    /** 供网页控制台读取的可编辑配置。 */
    fun editableSettingsJson(): String {
        val c = config
        return JSONObject().apply {
            put("cameraId", c.cameraId)
            put("width", c.width)
            put("height", c.height)
            put("fps", c.fps)
            put("bitrate", c.bitrate)
            put("codec", c.codec)
            put("encoder", c.encoder)
            put("port", c.port)
            put("authEnabled", c.authEnabled)
            put("powerSaving", c.powerSaving)
            put("nightMode", c.nightMode)
            put("denoiseEnabled", c.denoiseEnabled)
            put("denoiseLevel", c.denoiseLevel)
            put("osdEnabled", c.osdEnabled)
            put("osdDate", c.osdDate)
            put("osdDateFormat", c.osdDateFormat)
            put("osdBattery", c.osdBattery)
            put("osdBatteryIcon", c.osdBatteryIcon)
            put("osdPosition", c.osdPosition)
            put("osdRotation", c.osdRotation)
            put("osdFlip", c.osdFlip)
        }.toString()
    }

    /** 相机与分辨率能力，供网页下拉框使用。 */
    fun capabilitiesJson(): String = JSONObject().apply {
        val cameras = JSONArray()
        CameraCaps.cameras(this@StreamService).forEach { cam ->
            val resolutions = JSONArray()
            CameraCaps.resolutions(this@StreamService, cam.id).forEach { size ->
                resolutions.put(JSONArray().put(size.width).put(size.height))
            }
            cameras.put(JSONObject().apply {
                put("id", cam.id)
                put("label", cam.label)
                put("resolutions", resolutions)
            })
        }
        put("cameras", cameras)
    }.toString()

    /**
     * 应用网页提交的配置。返回空字符串表示成功，否则是给用户看的错误信息。
     * 配置先落盘，稍后再触发重建，保证 HTTP 响应能先返回。
     */
    fun applySettingsJson(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrElse { return "请求体不是合法 JSON" }
        var next = config

        json.optString("cameraId").takeIf { it.isNotEmpty() }?.let { id ->
            val sizes = CameraCaps.resolutions(this, id)
            if (sizes.isEmpty()) return "不支持的相机：$id"
            val givesSize = json.has("width") || json.has("height")
            if (!givesSize && sizes.none { it.width == next.width && it.height == next.height }) {
                return "该相机不支持当前分辨率 ${next.width}x${next.height}，请同时选择分辨率"
            }
            next = next.copy(cameraId = id)
        }

        if (json.has("width") || json.has("height")) {
            val w = json.optInt("width", next.width)
            val h = json.optInt("height", next.height)
            if (w != next.width || h != next.height) {
                val sizes = CameraCaps.resolutions(this, next.cameraId)
                if (sizes.none { it.width == w && it.height == h }) return "不支持的分辨率：${w}x$h"
                next = next.copy(width = w, height = h)
            }
        }

        if (json.has("fps")) {
            val fps = json.optInt("fps", next.fps)
            if (fps !in 1..60) return "帧率需在 1–60 之间"
            val options = CameraCaps.fpsOptions(this, next.cameraId, next.width, next.height)
            if (options.isNotEmpty() && fps !in options) {
                return "该分辨率不支持 ${fps}fps，可选：${options.joinToString(" / ")}"
            }
            next = next.copy(fps = fps)
        }

        if (json.has("bitrate")) {
            val bitrate = json.optInt("bitrate", next.bitrate)
            if (bitrate !in 200_000..20_000_000) return "码率需在 200000–20000000 bps 之间"
            next = next.copy(bitrate = bitrate)
        }

        json.optString("codec").takeIf { it.isNotEmpty() }?.let { codec ->
            if (codec != StreamConfig.CODEC_H264 && codec != StreamConfig.CODEC_H265) {
                return "编码格式只支持 H264 / H265"
            }
            next = next.copy(codec = codec)
        }

        if (json.has("port")) {
            val port = json.optInt("port", next.port)
            if (port !in 1024..65535) return "RTSP 端口需在 1024–65535 之间"
            val webPort = httpPort
            if (webPort > 0 && port in webPort..(webPort + 2)) {
                return "RTSP 端口不能与网页端口（$webPort）冲突"
            }
            next = next.copy(port = port)
        }

        if (json.has("powerSaving")) next = next.copy(powerSaving = json.optBoolean("powerSaving"))

        if (json.has("nightMode")) {
            val mode = json.optInt("nightMode", next.nightMode)
            if (mode !in 0..2) return "夜景模式只能是 0（关闭）/ 1（标准）/ 2（增强）"
            next = next.copy(nightMode = mode)
        }

        if (json.has("denoiseEnabled")) next = next.copy(denoiseEnabled = json.optBoolean("denoiseEnabled"))
        if (json.has("denoiseLevel")) {
            val level = json.optInt("denoiseLevel", next.denoiseLevel)
            if (level !in 0..100) return "降噪强度需在 0–100 之间"
            next = next.copy(denoiseLevel = level)
        }

        if (json.has("osdEnabled")) next = next.copy(osdEnabled = json.optBoolean("osdEnabled"))
        if (json.has("osdDate")) next = next.copy(osdDate = json.optBoolean("osdDate"))
        if (json.has("osdDateFormat")) {
            val format = json.optInt("osdDateFormat", next.osdDateFormat)
            if (format !in 0..3) return "日期格式只能是 0–3"
            next = next.copy(osdDateFormat = format)
        }
        if (json.has("osdBattery")) next = next.copy(osdBattery = json.optBoolean("osdBattery"))
        if (json.has("osdBatteryIcon")) next = next.copy(osdBatteryIcon = json.optBoolean("osdBatteryIcon"))
        if (json.has("osdPosition")) {
            val position = json.optInt("osdPosition", next.osdPosition)
            if (position !in 0..3) return "叠加位置只能是 0–3"
            next = next.copy(osdPosition = position)
        }
        if (json.has("osdRotation")) {
            val rotation = json.optInt("osdRotation", next.osdRotation)
            if (rotation !in listOf(0, 90, 180, 270)) return "旋转角度只能是 0 / 90 / 180 / 270"
            next = next.copy(osdRotation = rotation)
        }
        if (json.has("osdFlip")) {
            val flip = json.optInt("osdFlip", next.osdFlip)
            if (flip !in 0..3) return "翻转只能是 0–3"
            next = next.copy(osdFlip = flip)
        }

        val changed = next != config
        if (!changed) {
            Log.i(TAG, "applySettingsJson: no change")
            return ""
        }

        config = next
        Prefs.save(this, next)
        runCatching { sendBroadcast(Intent(ACTION_CONFIG_CHANGED).setPackage(packageName)) }
        Log.i(TAG, "applySettingsJson: saved, restart scheduled")
        mainHandler.removeCallbacks(applyRunnable)
        mainHandler.postDelayed(applyRunnable, 500)
        return ""
    }

    private fun startForegroundSafely(): Boolean {
        return try {
            createChannel()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(getString(R.string.state_starting)), type
            )
            true
        } catch (t: Throwable) {
            lastMessage = "前台服务启动失败：${t.message}"
            push()
            false
        }
    }

    private fun startStreaming() {
        if (streaming) {
            push()
            return
        }
        config = Prefs.load(this)
        boot(config, false)
    }

    private fun boot(cfg: StreamConfig, powerTier: Boolean) {
        stopTicker()
        releaseEncoder()
        val context = applicationContext
        try {
            val camera = Camera2Source(context)
            videoSource = camera
            val stream = RtspServerStream(context, cfg.port, this, camera, MicrophoneSource())
            stream.getGlInterface().autoHandleOrientation = true
            stream.getStreamClient().forceIpType(IpType.IPv4)
            stream.getStreamClient().setClientListener(clientListener)
            if (cfg.authEnabled && cfg.username.isNotEmpty() && cfg.password.isNotEmpty()) {
                stream.getStreamClient().setAuthorization(cfg.username, cfg.password)
            }
            stream.setVideoCodec(
                if (cfg.codec == StreamConfig.CODEC_H265) VideoCodec.H265 else VideoCodec.H264
            )
            stream.forceCodecType(
                when (cfg.encoder) {
                    StreamConfig.ENCODER_HARDWARE -> CodecUtil.CodecType.HARDWARE
                    StreamConfig.ENCODER_SOFTWARE -> CodecUtil.CodecType.SOFTWARE
                    else -> CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND
                },
                CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND
            )
            encoder = stream
            effective = cfg

            alignFacing(cfg.cameraId)

            // 分辨率/帧率组合可能不被支持（例如 4K 只有 30fps），先退一档再试，避免直接失败
            var applied = cfg
            // 增强夜景：降帧到 15fps，让自动曝光有更长的积分时间（长曝光）
            if (applied.nightMode == StreamConfig.NIGHT_STRONG && applied.fps > FpsRules.STRONG_NIGHT_FPS) {
                applied = applied.copy(fps = FpsRules.STRONG_NIGHT_FPS)
            }
            var prepared = runCatching { prepare(stream, applied) }.getOrDefault(false)
            if (!prepared && applied.fps > 30) {
                applied = applied.copy(fps = 30)
                prepared = runCatching { prepare(stream, applied) }.getOrDefault(false)
            }
            if (!prepared) {
                fail("参数不被支持：${applied.resolutionText} @${applied.fps}fps")
                return
            }
            effective = applied

            stream.startStream()
            Log.i(TAG, "streaming started ${applied.width}x${applied.height}@${applied.fps} ${applied.bitrate}bps night=${applied.nightMode} exposure=${if (applied.nightMode == StreamConfig.NIGHT_STRONG) 4 else 0}")
            streaming = true
            applyNightFilters()
            startHttpServer()
            retryCount = 0
            stallTicks = 0
            // 部分机型（如展锐 C2）在 configure 阶段会用按分辨率算出的默认码率，这里按配置覆盖一次
            runCatching { stream.setVideoBitrateOnFly(applied.bitrate) }
            lowPower = powerTier
            idleSince = 0L
            currentUrl = runCatching { stream.getStreamClient().getEndPointConnection() }
                .getOrDefault("")
            lastMessage = getString(R.string.state_idle)

            acquireLocks()
            updateNotification(currentUrl.ifBlank { getString(R.string.app_name) })
            applyCamera(cfg.cameraId)
            attachPreviewInternal()
            startTicker()
            push()
        } catch (t: Throwable) {
            fail("启动失败：${t.message}")
        }
    }

    private fun prepare(stream: RtspServerStream, cfg: StreamConfig): Boolean {
        val videoOk = stream.prepareVideo(
            cfg.width,
            cfg.height,
            cfg.bitrate,
            fps = cfg.fps,
            iFrameInterval = 1,
            rotation = 0
        )
        val audioOk = stream.prepareAudio(AUDIO_SAMPLE_RATE, true, AUDIO_BITRATE)
        return videoOk && audioOk
    }

    private fun alignFacing(cameraId: String) {
        val source = videoSource ?: return
        val facing = CameraCaps.facing(this, cameraId) ?: return
        val desired = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            CameraHelper.Facing.FRONT
        } else {
            CameraHelper.Facing.BACK
        }
        if (source.getCameraFacing() != desired) {
            runCatching { source.switchCamera() }
        }
    }

    private fun applyCamera(cameraId: String) {
        val source = videoSource ?: return
        if (!streaming) return
        val current = runCatching { source.getCurrentCameraId() }.getOrNull()
        if (current == cameraId) return
        runCatching { source.openCameraId(cameraId) }
            .onFailure { lastMessage = "切换摄像头失败：${it.message}" }
    }

    private fun attachPreviewInternal() {
        if (!streaming) return
        val surface = previewSurface ?: return
        val stream = encoder ?: return
        if (!surface.isValid || stream.isOnPreview) return
        val width = if (previewWidth > 0) previewWidth else 1080
        val height = if (previewHeight > 0) previewHeight else 1920
        runCatching { stream.startPreview(surface, width, height) }
            .onFailure { lastMessage = "预览启动失败：${it.message}" }
    }

    /**
     * 只停止推流。如果网页控制开启，服务继续以待机模式常驻，方便远程再次开启。
     */
    private fun stopStreaming() {
        retryCount = 0
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.removeCallbacks(applyRunnable)
        stopTicker()
        clientSeen.clear()
        streaming = false
        lowPower = false
        currentUrl = ""
        lastMessage = getString(R.string.state_idle)
        // 先停 GL 管线，再回收位图，避免 GL 线程还在上传已回收的位图
        releaseEncoder()
        osdBuffers.forEach { runCatching { it?.recycle() } }
        osdBuffers = arrayOfNulls(2)
        osdFilter = null
        releaseLocks()
        if (config.httpEnabled) {
            lastMessage = getString(R.string.state_ready)
            updateNotification(getString(R.string.notification_idle))
            if (httpServer == null) startHttpServer()
            push()
        } else {
            shutdown()
        }
    }

    /** 网页端请求开关推流；真正的切换在主线程执行。 */
    fun requestStreaming(enabled: Boolean): String {
        if (enabled == streaming) return ""
        return runCatching {
            mainHandler.post {
                runCatching {
                    if (enabled) {
                        startStreaming()
                    } else {
                        stopStreaming()
                    }
                }.onFailure { Log.w(TAG, "requestStreaming failed: ${it.message}") }
            }
            ""
        }.getOrElse { "操作失败：${it.message}" }
    }

    private fun shutdown() {
        retryCount = 0
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.removeCallbacks(applyRunnable)
        stopTicker()
        stopHttpServer()
        clientSeen.clear()
        osdBuffers.forEach { runCatching { it?.recycle() } }
        osdBuffers = arrayOfNulls(2)
        osdFilter = null
        streaming = false
        lowPower = false
        currentUrl = ""
        lastMessage = getString(R.string.state_idle)
        releaseEncoder()
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        push()
    }

    private fun fail(message: String) {
        Log.w(TAG, "fail retry=$retryCount msg=$message")
        stopTicker()
        streaming = false
        lowPower = false
        currentUrl = ""
        releaseEncoder()
        releaseLocks()
        if (retryCount < MAX_RETRIES) {
            // 保持前台状态重试，避免服务在退避期间被系统回收
            retryCount++
            lastMessage = getString(R.string.retry_waiting, retryCount, MAX_RETRIES)
            push()
            mainHandler.postDelayed(retryRunnable, RETRY_DELAY_MS)
        } else {
            retryCount = 0
            lastMessage = message
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            push()
        }
    }

    private val retryRunnable = Runnable {
        if (retryCount > 0) boot(config, false)
    }

    private fun releaseEncoder() {
        // 释放前先关灯，避免服务停止后闪光灯还亮着
        mainHandler.removeCallbacks(torchRunnable)
        runCatching { videoSource?.disableLantern() }
        torchMinutes = 0
        val stream = encoder
        if (stream != null) {
            runCatching { if (stream.isOnPreview) stream.stopPreview() }
            runCatching { if (stream.isStreaming) stream.stopStream() }
            runCatching { stream.release() }
        }
        encoder = null
        videoSource = null
    }

    /** 返回 true 表示触发了重建，调用方不要再重复投递 ticker。 */
    private fun evaluatePowerSaving(): Boolean {
        if (!config.powerSaving || lowPower) {
            idleSince = 0L
            return false
        }
        val clients = encoder?.getStreamClient()?.getNumClients() ?: 0
        val now = SystemClock.elapsedRealtime()
        if (clients > 0) {
            idleSince = 0L
            return false
        }
        if (idleSince == 0L) {
            idleSince = now
            return false
        }
        if (now - idleSince < IDLE_DOWNGRADE_MS) return false

        val low = config.copy(
            width = LOW_WIDTH,
            height = LOW_HEIGHT,
            fps = LOW_FPS,
            bitrate = LOW_BITRATE
        )
        boot(low, true)
        return true
    }

    private fun startTicker() {
        mainHandler.removeCallbacks(ticker)
        lastBytesSend = 0L
        lastTickAt = 0L
        mainHandler.postDelayed(ticker, 1000L)
    }

    private fun stopTicker() {
        mainHandler.removeCallbacks(ticker)
    }

    private fun refreshStats() {
        val client = encoder?.getStreamClient() ?: return
        val now = SystemClock.elapsedRealtime()
        val bytes = runCatching { client.getBytesSend() }.getOrDefault(0L)
        val clients = runCatching { client.getNumClients() }.getOrDefault(0)
        if (clients == 0) {
            lastBitrate = 0L
            lastBytesSend = bytes
            lastTickAt = now
            stallTicks = 0
            return
        }
        if (lastTickAt > 0L && now > lastTickAt && bytes >= lastBytesSend) {
            lastBitrate = (bytes - lastBytesSend) * 8000L / (now - lastTickAt)
        }
        // 有人看却连续 12 秒没有新数据：重建一次编码器
        if (bytes > lastBytesSend) {
            stallTicks = 0
        } else {
            stallTicks++
        }
        if (stallTicks >= STALL_TICKS) {
            stallTicks = 0
            lastMessage = getString(R.string.reconnecting)
            boot(config, lowPower)
            return
        }
        lastBytesSend = bytes
        lastTickAt = now
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CamNexus:stream")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CamNexus:wifi")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cam)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.btn_stop), stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun currentState(): State = State(
        running = streaming,
        url = currentUrl,
        clients = encoder?.getStreamClient()?.getNumClients() ?: 0,
        bitrate = lastBitrate,
        message = lastMessage,
        lowPower = lowPower,
        resolution = effective.resolutionText,
        fps = effective.fps
    )

    private fun push() {
        val state = currentState()
        mainHandler.post { listener?.onState(state) }
    }

    // 服务端模式下这里只覆盖"服务自身"的成败（例如端口被占用），客户端事件走 ClientListener。
    override fun onConnectionStarted(url: String) = Unit

    override fun onConnectionSuccess() = Unit

    override fun onConnectionFailed(reason: String) {
        if (reason.contains("Server creation failed", ignoreCase = true)) {
            fail("端口 ${config.port} 被占用")
        } else {
            lastMessage = "连接失败：$reason"
            push()
        }
    }

    override fun onNewBitrate(bitrate: Long) {
        lastBitrate = bitrate
    }

    override fun onDisconnect() = Unit

    override fun onAuthError() {
        lastMessage = "鉴权失败"
        push()
    }

    override fun onAuthSuccess() = Unit
}