package com.camnexus.rtsp

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * 首次启动的新手引导：品牌 -> 界面 -> 功能 -> 权限检测与授予。
 * 零额外依赖，翻页用 ViewFlipper + 平台动画。
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val PAGE_COUNT = 4
    }

    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var flipper: ViewFlipper
    private lateinit var btnNext: Button
    private lateinit var btnSkip: View
    private lateinit var dots: LinearLayout
    private lateinit var txtPermCamera: TextView
    private lateinit var txtPermMic: TextView
    private lateinit var txtPermNotification: TextView
    private lateinit var txtBattery: TextView

    private var index = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            renderPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        flipper = findViewById(R.id.flipper)
        btnNext = findViewById(R.id.btnNext)
        btnSkip = findViewById(R.id.btnSkip)
        dots = findViewById(R.id.dots)
        txtPermCamera = findViewById(R.id.txtPermCamera)
        txtPermMic = findViewById(R.id.txtPermMic)
        txtPermNotification = findViewById(R.id.txtPermNotification)
        txtBattery = findViewById(R.id.txtBattery)

        setupEdgeToEdge()
        buildDots()
        btnNext.setOnClickListener { goNext() }
        btnSkip.setOnClickListener { finishGuide() }
        findViewById<View>(R.id.btnGrant).setOnClickListener { requestPermissions() }
        findViewById<View>(R.id.rowBattery).setOnClickListener { openBatterySettings() }

        render()
        renderPermissions()
    }

    override fun onResume() {
        super.onResume()
        renderPermissions()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (index > 0) {
            flipper.inAnimation = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.slide_in_left)
            flipper.outAnimation = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.slide_out_right)
            flipper.showPrevious()
            index--
            render()
        } else {
            super.onBackPressed()
        }
    }

    /**
     * Android 15 起强制边到边，不处理 insets 就会画到状态栏/导航栏底下。
     * 顶部让出状态栏，底部让出导航栏（含三键导航）。
     */
    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
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
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBar.updatePadding(top = safe.top)
            bottomBar.updatePadding(bottom = safe.bottom + Ui.dp(this, 24))
            insets
        }
    }

    private fun goNext() {
        if (index >= PAGE_COUNT - 1) {
            finishGuide()
            return
        }
        flipper.inAnimation =
            android.view.animation.AnimationUtils.loadAnimation(this, R.anim.slide_in_right)
        flipper.outAnimation =
            android.view.animation.AnimationUtils.loadAnimation(this, R.anim.slide_out_left)
        flipper.showNext()
        index++
        render()
    }

    private fun render() {
        btnNext.setText(if (index == PAGE_COUNT - 1) R.string.guide_start else R.string.guide_next)
        btnSkip.visibility = if (index == PAGE_COUNT - 1) View.GONE else View.VISIBLE
        updateDots()
    }

    private fun buildDots() {
        dots.removeAllViews()
        repeat(PAGE_COUNT) {
            val dot = View(this)
            val size = Ui.dp(this, 7)
            val params = LinearLayout.LayoutParams(size, size)
            params.marginStart = Ui.dp(this, 4)
            params.marginEnd = Ui.dp(this, 4)
            dot.layoutParams = params
            dot.background = ContextCompat.getDrawable(this, R.drawable.bg_dot)
            dots.addView(dot)
        }
    }

    private fun updateDots() {
        for (i in 0 until dots.childCount) {
            val color = if (i == index) R.color.accent else R.color.stroke_strong
            dots.getChildAt(i).backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, color))
        }
    }

    private fun finishGuide() {
        Prefs.setOnboarded(this, true)
        finish()
    }

    private fun requestPermissions() {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(list.toTypedArray())
    }

    private fun renderPermissions() {
        setStatus(txtPermCamera, has(Manifest.permission.CAMERA))
        setStatus(txtPermMic, has(Manifest.permission.RECORD_AUDIO))
        val notice = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            has(Manifest.permission.POST_NOTIFICATIONS)
        setStatus(txtPermNotification, notice)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            txtBattery.setText(R.string.keepalive_old_system)
            txtBattery.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_tertiary))
            return
        }
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val ignoring = runCatching {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(false)
        txtBattery.setText(if (ignoring) R.string.battery_ok else R.string.battery_todo)
        txtBattery.setTextColor(
            ContextCompat.getColor(this, if (ignoring) R.color.accent else R.color.link)
        )
    }

    private fun setStatus(view: TextView, granted: Boolean) {
        view.setText(if (granted) R.string.perm_granted else R.string.perm_denied)
        view.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.accent else R.color.text_tertiary)
        )
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openBatterySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        val ok = runCatching { startActivity(request) }.isSuccess
        if (!ok) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}
