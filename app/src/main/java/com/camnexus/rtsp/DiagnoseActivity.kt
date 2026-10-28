package com.camnexus.rtsp

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * 设备检测：读取本机编码器/相机/内存/网络能力，给出一组推荐参数。
 * 全部在本地完成，不上传任何数据。
 */
class DiagnoseActivity : AppCompatActivity() {

    private lateinit var header: View
    private lateinit var cardInfo: LinearLayout
    private lateinit var txtRecommend: TextView
    private lateinit var txtReasons: TextView
    private lateinit var btnApply: Button

    private var recommendation: Recommendation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnose)

        header = findViewById(R.id.header)
        cardInfo = findViewById(R.id.cardInfo)
        txtRecommend = findViewById(R.id.txtRecommend)
        txtReasons = findViewById(R.id.txtReasons)
        btnApply = findViewById(R.id.btnApply)

        setupEdgeToEdge()
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val config = Prefs.load(this)
        val profile = DeviceProbe.collect(this, config.cameraId)
        val sizes = CameraCaps.resolutions(this, config.cameraId)
        val result = Recommender.build(config, profile, sizes)
        recommendation = result

        renderProfile(profile)
        renderRecommendation(result)

        btnApply.setOnClickListener {
            Prefs.save(this, result.config)
            StreamService.apply(this)
            Ui.toast(this, getString(R.string.diagnose_applied))
            finish()
        }
    }

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
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.header)) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            header.updatePadding(top = safe.top, left = Ui.dp(this, 8), right = Ui.dp(this, 20))
            insets
        }
    }

    private fun renderProfile(profile: DeviceProfile) {
        cardInfo.removeAllViews()
        addRow(getString(R.string.info_soc), "${profile.soc} · ${profile.cores} 核")
        addRow(getString(R.string.info_architecture), profile.abi)
        val ramGb = String.format(java.util.Locale.US, "%.1f GB", profile.ramMb / 1024.0)
        addRow(
            getString(R.string.info_ram),
            if (profile.lowRam) "$ramGb（低内存）" else ramGb
        )
        addRow(getString(R.string.info_h264), profile.h264?.label ?: getString(R.string.unknown))
        addRow(getString(R.string.info_h265), profile.h265?.label ?: getString(R.string.unknown))
        addRow(
            getString(R.string.info_camera),
            "${profile.cameraMaxSize.width}x${profile.cameraMaxSize.height} · ${profile.cameraMaxFps}fps"
        )
        addRow(
            getString(R.string.info_wifi),
            "${profile.wifiBand} · ${profile.wifiLinkMbps}Mbps · ${profile.wifiRssi}dBm"
        )
    }

    private fun addRow(label: String, value: String) {
        val row = layoutInflater.inflate(R.layout.item_info, cardInfo, false)
        row.findViewById<TextView>(R.id.infoLabel).text = label
        row.findViewById<TextView>(R.id.infoValue).text = value
        cardInfo.addView(row)
    }

    private fun renderRecommendation(result: Recommendation) {
        val config = result.config
        txtRecommend.text = buildString {
            append(config.resolutionText)
            append(" · ").append(config.fps).append("fps")
            append(" · ").append(config.bitrate / 1000).append("kbps")
            append(" · ").append(config.codecLabel)
        }
        txtReasons.text = result.reasons.joinToString("\n") { "· $it" }
    }
}