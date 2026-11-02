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
import android.widget.SeekBar
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

open class SettingsActivity : AppCompatActivity() {

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
    private lateinit var txtNight: TextView
    private lateinit var switchDenoise: SwitchCompat
    private lateinit var boxDenoiseLevel: View
    private lateinit var seekDenoise: SeekBar
    private lateinit var txtDenoiseLevel: TextView
    private lateinit var switchHttp: SwitchCompat
    private lateinit var rowHttpPort: View
    private lateinit var rowHttpUser: View
    private lateinit var rowHttpPass: View
    private lateinit var txtHttpPort: TextView
    private lateinit var txtHttpUrl: TextView
    private lateinit var txtHttpUser: TextView
    private lateinit var txtHttpPass: TextView
    private lateinit var switchOsd: SwitchCompat
    private lateinit var switchOsdDate: SwitchCompat
    private lateinit var switchOsdBattery: SwitchCompat
    private lateinit var switchOsdBatteryIcon: SwitchCompat
    private lateinit var rowOsdDateFormat: View
    private lateinit var rowOsdPosition: View
    private lateinit var rowOsdRotation: View
    private lateinit var rowOsdFlip: View
    private lateinit var txtOsdDateFormat: TextView
    private lateinit var txtOsdPosition: TextView
    private lateinit var txtOsdRotation: TextView
    private lateinit var txtOsdFlip: TextView

    /** 代码里批量设置控件状态时，屏蔽监听器回调。 */
    private var suppress = false

    /** 网页端改完配置后，设置页立即刷新显示。 */
    private val configReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 在 super 之前设定方向，避免先按竖屏布局一次再旋转（会闪一下）
        requestedOrientation = if (Prefs.load(this).uiLandscape) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            configReceiver,
            android.content.IntentFilter(StreamService.ACTION_CONFIG_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

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
        txtNight = findViewById(R.id.txtNight)
        switchDenoise = findViewById(R.id.switchDenoise)
        boxDenoiseLevel = findViewById(R.id.boxDenoiseLevel)
        seekDenoise = findViewById(R.id.seekDenoise)
        txtDenoiseLevel = findViewById(R.id.txtDenoiseLevel)
        switchHttp = findViewById(R.id.switchHttp)
        rowHttpPort = findViewById(R.id.rowHttpPort)
        rowHttpUser = findViewById(R.id.rowHttpUser)
        rowHttpPass = findViewById(R.id.rowHttpPass)
        txtHttpPort = findViewById(R.id.txtHttpPort)
        txtHttpUrl = findViewById(R.id.txtHttpUrl)
        txtHttpUser = findViewById(R.id.txtHttpUser)
        txtHttpPass = findViewById(R.id.txtHttpPass)
        switchOsd = findViewById(R.id.switchOsd)
        switchOsdDate = findViewById(R.id.switchOsdDate)
        switchOsdBattery = findViewById(R.id.switchOsdBattery)
        switchOsdBatteryIcon = findViewById(R.id.switchOsdBatteryIcon)
        rowOsdDateFormat = findViewById(R.id.rowOsdDateFormat)
        rowOsdPosition = findViewById(R.id.rowOsdPosition)
        rowOsdRotation = findViewById(R.id.rowOsdRotation)
        rowOsdFlip = findViewById(R.id.rowOsdFlip)
        txtOsdDateFormat = findViewById(R.id.txtOsdDateFormat)
        txtOsdPosition = findViewById(R.id.txtOsdPosition)
        txtOsdRotation = findViewById(R.id.txtOsdRotation)
        txtOsdFlip = findViewById(R.id.txtOsdFlip)

        switchOsd.setOnCheckedChangeListener { _, checked ->
            if (!suppress) save { it.copy(osdEnabled = checked) }
        }
        switchOsdDate.setOnCheckedChangeListener { _, checked ->
            if (!suppress) save { it.copy(osdDate = checked) }
        }
        switchOsdBattery.setOnCheckedChangeListener { _, checked ->
            if (!suppress) save { it.copy(osdBattery = checked) }
        }
        switchOsdBatteryIcon.setOnCheckedChangeListener { _, checked ->
            if (!suppress) save { it.copy(osdBatteryIcon = checked) }
        }
        rowOsdDateFormat.setOnClickListener { pickOsdDateFormat() }
        rowOsdPosition.setOnClickListener { pickOsdPosition() }
        rowOsdRotation.setOnClickListener { pickOsdRotation() }
        rowOsdFlip.setOnClickListener { pickOsdFlip() }

        switchHttp.setOnCheckedChangeListener { _, checked ->
            if (!suppress) save { it.copy(httpEnabled = checked) }
        }

        switchDenoise.setOnCheckedChangeListener { _, checked ->
            if (!suppress) save { it.copy(denoiseEnabled = checked) }
            boxDenoiseLevel.visibility = if (checked) View.VISIBLE else View.GONE
        }
        seekDenoise.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                txtDenoiseLevel.text = "$progress%"
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) {
                val level = bar?.progress ?: return
                save { it.copy(denoiseLevel = level) }
            }
        })

        setupEdgeToEdge()
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<View>(R.id.rowCodec).setOnClickListener { pickCodec() }
        findViewById<View>(R.id.rowEncoder).setOnClickListener { pickEncoder() }
        findViewById<View>(R.id.rowNight).setOnClickListener { pickNight() }
        findViewById<View>(R.id.rowPort).setOnClickListener { editPort() }
        findViewById<View>(R.id.rowKeepAlive).setOnClickListener { showKeepAlive() }
        findViewById<View>(R.id.rowDiagnose).setOnClickListener {
            val target = if (Prefs.load(this).uiLandscape) {
                DiagnoseLandscapeActivity::class.java
            } else {
                DiagnosePortraitActivity::class.java
            }
            startActivity(Intent(this, target))
        }
        findViewById<View>(R.id.rowGuide).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        rowUser.setOnClickListener { editUser() }
        rowPass.setOnClickListener { editPassword() }
        rowHttpPort.setOnClickListener { editHttpPort() }
        rowHttpUser.setOnClickListener { editHttpUser() }
        rowHttpPass.setOnClickListener { editHttpPassword() }

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

    private fun pickNight() {
        val config = Prefs.load(this)
        val options = listOf(
            getString(R.string.night_off),
            getString(R.string.night_standard),
            getString(R.string.night_strong)
        )
        Ui.picker(this, getString(R.string.night_title), options, config.nightMode) { index ->
            save { it.copy(nightMode = index) }
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
        txtNight.text = getString(
            when (config.nightMode) {
                StreamConfig.NIGHT_STANDARD -> R.string.night_standard
                StreamConfig.NIGHT_STRONG -> R.string.night_strong
                else -> R.string.night_off
            }
        )
        txtEncoderInfo.text = if (codecInfo == null) {
            getString(R.string.encoder_none)
        } else {
            getString(R.string.encoder_info_fmt, codecInfo.label)
        }
        txtPort.text = config.port.toString()
        txtHttpPort.text = config.httpPort.toString()
        val localIp = NetInfo.localIpv4()
        txtHttpUrl.text = if (localIp.isEmpty()) {
            getString(R.string.http_enable_hint)
        } else {
            getString(R.string.http_url_hint, "http://$localIp:${config.httpPort}")
        }
        txtHttpUser.text = config.httpUser
        txtHttpPass.text = if (config.httpPass.isEmpty()) "未设置" else "•".repeat(config.httpPass.length.coerceAtMost(10))
        txtUser.text = config.username
        txtPass.text = if (config.password.isEmpty()) "未设置" else "•".repeat(config.password.length.coerceAtMost(10))

        suppress = true
        switchPower.isChecked = config.powerSaving
        switchAuth.isChecked = config.authEnabled
        switchDenoise.isChecked = config.denoiseEnabled
        seekDenoise.progress = config.denoiseLevel
        switchHttp.isChecked = config.httpEnabled
        suppress = false
        txtDenoiseLevel.text = "${config.denoiseLevel}%"
        boxDenoiseLevel.visibility = if (config.denoiseEnabled) View.VISIBLE else View.GONE

        val alpha = if (config.authEnabled) 1f else 0.4f
        rowUser.alpha = alpha
        rowPass.alpha = alpha
        rowUser.isEnabled = config.authEnabled
        rowPass.isEnabled = config.authEnabled

        val httpAlpha = if (config.httpEnabled) 1f else 0.4f
        listOf(rowHttpPort, rowHttpUser, rowHttpPass).forEach {
            it.alpha = httpAlpha
            it.isEnabled = config.httpEnabled
        }

        suppress = true
        switchOsd.isChecked = config.osdEnabled
        switchOsdDate.isChecked = config.osdDate
        switchOsdBattery.isChecked = config.osdBattery
        switchOsdBatteryIcon.isChecked = config.osdBatteryIcon
        suppress = false
        txtOsdDateFormat.text = OsdOverlay.DATE_FORMATS[
            config.osdDateFormat.coerceIn(0, OsdOverlay.DATE_FORMATS.size - 1)
        ]
        txtOsdPosition.text = getString(
            when (config.osdPosition) {
                0 -> R.string.osd_pos_tl
                2 -> R.string.osd_pos_bl
                3 -> R.string.osd_pos_br
                else -> R.string.osd_pos_tr
            }
        )
        txtOsdRotation.text = "${config.osdRotation}°"
        txtOsdFlip.text = getString(
            when (config.osdFlip) {
                1 -> R.string.osd_flip_h
                2 -> R.string.osd_flip_v
                3 -> R.string.osd_flip_hv
                else -> R.string.osd_flip_none
            }
        )
        val osdAlpha = if (config.osdEnabled) 1f else 0.4f
        listOf(rowOsdDateFormat, rowOsdPosition, rowOsdRotation, rowOsdFlip).forEach {
            it.alpha = osdAlpha
            it.isEnabled = config.osdEnabled
        }
        switchOsdDate.isEnabled = config.osdEnabled
        switchOsdDate.alpha = osdAlpha
        switchOsdBattery.isEnabled = config.osdEnabled
        switchOsdBattery.alpha = osdAlpha
        switchOsdBatteryIcon.isEnabled = config.osdEnabled && config.osdBattery
        switchOsdBatteryIcon.alpha = if (config.osdEnabled && config.osdBattery) 1f else 0.4f
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
                when {
                    port == null || port !in 1024..65535 -> getString(R.string.msg_invalid_port)
                    config.httpEnabled && port in config.httpPort..(config.httpPort + 2) ->
                        getString(R.string.msg_port_conflict)
                    else -> null
                }
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
            tip = getString(R.string.password_tip),
            password = true,
            validate = { value -> if (value.isBlank()) getString(R.string.msg_invalid_auth) else null }
        ) { value ->
            save { it.copy(password = value) }
        }
    }

    private fun editHttpPort() {
        val config = Prefs.load(this)
        Ui.input(
            this,
            getString(R.string.http_port),
            config.httpPort.toString(),
            hint = "8080",
            numeric = true,
            validate = { value ->
                val port = value.toIntOrNull()
                when {
                    port == null || port !in 1024..65535 -> getString(R.string.msg_invalid_http_port)
                    port in config.port..(config.port + 2) || config.port in port..(port + 2) ->
                        getString(R.string.msg_port_conflict)
                    else -> null
                }
            }
        ) { value ->
            save { it.copy(httpPort = value.toInt()) }
        }
    }

    private fun editHttpUser() {
        val config = Prefs.load(this)
        Ui.input(
            this,
            getString(R.string.http_user),
            config.httpUser,
            validate = { value -> if (value.isBlank()) getString(R.string.msg_invalid_auth) else null }
        ) { value ->
            save { it.copy(httpUser = value) }
        }
    }

    private fun editHttpPassword() {
        val config = Prefs.load(this)
        Ui.input(
            this,
            getString(R.string.http_pass),
            config.httpPass,
            tip = getString(R.string.password_tip),
            password = true,
            validate = { value -> if (value.isBlank()) getString(R.string.msg_invalid_auth) else null }
        ) { value ->
            save { it.copy(httpPass = value) }
        }
    }

    private fun pickOsdDateFormat() {
        val config = Prefs.load(this)
        Ui.picker(this, getString(R.string.osd_date_format), OsdOverlay.DATE_FORMATS.toList(), config.osdDateFormat) { index ->
            save { it.copy(osdDateFormat = index) }
        }
    }

    private fun pickOsdPosition() {
        val config = Prefs.load(this)
        val options = listOf(
            getString(R.string.osd_pos_tl),
            getString(R.string.osd_pos_tr),
            getString(R.string.osd_pos_bl),
            getString(R.string.osd_pos_br)
        )
        Ui.picker(this, getString(R.string.osd_position), options, config.osdPosition) { index ->
            save { it.copy(osdPosition = index) }
        }
    }

    private fun pickOsdRotation() {
        val config = Prefs.load(this)
        val options = listOf("0°", "90°", "180°", "270°")
        Ui.picker(this, getString(R.string.osd_rotation), options, config.osdRotation / 90) { index ->
            save { it.copy(osdRotation = index * 90) }
        }
    }

    private fun pickOsdFlip() {
        val config = Prefs.load(this)
        val options = listOf(
            getString(R.string.osd_flip_none),
            getString(R.string.osd_flip_h),
            getString(R.string.osd_flip_v),
            getString(R.string.osd_flip_hv)
        )
        Ui.picker(this, getString(R.string.osd_flip), options, config.osdFlip) { index ->
            save { it.copy(osdFlip = index) }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(configReceiver) }
        super.onDestroy()
    }
}