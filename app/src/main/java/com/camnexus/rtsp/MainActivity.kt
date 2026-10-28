package com.camnexus.rtsp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.annotation.SuppressLint
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.util.Locale

class MainActivity : AppCompatActivity(), StreamService.Listener {

    companion object {
        private const val BITRATE_MIN = 500_000
        private const val BITRATE_STEP = 500_000
        private const val BITRATE_MAX = 20_000_000
    }

    private lateinit var root: View
    private lateinit var preview: SurfaceView
    private lateinit var logoWatermark: ImageView
    private lateinit var statusPill: View
    private lateinit var statusDot: View
    private lateinit var txtState: TextView
    private lateinit var txtMeta: TextView
    private lateinit var panel: View
    private lateinit var urlText: TextView
    private lateinit var btnToggle: Button
    private lateinit var txtExpand: TextView
    private lateinit var iconExpand: View
    private lateinit var settingsBox: View
    private lateinit var txtCamera: TextView
    private lateinit var txtResolution: TextView
    private lateinit var txtFps: TextView
    private lateinit var seekBitrate: SeekBar
    private lateinit var txtBitrate: TextView

    private var service: StreamService? = null
    private var bound = false
    private var previewAttached = false
    private var running = false
    private var expanded = false
    private var dragStartY = 0f
    private var dragStartHeight = 0
    private var dragExtra = 0
    private var dragFromExpanded = false
    private var dragExpandedHeight = 0
    private var dragCollapsedHeight = 0
    private var dragging = false

    private var cameras: List<CameraEntry> = emptyList()
    private var resolutions: List<Size> = emptyList()
    private var fpsOptions: List<Int> = listOf(30)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasCorePermissions()) {
                refreshUiFromConfig()
            } else {
                txtMeta.text = getString(R.string.permission_needed)
            }
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? StreamService.LocalBinder ?: return
            val streamService = localBinder.getService()
            service = streamService
            bound = true
            streamService.setListener(this@MainActivity)
            attachPreviewIfPossible()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = attachPreviewIfPossible()

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
            attachPreviewIfPossible()

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            previewAttached = false
            service?.detachPreview()
        }
    }

    private val seekListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            txtBitrate.text = formatBitrate(bitrateForProgress(progress))
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            val bitrate = bitrateForProgress(seekBar?.progress ?: 0)
            Prefs.update(this@MainActivity) { it.copy(bitrate = bitrate) }
            service?.setBitrate(bitrate)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        preview = findViewById(R.id.preview)
        logoWatermark = findViewById(R.id.logoWatermark)
        statusPill = findViewById(R.id.statusPill)
        statusDot = findViewById(R.id.statusDot)
        txtState = findViewById(R.id.txtState)
        txtMeta = findViewById(R.id.txtMeta)
        panel = findViewById(R.id.panel)
        urlText = findViewById(R.id.urlText)
        btnToggle = findViewById(R.id.btnToggle)
        txtExpand = findViewById(R.id.txtExpand)
        iconExpand = findViewById(R.id.iconExpand)
        settingsBox = findViewById(R.id.settingsBox)
        txtCamera = findViewById(R.id.txtCamera)
        txtResolution = findViewById(R.id.txtResolution)
        txtFps = findViewById(R.id.txtFps)
        seekBitrate = findViewById(R.id.seekBitrate)
        txtBitrate = findViewById(R.id.txtBitrate)

        setupEdgeToEdge()
        adaptLayoutToScreen()

        seekBitrate.max = (BITRATE_MAX - BITRATE_MIN) / BITRATE_STEP
        seekBitrate.setOnSeekBarChangeListener(seekListener)
        preview.holder.addCallback(surfaceCallback)

        btnToggle.setOnClickListener { onToggleClicked() }
        findViewById<View>(R.id.btnCopy).setOnClickListener { copyUrl() }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.expandRow).setOnClickListener { setExpanded(!expanded) }

        findViewById<View>(R.id.rowCamera).setOnClickListener { pickCamera() }
        findViewById<View>(R.id.rowResolution).setOnClickListener { pickResolution() }
        findViewById<View>(R.id.rowFps).setOnClickListener { pickFps() }
        setupDrag()
        root.setOnClickListener { if (expanded) setExpanded(false) }

        // 先刷新界面（读摄像头列表不需要权限），再决定是否申请权限
        refreshUiFromConfig()
        if (!Prefs.isOnboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        } else if (!hasCorePermissions()) {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, StreamService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    @SuppressLint("MissingSuperCall")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 面板展开时先收起；再按一次才询问是否退出
        if (expanded) {
            setExpanded(false)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_message)
            .setPositiveButton(R.string.exit_ok) { _, _ -> finish() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // 从设置页回来时同步一次
        if (hasCorePermissions()) refreshUiFromConfig()
    }

    override fun onStop() {
        previewAttached = false
        service?.detachPreview()
        service?.setListener(null)
        if (bound) {
            runCatching { unbindService(connection) }
            bound = false
        }
        service = null
        super.onStop()
    }

    override fun onDestroy() {
        preview.holder.removeCallback(surfaceCallback)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attributes = window.attributes
            attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attributes
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val params = statusPill.layoutParams as FrameLayout.LayoutParams
            params.topMargin = safe.top + Ui.dp(this, 12)
            statusPill.layoutParams = params
            panel.updatePadding(bottom = safe.bottom + Ui.dp(this, 18))
            insets
        }
    }

    private fun adaptLayoutToScreen() {
        val metrics = resources.displayMetrics
        if (resources.configuration.screenWidthDp >= 600) {
            panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
                width = Ui.dp(this@MainActivity, 560)
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
        }
        panel.post {
            val maxHeight = (metrics.heightPixels * 0.72f).toInt()
            if (panel.height > maxHeight) {
                panel.layoutParams = panel.layoutParams.apply { height = maxHeight }
                panel.requestLayout()
            }
        }
    }

    private fun refreshUiFromConfig() {
        cameras = CameraCaps.cameras(this)
        var config = normalizeForCamera(Prefs.load(this))
        if (config != Prefs.load(this)) Prefs.save(this, config)
        val cameraIndex = cameras.indexOfFirst { it.id == config.cameraId }.coerceAtLeast(0)
        val cameraId = cameras.getOrNull(cameraIndex)?.id ?: config.cameraId
        txtCamera.text = cameras.getOrNull(cameraIndex)?.label ?: getString(R.string.label_camera)

        resolutions = CameraCaps.resolutions(this, cameraId).ifEmpty {
            listOf(Size(640, 480), Size(1280, 720))
        }
        txtResolution.text = config.resolutionText

        fpsOptions = CameraCaps.fpsOptions(this, cameraId, config.width, config.height)
        txtFps.text = "${config.fps} fps"

        seekBitrate.progress = progressForBitrate(config.bitrate)
        txtBitrate.text = formatBitrate(config.bitrate)

        if (!running) {
            urlText.text = "rtsp://—:${config.port}/"
            txtMeta.text = "${config.codecLabel} · :${config.port} · ${config.bitrate / 1000} kbps"
        }
    }

    /** 换摄像头后，原来的分辨率/帧率可能不被支持，收敛到最近的可用档位。 */
    private fun normalizeForCamera(config: StreamConfig): StreamConfig {
        var result = config
        // 去重/过滤后，旧配置里的 cameraId 可能已不在列表中，回退到第一颗
        val available = CameraCaps.cameras(this)
        if (available.isNotEmpty() && available.none { it.id == result.cameraId }) {
            result = result.copy(cameraId = available.first().id)
        }
        val sizes = CameraCaps.resolutions(this, result.cameraId)
        if (sizes.isNotEmpty() && sizes.none { it.width == result.width && it.height == result.height }) {
            val fallback = sizes.filter { it.width <= result.width }
                .maxByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { it.width * it.height }
            if (fallback != null) {
                result = result.copy(width = fallback.width, height = fallback.height)
            }
        }
        return normalizeFps(result)
    }

    /** 帧率超出当前分辨率支持范围时，收敛到最接近的档位。 */
    private fun normalizeFps(config: StreamConfig): StreamConfig {
        val options = CameraCaps.fpsOptions(this, config.cameraId, config.width, config.height)
        if (options.isEmpty() || options.contains(config.fps)) return config
        val nearest = options.minByOrNull { kotlin.math.abs(it - config.fps) } ?: return config
        return config.copy(fps = nearest)
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        panel.translationY = 0f
        val group = root as? ViewGroup ?: return
        TransitionManager.beginDelayedTransition(group, AutoTransition().setDuration(200))
        settingsBox.visibility = if (value) View.VISIBLE else View.GONE
        txtExpand.setText(if (value) R.string.collapse_settings else R.string.expand_settings)
        iconExpand.animate().rotation(if (value) 180f else 0f).setDuration(200).start()
        updatePanelStyle()
    }

    /**
     * 运行中且收起时用半透明底色，其余情况用不透明底色。
     * 注意不能改 View 的 alpha——那样文字也会被一起洗淡，圆角和描边也会糊掉。
     */
    /** 未开启时显示居中的品牌水印，开播后淡出。 */
    private fun showWatermark(show: Boolean) {
        if (show) {
            logoWatermark.visibility = View.VISIBLE
            logoWatermark.animate().alpha(0.95f).setDuration(180).start()
        } else {
            logoWatermark.animate().alpha(0f).setDuration(180).withEndAction {
                logoWatermark.visibility = View.GONE
            }.start()
        }
    }

    private fun updatePanelStyle() {
        val translucent = running && !expanded
        panel.setBackgroundResource(
            if (translucent) R.drawable.bg_sheet_glass else R.drawable.bg_sheet
        )
    }

    /**
     * 把手拖动：面板高度跟手变化（底边固定、上边跟着手指走），松手后吸附到展开/收起。
     */
    private fun setupDrag() {
        val handle = findViewById<View>(R.id.handleArea) ?: return
        handle.setOnTouchListener { view, event ->
            val motion = event ?: return@setOnTouchListener false
            when (motion.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    dragStartY = motion.rawY
                    dragStartHeight = panel.height
                    dragFromExpanded = expanded
                    dragExtra = if (expanded) settingsBox.height else measureSettingsHeight()
                    dragExpandedHeight = if (expanded) dragStartHeight else dragStartHeight + dragExtra
                    dragCollapsedHeight = if (expanded) dragStartHeight - dragExtra else dragStartHeight
                    panel.animate().cancel()
                    (view as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    val delta = motion.rawY - dragStartY
                    val target = (dragStartHeight - delta.toInt())
                        .coerceIn(dragCollapsedHeight, dragExpandedHeight)
                    if (!dragFromExpanded && target > dragStartHeight &&
                        settingsBox.visibility != View.VISIBLE
                    ) {
                        settingsBox.visibility = View.VISIBLE
                    }
                    panel.layoutParams = panel.layoutParams.apply { height = target }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return@setOnTouchListener false
                    dragging = false
                    val delta = motion.rawY - dragStartY
                    val threshold = Ui.dp(this, 24)
                    val expand = if (dragFromExpanded) delta <= threshold else delta < -threshold
                    settle(expand)
                    true
                }

                else -> false
            }
        }
    }

    /** GONE 状态下也能量出设置区高度，避免拖动时面板跳变。 */
    private fun measureSettingsHeight(): Int {
        val width = (panel.width - panel.paddingLeft - panel.paddingRight).coerceAtLeast(0)
        settingsBox.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return settingsBox.measuredHeight
    }

    /** 松手后把高度动画到目标，再恢复 wrap_content 并落状态。 */
    private fun settle(expand: Boolean) {
        val end = if (expand) dragExpandedHeight else dragCollapsedHeight
        val animator = android.animation.ValueAnimator.ofInt(panel.height, end)
        animator.duration = 160
        animator.addUpdateListener { value ->
            panel.layoutParams = panel.layoutParams.apply { height = value.animatedValue as Int }
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                panel.layoutParams = panel.layoutParams.apply {
                    height = FrameLayout.LayoutParams.WRAP_CONTENT
                }
                settingsBox.visibility = if (expand) View.VISIBLE else View.GONE
                expanded = expand
                txtExpand.setText(if (expand) R.string.collapse_settings else R.string.expand_settings)
                iconExpand.animate().rotation(if (expand) 180f else 0f).setDuration(160).start()
                updatePanelStyle()
            }
        })
        animator.start()
    }

    private fun copyUrl() {
        val text = urlText.text?.toString().orEmpty()
        if (!text.startsWith("rtsp://")) {
            Ui.toast(this, getString(R.string.state_idle))
            return
        }
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("RTSP", text))
        Ui.toast(this, getString(R.string.copied))
    }

    private fun onToggleClicked() {
        val streamService = service
        if (streamService != null && streamService.isRunning()) {
            StreamService.stop(this)
            return
        }
        if (!hasCorePermissions()) {
            permissionLauncher.launch(requiredPermissions())
            return
        }
        StreamService.start(this)
    }

    private fun pickCamera() {
        val config = Prefs.load(this)
        if (cameras.isEmpty()) {
            cameras = CameraCaps.cameras(this)
        }
        if (cameras.isEmpty()) {
            Ui.toast(this, getString(R.string.permission_needed))
            return
        }
        val selected = cameras.indexOfFirst { it.id == config.cameraId }.coerceAtLeast(0)
        Ui.picker(this, getString(R.string.label_camera), cameras.map { it.label }, selected) { index ->
            val camera = cameras[index]
            val next = normalizeForCamera(
                Prefs.update(this) { it.copy(cameraId = camera.id) }
            )
            if (next.fps != Prefs.load(this).fps || next.width != Prefs.load(this).width) {
                Prefs.save(this, next)
                Ui.toast(this, getString(R.string.settings_adjusted))
            }
            refreshUiFromConfig()
            service?.applyConfig(next)
        }
    }

    private fun pickResolution() {
        if (resolutions.isEmpty()) return
        val config = Prefs.load(this)
        val selected = resolutions.indexOfFirst {
            it.width == config.width && it.height == config.height
        }.coerceAtLeast(0)
        Ui.picker(
            this,
            getString(R.string.label_resolution),
            resolutions.map { "${it.width}x${it.height}" },
            selected
        ) { index ->
            val size = resolutions[index]
            val base = Prefs.update(this) { it.copy(width = size.width, height = size.height) }
            val next = normalizeFps(base)
            if (next.fps != base.fps) {
                Prefs.save(this, next)
                Ui.toast(this, getString(R.string.fps_adjusted, next.fps))
            }
            refreshUiFromConfig()
            service?.applyConfig(next)
        }
    }

    private fun pickFps() {
        val config = Prefs.load(this)
        if (fpsOptions.isEmpty()) fpsOptions = listOf(config.fps)
        val selected = fpsOptions.indexOf(config.fps).coerceAtLeast(0)
        Ui.picker(
            this,
            getString(R.string.label_fps),
            fpsOptions.map { "$it fps" },
            selected
        ) { index ->
            val next = Prefs.update(this) { it.copy(fps = fpsOptions[index]) }
            refreshUiFromConfig()
            service?.applyConfig(next)
        }
    }

    override fun onState(state: StreamService.State) {
        running = state.running
        if (state.running) {
            showWatermark(false)
            if (preview.visibility != View.VISIBLE) preview.visibility = View.VISIBLE
            txtState.setText(R.string.state_live)
            tintDot(R.color.accent)
            urlText.text = state.url
            txtMeta.text = buildMeta(state)
            updatePanelStyle()
            btnToggle.setText(R.string.btn_stop)
            btnToggle.setBackgroundResource(R.drawable.bg_button_danger)
            btnToggle.setTextColor(ContextCompat.getColor(this, R.color.danger))
            if (!previewAttached) attachPreviewIfPossible()
        } else {
            showWatermark(true)
            if (preview.visibility != View.INVISIBLE) preview.visibility = View.INVISIBLE
            previewAttached = false
            txtState.setText(R.string.state_idle)
            tintDot(R.color.text_tertiary)
            updatePanelStyle()
            btnToggle.setText(R.string.btn_start)
            btnToggle.setBackgroundResource(R.drawable.bg_button_primary)
            btnToggle.setTextColor(ContextCompat.getColor(this, R.color.on_accent))
            val config = Prefs.load(this)
            urlText.text = "rtsp://—:${config.port}/"
            txtMeta.text = state.message.ifBlank {
                "${config.codecLabel} · :${config.port} · ${config.bitrate / 1000} kbps"
            }
        }
    }

    private fun buildMeta(state: StreamService.State): String {
        val builder = StringBuilder()
        builder.append(state.resolution).append(" · ").append(state.fps).append("fps")
        builder.append(" · 客户端 ").append(state.clients)
        if (state.bitrate > 0L) {
            builder.append(" · ")
            builder.append(String.format(Locale.US, "%.1f Mbps", state.bitrate / 1_000_000f))
        }
        if (state.lowPower) builder.append(" · 省电档")
        return builder.toString()
    }

    private fun tintDot(colorRes: Int) {
        statusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun attachPreviewIfPossible() {
        val streamService = service ?: return
        if (!streamService.isRunning()) return
        val holder = preview.holder
        if (!holder.surface.isValid) return
        val width = if (preview.width > 0) preview.width else 1080
        val height = if (preview.height > 0) preview.height else 1920
        streamService.attachPreview(holder.surface, width, height)
        previewAttached = true
    }

    private fun hasCorePermissions(): Boolean =
        hasPermission(Manifest.permission.CAMERA) && hasPermission(Manifest.permission.RECORD_AUDIO)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    private fun maxProgress(): Int = (BITRATE_MAX - BITRATE_MIN) / BITRATE_STEP

    private fun bitrateForProgress(progress: Int): Int =
        (BITRATE_MIN + progress.coerceIn(0, maxProgress()) * BITRATE_STEP)
            .coerceIn(BITRATE_MIN, BITRATE_MAX)

    private fun progressForBitrate(bitrate: Int): Int =
        ((bitrate - BITRATE_MIN) / BITRATE_STEP).coerceIn(0, maxProgress())

    private fun formatBitrate(bitsPerSecond: Int): String =
        String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000f)
}