package com.camnexus.rtsp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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

        private const val CHANNEL_ID = "camnexus_stream"
        private const val NOTIFICATION_ID = 0x434E0001
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE = 128_000

        private const val IDLE_DOWNGRADE_MS = 60_000L
        private const val LOW_WIDTH = 640
        private const val LOW_HEIGHT = 480
        private const val LOW_FPS = 10
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

    private var encoder: RtspServerStream? = null
    private var videoSource: Camera2Source? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var previewSurface: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var streaming = false

    private var config = StreamConfig()
    private var effective = StreamConfig()
    private var lowPower = false

    private var currentUrl = ""
    private var lastBitrate = 0L
    private var retryCount = 0
    private var stallTicks = 0
    private var lastMessage = ""

    private var lastBytesSend = 0L
    private var lastTickAt = 0L
    private var idleSince = 0L

    private val clientListener = object : ClientListener {
        override fun onClientConnected(client: ServerClient) {
            lastMessage = "客户端已连接"
            push()
        }

        override fun onClientDisconnected(client: ServerClient) {
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
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_APPLY -> {
                if (!streaming) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                config = Prefs.load(this)
                boot(config, false)
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
            Log.i(TAG, "streaming started ${applied.width}x${applied.height}@${applied.fps} ${applied.bitrate}bps")
            streaming = true
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

    private fun shutdown() {
        retryCount = 0
        mainHandler.removeCallbacks(retryRunnable)
        stopTicker()
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