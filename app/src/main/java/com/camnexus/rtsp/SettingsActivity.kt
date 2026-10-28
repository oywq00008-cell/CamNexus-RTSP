package com.camnexus.rtsp

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class SettingsActivity : AppCompatActivity() {

    private lateinit var header: View
    private lateinit var txtCodec: TextView
    private lateinit var txtEncoder: TextView
    private lateinit var txtEncoderInfo: TextView
    private lateinit var txtPort: TextView
    private lateinit var txtUser: TextView
    private lateinit var txtPass: TextView
    private lateinit var rowUser: View
    private lateinit var rowPass: View
    private lateinit var switchPower: SwitchCompat
    private lateinit var switchAuth: SwitchCompat
    private lateinit var txtKeepAlive: TextView

    /** 代码里批量设置控件状态时，屏蔽监听器回调。 */
    private var suppress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        header = findViewById(R.id.header)
        txtCodec = findViewById(R.id.txtCodec)
        txtEncoder = findViewById(R.id.txtEncoder)
        txtEncoderInfo = findViewById(R.id.txtEncoderInfo)
        txtPort = findViewById(R.id.txtPort)
        txtUser = findViewById(R.id.txtUser)
        txtPass = findViewById(R.id.txtPass)
        rowUser = findViewById(R.id.rowUser)
        rowPass = findViewById(R.id.rowPass)
        switchPower = findViewById(R.id.switchPower)
        switchAuth = findViewById(R.id.switchAuth)
        txtKeepAlive = findViewById(R.id.txtKeepAlive)

        setupEdgeToEdge()
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<View>(R.id.rowCodec).setOnClickListener { pickCodec() }
        findViewById<View>(R.id.rowEncoder).setOnClickListener { pickEncoder() }
        findViewById<View>(R.id.rowPort).setOnClickListener { editPort() }
        findViewById<View>(R.id.rowKeepAlive).setOnClickListener { showKeepAlive() }
        findViewById<View>(R.id.rowDiagnose).setOnClickListener {
            startActivity(Intent(this, DiagnoseActivity::class.java))
        }
        findViewById<View>(R.id.rowGuide).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        rowUser.setOnClickListener { editUser() }
        rowPass.setOnClickListener { editPassword() }

        switchPower.setOnCheckedChangeListener { _, checked ->
            if (!suppress) save { it.copy(powerSaving = checked) }
        }
        switchAuth.setOnCheckedChangeListener { _, checked ->
            if (!suppress) save { it.copy(authEnabled = checked) }
        }

        findViewById<TextView>(R.id.txtVersion).text = getString(
            R.string.version_fmt, runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
                .getOrNull() ?: "—"
        )

        render()
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

    override fun onResume() {
        super.onResume()
        updateKeepAlive()
    }

    private fun isIgnoringBattery(): Boolean {
        // isIgnoringBatteryOptimizations 是 API 23 才有的，Android 5.x 上直接调用会 NoSuchMethodError
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateKeepAlive() {
        val ok = isIgnoringBattery()
        txtKeepAlive.setText(if (ok) R.string.keepalive_ok else R.string.keepalive_todo)
        txtKeepAlive.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.accent else R.color.text_tertiary)
        )
    }

    private fun showKeepAlive() {
        val status = getString(if (isIgnoringBattery()) R.string.keepalive_ok else R.string.keepalive_todo)
        AlertDialog.Builder(this)
            .setTitle(R.string.keepalive_title)
            .setMessage(status + "\n\n" + brandHint())
            .setPositiveButton(R.string.keepalive_battery) { _, _ -> requestIgnoreBattery() }
            .setNeutralButton(R.string.keepalive_go) { _, _ -> openAppDetails() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, R.string.keepalive_old_system, Toast.LENGTH_LONG).show()
            return
        }
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(direct) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun openAppDetails() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    /** 按厂商给出具体的后台保活路径，国产 ROM 的入口差异很大。 */
    private fun brandHint(): String {
        val id = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return when {
            id.contains("huawei") || id.contains("honor") -> getString(R.string.keepalive_huawei)
            id.contains("xiaomi") || id.contains("redmi") || id.contains("poco") -> getString(R.string.keepalive_xiaomi)
            id.contains("zte") || id.contains("nubia") -> getString(R.string.keepalive_zte)
            id.contains("oppo") || id.contains("realme") || id.contains("oneplus") -> getString(R.string.keepalive_oppo)
            id.contains("vivo") || id.contains("iqoo") -> getString(R.string.keepalive_vivo)
            else -> getString(R.string.keepalive_generic)
        }
    }

    private fun render() {
        val config = Prefs.load(this)
        txtCodec.text = config.codecLabel
        txtEncoder.text = when (config.encoder) {
            StreamConfig.ENCODER_HARDWARE -> getString(R.string.encoder_hardware)
            StreamConfig.ENCODER_SOFTWARE -> getString(R.string.encoder_software)
            else -> getString(R.string.encoder_auto)
        }
        val mime = if (config.codec == StreamConfig.CODEC_H265) Codecs.MIME_H265 else Codecs.MIME_H264
        val codecInfo = Codecs.find(mime, preferHardware = config.encoder != StreamConfig.ENCODER_SOFTWARE)
        txtEncoderInfo.text = if (codecInfo == null) {
            getString(R.string.encoder_none)
        } else {
            getString(R.string.encoder_info_fmt, codecInfo.label)
        }
        txtPort.text = config.port.toString()
        txtUser.text = config.username
        txtPass.text = if (config.password.isEmpty()) "未设置" else "•".repeat(config.password.length.coerceAtMost(10))

        suppress = true
        switchPower.isChecked = config.powerSaving
        switchAuth.isChecked = config.authEnabled
        suppress = false

        val alpha = if (config.authEnabled) 1f else 0.4f
        rowUser.alpha = alpha
        rowPass.alpha = alpha
        rowUser.isEnabled = config.authEnabled
        rowPass.isEnabled = config.authEnabled
    }

    private fun save(block: (StreamConfig) -> StreamConfig) {
        Prefs.update(this, block)
        render()
        // 正在推流时让服务立刻用新配置重建一次
        StreamService.apply(this)
    }

    private fun pickCodec() {
        val config = Prefs.load(this)
        val options = listOf("H.264", "H.265")
        val selected = if (config.codec == StreamConfig.CODEC_H265) 1 else 0
        Ui.picker(this, getString(R.string.label_codec), options, selected) { index ->
            val allowed = if (index != 1) {
                true
            } else {
                val info = Codecs.find(Codecs.MIME_H265, preferHardware = true)
                when {
                    info == null -> {
                        Ui.toast(this, getString(R.string.codec_h265_unsupported))
                        false
                    }
                    !info.hardware -> {
                        Ui.toast(this, getString(R.string.codec_h265_software))
                        true
                    }
                    else -> true
                }
            }
            if (allowed) {
                save {
                    it.copy(codec = if (index == 1) StreamConfig.CODEC_H265 else StreamConfig.CODEC_H264)
                }
            }
        }
    }

    private fun pickEncoder() {
        val config = Prefs.load(this)
        val labels = listOf(
            getString(R.string.encoder_auto),
            getString(R.string.encoder_hardware),
            getString(R.string.encoder_software)
        )
        val values = listOf(
            StreamConfig.ENCODER_AUTO,
            StreamConfig.ENCODER_HARDWARE,
            StreamConfig.ENCODER_SOFTWARE
        )
        val selected = values.indexOf(config.encoder).coerceAtLeast(0)
        Ui.picker(this, getString(R.string.label_encoder), labels, selected) { index ->
            save { it.copy(encoder = values[index]) }
        }
    }

    private fun editPort() {
        val config = Prefs.load(this)
        Ui.input(
            this,
            getString(R.string.label_port),
            config.port.toString(),
            hint = "8554",
            numeric = true,
            validate = { value ->
                val port = value.toIntOrNull()
                if (port == null || port !in 1024..65535) getString(R.string.msg_invalid_port) else null
            }
        ) { value ->
            save { it.copy(port = value.toInt()) }
        }
    }

    private fun editUser() {
        val config = Prefs.load(this)
        Ui.input(
            this,
            getString(R.string.label_username),
            config.username,
            validate = { value -> if (value.isBlank()) getString(R.string.msg_invalid_auth) else null }
        ) { value ->
            save { it.copy(username = value) }
        }
    }

    private fun editPassword() {
        val config = Prefs.load(this)
        Ui.input(
            this,
            getString(R.string.label_password),
            config.password,
            password = true,
            validate = { value -> if (value.isBlank()) getString(R.string.msg_invalid_auth) else null }
        ) { value ->
            save { it.copy(password = value) }
        }
    }
}
