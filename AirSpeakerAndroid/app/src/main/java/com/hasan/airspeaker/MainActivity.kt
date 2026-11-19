package com.hasan.airspeaker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import android.media.projection.MediaProjectionManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hasan.airspeaker.databinding.ActivityMainBinding
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private var preStreamDiscoveryChannel: ControlChannel? = null
    private var lastServiceStartTimeMs = 0L
    private val handler = Handler(Looper.getMainLooper())

    private var mediaProjectionManager: MediaProjectionManager? = null
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val targetIp = binding.etServeTargetIp.text.toString().trim().ifBlank {
                binding.etPcIp.text.toString().trim().ifBlank { "255.255.255.255" }
            }
            stopPreStreamDiscovery()
            val intent = Intent(this, AudioStreamService::class.java).apply {
                action = AudioStreamService.ACTION_START_SERVE
                putExtra(AudioStreamService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioStreamService.EXTRA_RESULT_DATA, result.data)
                putExtra(AudioStreamService.EXTRA_TARGET_IP, targetIp)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            Toast.makeText(this, "Internal audio capture permission denied", Toast.LENGTH_SHORT).show()
        }
        handler.postDelayed({ refreshServiceButtonState() }, 200L)
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchMediaProjectionCapture()
        } else {
            Toast.makeText(this, "Audio permission is required by Android to capture internal system audio", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchMediaProjectionCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (mediaProjectionManager == null) {
                mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            }
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mediaProjectionManager!!.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                mediaProjectionManager!!.createScreenCaptureIntent()
            }
            Toast.makeText(this, "Android requires Screen Capture for internal audio (audio only — select Entire Screen)", Toast.LENGTH_LONG).show()
            screenCaptureLauncher.launch(captureIntent)
        } else {
            Toast.makeText(this, "Internal system audio capture requires Android 10+", Toast.LENGTH_LONG).show()
        }
    }

    private var partyModeScreen: String = "CHOOSER" // "CHOOSER", "HOST", "JOIN"
    private var isPartyJoinedActive: Boolean = false
    private var isPartyExtendedActive: Boolean = false
    private var isPartyHostBroadcastActive: Boolean = false
    private var joinedHostIp: String = ""
    private var joinedHostPort: Int = 5004
    private var lastDiscoveredHostsKey: String = ""
    private var lastJoinKeepAliveMs: Long = 0L

    private val qrScannerLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val raw = result.data?.getStringExtra("SCAN_RESULT")?.trim().orEmpty()
            if (raw.isNotEmpty()) {
                val (ip, port) = parseHostIpFromUri(raw)
                if (ip.isNotEmpty()) {
                    showPartyScreen("JOIN")
                    binding.rbPartyJoin.isChecked = true
                    joinedHostIp = ip
                    joinedHostPort = port
                    binding.tvPartyJoinStatus.text = "PARTY JOINER: Connecting to $ip:$port..."
                    Toast.makeText(this, "Party Host: $ip:$port — Connecting...", Toast.LENGTH_SHORT).show()
                    if (!AudioStreamService.isStreaming) {
                        binding.btnTogglePartyJoin.performClick()
                    } else {
                        ControlChannel.sendPartyJoinAnnouncement(ip)
                    }
                }
            }
        }
    }

    private fun launchQrScanner() {
        val intent = Intent(this, QrScannerActivity::class.java)
        qrScannerLauncher.launch(intent)
    }

    private var localRev = 1
    private var lastSyncId = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
    private val seenSyncIds = ArrayDeque<Int>(32)
    private var suppressSync = false
    private var currentFrameMs = 5
    private var userEditedPcIp: String? = null
    private var lastAppliedDiscoveredPcIp: String? = null
    private var lastAdvLogTime: Long = 0L
    private var userManuallyStoppedPlayer = false
    private var pcStreamingHintActive = false

    private val bitrateValues = listOf("10", "24", "32", "64", "96", "128", "160", "192", "256", "320")

    private fun computeExpectedDelayMs(): Pair<Int, String> {
        val isOpus = binding.rbCodecOpus.isChecked || binding.rbPlayerCodecOpus.isChecked
        val bitrate = bitrateValues.getOrElse(binding.spinnerBitrate.selectedItemPosition) { "128" }.toIntOrNull() ?: 128
        val frameMs = if (!isOpus) 5 else currentFrameMs
        val bufMs = quantizeTarget(AudioNative.nativeGetTargetBufferMs()).toInt()
        val captureMs = 10
        val hwMs = 10
        val (codecNetMs, qualityNote) = if (!isOpus) {
            Pair(3, "Lossless 1536 kbps")
        } else {
            when {
                bitrate <= 32 -> Pair(9, "Opus ${bitrate}k (Voice/Low BW)")
                bitrate <= 96 -> Pair(6, "Opus ${bitrate}k (Balanced)")
                bitrate <= 192 -> Pair(5, "Opus ${bitrate}k (High Quality)")
                else -> Pair(6, "Opus ${bitrate}k (Studio Max)")
            }
        }
        val totalMs = captureMs + frameMs + codecNetMs + bufMs + hwMs
        return Pair(totalMs, "Expected Delay: ~$totalMs ms ($qualityNote, ${frameMs}ms frame + ${bufMs}ms buf)")
    }

    private fun updateCodecBitrateUiState(isOpus: Boolean) {
        binding.spinnerBitrate.isEnabled = isOpus
        binding.spPlayerBitrate.isEnabled = isOpus
        binding.spinnerBitrate.alpha = if (isOpus) 1.0f else 0.45f
        binding.spPlayerBitrate.alpha = if (isOpus) 1.0f else 0.45f
        if (!isOpus) currentFrameMs = 5
    }

    private fun updateExpectedDelayUI() {
        val (_, text) = computeExpectedDelayMs()
        binding.tvExpectedDelay.text = text
    }

    private fun showHelpDialog() {
        val guideText = """
        ============================================================
        AIR SPEAKER v1 STUDIO — USER GUIDE & HELP
        ============================================================

        ✓ STUDIO ARCHITECTURE: 4 DEDICATED TABS
        ------------------------------------------------------------
        Air Speaker v1 features a unified monospace layout across
        Android, iOS, Windows, macOS, and Linux:

        1. [ PLAY ] TAB (PC -> PHONE AUDIO PLAYER)
        ------------------------------------------------------------
        * Tap [ ▶ START SPEAKER PLAYER (PORT 5004) ] on Phone and
          click [ ▶ START SPEAKER STREAM ] on PC.
        * Plays low-latency wireless audio from your computer.
        * Default codec: Lossless PCM16 48 kHz stereo (5 ms frames).
        * Target buffer slider: 10 ms to 150 ms (Prebuffer safety).
        * Live latency, packet loss, and jitter telemetry graph.

        2. [ SERVE ] TAB (PHONE INTERNAL AUDIO -> PC)
        ------------------------------------------------------------
        * Tap [ ⏺ CAPTURE & SERVE INTERNAL AUDIO ] to stream system
          audio (games, media, apps) from Android 10+ directly to PC.
        * Uses MediaProjection AudioPlaybackCaptureConfiguration.
        * Output streams on UDP port 5004 to PC or Party joiners.

        3. [ PARTY ] TAB (MULTI-DEVICE BROADCAST & RELAY)
        ------------------------------------------------------------
        * [ HOST PARTY ]: Broadcast internal audio to multiple
          phones and PCs simultaneously.
        * [ JOIN PARTY ]: Connect to an active party broadcast.
        * [ EXTEND VIA HOTSPOT ]: Daisy-chain relay audio to devices
          on your personal hotspot with clock-drift compensation.

        4. [ SETTINGS ] TAB (REAL-TIME 2-WAY STUDIO SYNC)
        ------------------------------------------------------------
        * Synchronizes settings in real-time over UDP port 5005.
        * Switch between Lossless PCM16 and Opus (10 - 320 kbps).
        * Studio EQ & Dynamic Limiter: Studio Flat, Vocal Boost,
          Bass Punch, Gaming Crisp.
        """.trimIndent()

        val tv = android.widget.TextView(this).apply {
            text = guideText
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF0A0A0A.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(32, 24, 32, 24)
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(tv)
            setBackgroundColor(0xFF0A0A0A.toInt())
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Air Speaker v1 Studio Guide")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private val statsRunnable = object : Runnable {
        override fun run() {
            updateStatsUI()
            handler.postDelayed(this, 200) // 5 Hz
        }
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioStreamService.ACTION_STATE_CHANGED -> {
                    refreshServiceButtonState()
                }
                AudioStreamService.ACTION_CONFIG_SYNC_RECEIVED -> {
                    val jsonStr = intent.getStringExtra(AudioStreamService.EXTRA_CONFIG_JSON)
                    if (!jsonStr.isNullOrBlank()) {
                        try {
                            applyIncomingConfigSync(JSONObject(jsonStr))
                        } catch (e: Exception) {
                            Log.e("AirSpeaker", "Error parsing incoming CONFIG_SYNC", e)
                        }
                    }
                }
            }
        }
    }

    private fun quantizeTarget(value: Float): Float {
        val safe = if (value.isFinite()) value else 30f
        val clamped = safe.coerceIn(10f, 200f)
        return (kotlin.math.round(clamped / 2.5f) * 2.5f).coerceIn(10f, 200f)
    }

    private fun quantizeGain(value: Float): Float {
        val safe = if (value.isFinite()) value else 1.0f
        return safe.coerceIn(0f, 2.0f)
    }

    companion object {
        val EQ_BAND_FREQS = intArrayOf(60, 230, 910, 3600, 14000)
        val EQ_BAND_LABELS = arrayOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")
        val BUILTIN_EQ_PRESETS: Map<String, FloatArray> = mapOf(
            "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f),
            "Bass Boost" to floatArrayOf(6.5f, 4.0f, 0.0f, 1.0f, 1.5f),
            "Vocal Crisp" to floatArrayOf(-2.0f, -1.0f, 3.5f, 5.0f, 2.0f),
            "Gaming Footsteps" to floatArrayOf(-3.0f, 1.5f, 4.5f, 6.0f, 3.5f),
            "Warm Studio" to floatArrayOf(3.5f, 2.5f, -0.5f, 1.5f, 2.5f),
            "Treble Air" to floatArrayOf(0.0f, 0.0f, 1.0f, 4.0f, 7.0f)
        )
    }

    private fun syncAllTargetBufferSliders(qVal: Float, fromSlider: com.google.android.material.slider.Slider? = null) {
        val clamped = quantizeTarget(qVal)
        val textStr = if (clamped % 1f == 0f) "${clamped.toInt()} ms" else String.format(java.util.Locale.US, "%.1f ms", clamped)

        val sliders = listOf(
            binding.sliderTargetBuffer,
            binding.sliderServeTargetBuffer,
            binding.sliderPartyHostTargetBuffer
        )
        for (s in sliders) {
            if (s !== fromSlider && kotlin.math.abs(s.value - clamped) > 0.1f) {
                s.value = clamped
            }
        }
        binding.tvTargetBufferValue.text = textStr
        binding.tvServeTargetBufferValue.text = textStr
        binding.tvPartyHostTargetBufferValue.text = textStr
    }

    private fun syncAllBufferModeRadioGroups(mode: String) {
        val lowBtns = listOf(binding.rbBufLow, binding.rbServeBufLow, binding.rbPartyHostBufLow)
        val medBtns = listOf(binding.rbBufMed, binding.rbServeBufMed, binding.rbPartyHostBufMed)
        val highBtns = listOf(binding.rbBufHigh, binding.rbServeBufHigh, binding.rbPartyHostBufHigh)
        val autoBtns = listOf(binding.rbBufAuto, binding.rbServeBufAuto, binding.rbPartyHostBufAuto)
        val customBtns = listOf(binding.rbBufCustom, binding.rbServeBufCustom, binding.rbPartyHostBufCustom)

        when (mode) {
            "LOW" -> lowBtns.forEach { if (!it.isChecked) it.isChecked = true }
            "MEDIUM" -> medBtns.forEach { if (!it.isChecked) it.isChecked = true }
            "HIGH" -> highBtns.forEach { if (!it.isChecked) it.isChecked = true }
            "AUTO" -> autoBtns.forEach { if (!it.isChecked) it.isChecked = true }
            "CUSTOM" -> customBtns.forEach { if (!it.isChecked) it.isChecked = true }
        }
    }

    private fun applyEffectiveEqualizerState() {
        val prefs = getSharedPreferences("AirSpeakerEQ", MODE_PRIVATE)
        val univEnabled = prefs.getBoolean("universal_enabled", false)
        val univApplyTx = prefs.getBoolean("universal_apply_tx", false)
        val univApplyRx = prefs.getBoolean("universal_apply_rx", false)
        val univBands = FloatArray(5) { i -> prefs.getFloat("universal_band_$i", 0f) }

        // RX: Active between play & party_join
        val isPartyJoin = (partyModeScreen == "JOIN") || binding.rbPartyJoin.isChecked
        val rxKey = if (isPartyJoin) "party_join" else "play"
        val rxModeEnabled = prefs.getBoolean("${rxKey}_enabled", false)
        val rxModeBands = FloatArray(5) { i -> prefs.getFloat("${rxKey}_band_$i", 0f) }

        val rxEffectiveEnabled = rxModeEnabled || (univEnabled && univApplyRx)
        val rxEffectiveBands = FloatArray(5) { i ->
            var sum = 0f
            if (rxModeEnabled) sum += rxModeBands[i]
            if (univEnabled && univApplyRx) sum += univBands[i]
            sum.coerceIn(-12.0f, 12.0f)
        }

        // TX: Active between serve & party_host
        val isPartyHost = (partyModeScreen == "HOST") || binding.rbPartyHost.isChecked
        val txKey = if (isPartyHost) "party_host" else "serve"
        val txModeEnabled = prefs.getBoolean("${txKey}_enabled", false)
        val txModeBands = FloatArray(5) { i -> prefs.getFloat("${txKey}_band_$i", 0f) }

        val txEffectiveEnabled = txModeEnabled || (univEnabled && univApplyTx)
        val txEffectiveBands = FloatArray(5) { i ->
            var sum = 0f
            if (txModeEnabled) sum += txModeBands[i]
            if (univEnabled && univApplyTx) sum += univBands[i]
            sum.coerceIn(-12.0f, 12.0f)
        }

        AudioNative.nativeSetEqBands(rxEffectiveEnabled, rxEffectiveBands)
        InternalAudioStreamer.setOutboundEq(txEffectiveEnabled, txEffectiveBands)
        updateEqButtonLabels()
    }

    private fun updateEqButtonLabels() {
        val prefs = getSharedPreferences("AirSpeakerEQ", MODE_PRIVATE)
        fun formatLabel(ctx: String, prefix: String): String {
            val en = prefs.getBoolean("${ctx}_enabled", false)
            val pr = prefs.getString("${ctx}_preset", "Flat") ?: "Flat"
            return if (en) "[ 🎛️ $prefix: ON ($pr) ]" else "[ 🎛️ $prefix: OFF ]"
        }
        binding.btnEqualizerPlay.text = formatLabel("play", "PLAY EQUALIZER")
        binding.btnEqualizerServe.text = formatLabel("serve", "SERVE EQUALIZER & PRESETS")
        binding.btnEqualizerPartyHost.text = formatLabel("party_host", "PARTY HOST EQUALIZER & PRESETS")
        val uEn = prefs.getBoolean("universal_enabled", false)
        val uPr = prefs.getString("universal_preset", "Flat") ?: "Flat"
        binding.btnEqualizerUniversal.text = if (uEn) "[ 🎛️ UNIVERSAL 5-BAND EQUALIZER: ON ($uPr) ]" else "[ 🎛️ OPEN UNIVERSAL 5-BAND EQUALIZER & PRESETS ]"
    }

    private fun showEqualizerDialog(contextKey: String, title: String) {
        val prefs = getSharedPreferences("AirSpeakerEQ", MODE_PRIVATE)
        val currentEnabled = prefs.getBoolean("${contextKey}_enabled", false)
        var currentPreset = prefs.getString("${contextKey}_preset", "Flat") ?: "Flat"
        val currentBands = FloatArray(5) { i -> prefs.getFloat("${contextKey}_band_$i", 0f) }

        val sv = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#080808"))
            setPadding(32, 24, 32, 24)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        sv.addView(layout)

        // Title
        val tvTitle = TextView(this).apply {
            text = "🎛️ ${title.uppercase()}"
            setTextColor(Color.parseColor("#00FF88"))
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvTitle)

        // Switch
        val swEnable = SwitchMaterial(this).apply {
            text = "Enable Equalizer"
            setTextColor(Color.WHITE)
            isChecked = currentEnabled
            typeface = android.graphics.Typeface.MONOSPACE
        }
        layout.addView(swEnable)

        // Preset selector
        val customPresets = prefs.getStringSet("custom_presets", emptySet()) ?: emptySet()
        val allPresetNames = ArrayList<String>()
        allPresetNames.addAll(BUILTIN_EQ_PRESETS.keys)
        allPresetNames.addAll(customPresets.sorted())
        if (!allPresetNames.contains("Custom")) allPresetNames.add("Custom")

        val spinnerPresets = Spinner(this).apply {
            setBackgroundColor(Color.parseColor("#18222C"))
            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, allPresetNames)
            setAdapter(adapter)
            val selIdx = allPresetNames.indexOf(currentPreset).coerceAtLeast(0)
            setSelection(selIdx)
        }
        layout.addView(spinnerPresets)

        // Buttons row: Save Preset, Flat
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 16)
        }
        val btnSavePreset = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "💾 Save Preset"
            setTextColor(Color.parseColor("#00EEEE"))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val btnDeletePreset = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "🗑 Delete"
            setTextColor(Color.parseColor("#FF4444"))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val btnFlat = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "↺ Flat"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        btnRow.addView(btnSavePreset)
        btnRow.addView(btnDeletePreset)
        btnRow.addView(btnFlat)
        layout.addView(btnRow)

        // 5 Sliders
        val sliders = ArrayList<com.google.android.material.slider.Slider>()
        val valLabels = ArrayList<TextView>()

        for (i in 0 until 5) {
            val bandRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }
            val lblName = TextView(this).apply {
                text = EQ_BAND_LABELS[i]
                setTextColor(Color.parseColor("#38B6FF"))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            }
            val slider = com.google.android.material.slider.Slider(this).apply {
                valueFrom = -12f
                valueTo = 12f
                stepSize = 0.5f
                value = currentBands[i].coerceIn(-12f, 12f)
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                trackActiveTintList = ColorStateList.valueOf(Color.parseColor("#00FF88"))
                trackInactiveTintList = ColorStateList.valueOf(Color.parseColor("#444444"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
            }
            val lblVal = TextView(this).apply {
                text = String.format(java.util.Locale.US, "%+.1f dB", slider.value)
                setTextColor(Color.parseColor("#00FF88"))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            }
            sliders.add(slider)
            valLabels.add(lblVal)
            bandRow.addView(lblName)
            bandRow.addView(slider)
            bandRow.addView(lblVal)
            layout.addView(bandRow)
        }

        var isUpdatingUi = false

        fun saveDialogState() {
            val editor = prefs.edit()
            editor.putBoolean("${contextKey}_enabled", swEnable.isChecked)
            editor.putString("${contextKey}_preset", currentPreset)
            for (idx in 0 until 5) {
                editor.putFloat("${contextKey}_band_$idx", sliders[idx].value)
            }
            editor.apply()
            applyEffectiveEqualizerState()
        }

        swEnable.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                saveDialogState()
            }
        }

        for (i in 0 until 5) {
            sliders[i].addOnChangeListener { _, value, fromUser ->
                valLabels[i].text = String.format(java.util.Locale.US, "%+.1f dB", value)
                if (fromUser && !isUpdatingUi) {
                    currentPreset = "Custom"
                    val customIdx = allPresetNames.indexOf("Custom")
                    if (customIdx >= 0 && spinnerPresets.selectedItemPosition != customIdx) {
                        isUpdatingUi = true
                        spinnerPresets.setSelection(customIdx)
                        isUpdatingUi = false
                    }
                    if (!swEnable.isChecked) {
                        swEnable.isChecked = true
                    }
                    saveDialogState()
                }
            }
        }

        spinnerPresets.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingUi) return
                val selectedName = allPresetNames[position]
                if (selectedName == "Custom") {
                    currentPreset = "Custom"
                    return
                }
                var targetBands: FloatArray? = BUILTIN_EQ_PRESETS[selectedName]
                if (targetBands == null) {
                    targetBands = FloatArray(5) { b -> prefs.getFloat("preset_${selectedName}_band_$b", 0f) }
                }
                isUpdatingUi = true
                currentPreset = selectedName
                for (b in 0 until 5) {
                    val bandVal = targetBands[b].coerceIn(-12f, 12f)
                    sliders[b].value = bandVal
                    valLabels[b].text = String.format(java.util.Locale.US, "%+.1f dB", bandVal)
                }
                swEnable.isChecked = true
                isUpdatingUi = false
                saveDialogState()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        btnFlat.setOnClickListener {
            isUpdatingUi = true
            currentPreset = "Flat"
            val flatIdx = allPresetNames.indexOf("Flat")
            if (flatIdx >= 0) spinnerPresets.setSelection(flatIdx)
            for (b in 0 until 5) {
                sliders[b].value = 0f
                valLabels[b].text = "+0.0 dB"
            }
            isUpdatingUi = false
            saveDialogState()
        }

        btnSavePreset.setOnClickListener {
            val input = EditText(this).apply {
                hint = "Enter preset name (e.g. Bass Ultra)"
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
            }
            AlertDialog.Builder(this)
                .setTitle("Save Custom Preset")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty() && !BUILTIN_EQ_PRESETS.containsKey(name)) {
                        val set = prefs.getStringSet("custom_presets", emptySet())?.toMutableSet() ?: mutableSetOf()
                        set.add(name)
                        val ed = prefs.edit()
                        ed.putStringSet("custom_presets", set)
                        for (b in 0 until 5) {
                            ed.putFloat("preset_${name}_band_$b", sliders[b].value)
                        }
                        ed.apply()
                        if (!allPresetNames.contains(name)) {
                            allPresetNames.add(allPresetNames.size - 1, name)
                            (spinnerPresets.adapter as ArrayAdapter<String>).notifyDataSetChanged()
                        }
                        spinnerPresets.setSelection(allPresetNames.indexOf(name))
                        Toast.makeText(this, "Saved preset '$name'", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnDeletePreset.setOnClickListener {
            if (BUILTIN_EQ_PRESETS.containsKey(currentPreset) || currentPreset == "Custom") {
                Toast.makeText(this, "Built-in presets cannot be deleted", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val toDelete = currentPreset
            val set = prefs.getStringSet("custom_presets", emptySet())?.toMutableSet() ?: mutableSetOf()
            if (set.remove(toDelete)) {
                val ed = prefs.edit().putStringSet("custom_presets", set)
                for (b in 0 until 5) ed.remove("preset_${toDelete}_band_$b")
                ed.apply()
                allPresetNames.remove(toDelete)
                (spinnerPresets.adapter as ArrayAdapter<String>).notifyDataSetChanged()
                btnFlat.performClick()
                Toast.makeText(this, "Deleted preset '$toDelete'", Toast.LENGTH_SHORT).show()
            }
        }

        AlertDialog.Builder(this)
            .setView(sv)
            .setPositiveButton("✔ Done") { dialog, _ ->
                saveDialogState()
                dialog.dismiss()
            }
            .show()
    }

    @Synchronized
    private fun startPreStreamDiscoveryIfNeeded() {
        if (SystemClock.elapsedRealtime() - lastServiceStartTimeMs < 2000L || AudioStreamService.activeControlChannel != null) {
            return
        }
        if (!AudioStreamService.isStreaming && !AudioStreamService.isServing && preStreamDiscoveryChannel == null) {
            preStreamDiscoveryChannel = ControlChannel(
                audioPort = 5004,
                controlPort = 5005,
                onConfigSyncReceived = { configJson ->
                    runOnUiThread { applyIncomingConfigSync(configJson) }
                }
            ).also {
                it.start()
            }
        }
    }

    @Synchronized
    private fun stopPreStreamDiscovery() {
        preStreamDiscoveryChannel?.stop()
        preStreamDiscoveryChannel = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ControlChannel.onPartyHostDiscovered = {
            runOnUiThread {
                if (partyModeScreen == "JOIN") {
                    renderDiscoveredHostButtons()
                }
            }
        }

        setupTabs()
        setupUI()
        checkPermissions()
        refreshLocalIps()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            // Adjust ONLY the phone's hardware media volume (sets the 100% baseline for PC relative volume)
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
            am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(AudioStreamService.ACTION_STATE_CHANGED)
            addAction(AudioStreamService.ACTION_CONFIG_SYNC_RECEIVED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }
        refreshServiceButtonState()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(statsRunnable)
        handler.post(statsRunnable)
        startPreStreamDiscoveryIfNeeded()
        refreshBatteryOptimizationStatus()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statsRunnable)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(serviceReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        ControlChannel.onPartyHostDiscovered = null
        stopPreStreamDiscovery()
    }

    // ------------------------------------------------------------------
    // Tab Navigation & Party QR Helpers
    // ------------------------------------------------------------------

    private fun copyToClipboard(label: String, text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard?.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy to clipboard: ${e.message}")
        }
    }

    private fun parseHostIpFromUri(raw: String): Pair<String, Int> {
        var s = raw.trim()
        var port = 5004
        if (s.startsWith("airspeaker://join", ignoreCase = true) || s.startsWith("phonespeaker://join", ignoreCase = true)) {
            try {
                val uri = Uri.parse(s)
                val h = uri.getQueryParameter("host")
                val p = uri.getQueryParameter("port")?.toIntOrNull()
                if (!h.isNullOrBlank()) {
                    return Pair(h.trim(), p ?: 5004)
                }
            } catch (_: Exception) {}
        }
        for (prefix in listOf("http://", "https://", "udp://", "airspeaker://", "phonespeaker://")) {
            if (s.startsWith(prefix, ignoreCase = true)) {
                s = s.substring(prefix.length).trim()
            }
        }
        s = s.split("/")[0].trim()
        if (s.contains(":")) {
            val parts = s.split(":")
            s = parts[0].trim()
            parts.getOrNull(1)?.toIntOrNull()?.let { port = it }
        }
        return Pair(s, port)
    }

    private fun updateHostQrCode() {
        val hostIp = ControlChannel.getPreferredHostIp()
        binding.tvPartyHostIpBanner.text = "👑 HOST IP: $hostIp : 5004"
        val model = ControlChannel.getPhoneModelName()
        val uri = "airspeaker://join?host=$hostIp&port=5004&name=${Uri.encode(model)}"
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(uri, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            binding.ivPartyHostQr.setImageBitmap(bmp)
            binding.ivPartyHostQr.setOnClickListener {
                copyToClipboard("Air Speaker Party Join Link", uri)
                Toast.makeText(this, "Copied Party Join link to clipboard!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate host QR code: ${e.message}")
        }
    }

    private fun updateExtendedQrCode() {
        val extIp = ControlChannel.getPreferredHostIp()
        binding.tvExtendedHostIpBanner.text = "📡 EXTENDED HOST IP: $extIp : 5004"
        val model = ControlChannel.getPhoneModelName()
        val uri = "airspeaker://join?host=$extIp&port=5004&name=${Uri.encode("$model (Extended)")}"
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(uri, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            binding.ivExtendedHostQr.setImageBitmap(bmp)
            binding.ivExtendedHostQr.setOnClickListener {
                copyToClipboard("Extended Party Join Link", uri)
                Toast.makeText(this, "Copied Extended Party Join link to clipboard!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate extended QR code: ${e.message}")
        }
    }

    private fun showQrPasteDialog() {
        var clipboardText = ""
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard?.hasPrimaryClip() == true) {
                val item = clipboard.primaryClip?.getItemAt(0)
                clipboardText = item?.text?.toString()?.trim().orEmpty()
            }
        } catch (_: Exception) {}

        val input = android.widget.EditText(this).apply {
            hint = "Paste airspeaker://join link or Host IP"
            setText(clipboardText)
            setSelectAllOnFocus(true)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666666.toInt())
            background = null
            setPadding(32, 24, 32, 24)
            textSize = 13f
        }

        val container = android.widget.FrameLayout(this).apply {
            setBackgroundColor(0xFF141414.toInt())
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Party Host Address / QR Link")
            .setMessage("Paste a Party QR link (airspeaker://join?host=...) or Host IP address:")
            .setView(container)
            .setPositiveButton("Use Address") { _, _ ->
                val entered = input.text.toString().trim()
                if (entered.isNotEmpty()) {
                    val (ip, port) = parseHostIpFromUri(entered)
                    if (ip.isNotEmpty()) {
                        showPartyScreen("JOIN")
                        joinedHostIp = ip
                        joinedHostPort = port
                        binding.tvPartyJoinStatus.text = "PARTY JOINER: Selected host $ip:$port"
                        Toast.makeText(this, "Set Party Host: $ip:$port", Toast.LENGTH_SHORT).show()
                        if (!AudioStreamService.isStreaming) {
                            binding.btnTogglePartyJoin.performClick()
                        } else {
                            ControlChannel.sendPartyJoinAnnouncement(ip)
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupTabs() {
        binding.tabBtnPlayer.setOnClickListener { selectTab(0) }
        binding.tabBtnServer.setOnClickListener { selectTab(1) }
        binding.tabBtnParty.setOnClickListener { selectTab(2) }
        binding.tabBtnSettings.setOnClickListener { selectTab(3) }
        selectTab(0)
    }

    private fun selectTab(index: Int) {
        binding.layoutPlayerTab.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.layoutServerTab.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.layoutPartyTab.visibility = if (index == 2) View.VISIBLE else View.GONE
        binding.layoutSettingsTab.visibility = if (index == 3) View.VISIBLE else View.GONE
        if (index == 2) {
            showPartyScreen(partyModeScreen)
            if (partyModeScreen == "HOST") {
                updateHostQrCode()
            } else if (partyModeScreen == "JOIN") {
                renderDiscoveredHostButtons()
                if (isPartyExtendedActive) {
                    updateExtendedQrCode()
                }
            }
        }

        val tabs = listOf(binding.tabBtnPlayer, binding.tabBtnServer, binding.tabBtnParty, binding.tabBtnSettings)
        for ((i, btn) in tabs.withIndex()) {
            val active = (i == index)
            btn.setTextColor(if (active) 0xFF00FF00.toInt() else 0xFF888888.toInt())
            btn.strokeColor = android.content.res.ColorStateList.valueOf(
                if (active) 0xFF00FF00.toInt() else 0xFF444444.toInt()
            )
            btn.strokeWidth = if (active) 4 else 2
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (active) 0xFF0F220F.toInt() else 0xFF000000.toInt()
            )
        }
    }

    private fun showPartyScreen(screen: String) {
        partyModeScreen = screen
        binding.layoutPartyChooser.visibility = if (screen == "CHOOSER") View.VISIBLE else View.GONE
        binding.layoutPartyHostSection.visibility = if (screen == "HOST") View.VISIBLE else View.GONE
        binding.layoutPartyJoinSection.visibility = if (screen == "JOIN") View.VISIBLE else View.GONE

        if (screen == "HOST") {
            binding.rbPartyHost.isChecked = true
            ControlChannel.isHostingOrExtendingParty = isPartyHostBroadcastActive
            ControlChannel.isExtendingParty = false
            updateHostQrCode()
        } else if (screen == "JOIN") {
            binding.rbPartyJoin.isChecked = true
            ControlChannel.isHostingOrExtendingParty = isPartyExtendedActive
            ControlChannel.isExtendingParty = isPartyExtendedActive
            startPreStreamDiscoveryIfNeeded()
            renderDiscoveredHostButtons()
        } else {
            if (!AudioStreamService.isStreaming) {
                ControlChannel.isHostingOrExtendingParty = false
                ControlChannel.isExtendingParty = false
            }
        }
        applyEffectiveEqualizerState()
        updateStatsUI()
    }

    private fun renderDiscoveredHostButtons() {
        val now = SystemClock.elapsedRealtime()
        ControlChannel.prunePartyHostsAndPeers()
        val hosts = ControlChannel.discoveredPartyHosts.values.filter { (now - it.lastSeenMs) < 6000L }
        val currentKey = hosts.joinToString(";") { "${it.ip}:${it.port}:${it.name}:${it.isExtended}" }
        if (currentKey == lastDiscoveredHostsKey && binding.layoutDiscoveredHostsList.childCount == hosts.size) {
            return
        }
        lastDiscoveredHostsKey = currentKey

        binding.layoutDiscoveredHostsList.removeAllViews()
        if (hosts.isEmpty()) {
            binding.tvPartyDiscoveredHosts.visibility = View.VISIBLE
            binding.tvPartyDiscoveredHosts.text = "Waiting for party hosts... Tap a host below to connect:"
            binding.tvPartyDiscoveredHosts.setTextColor(0xFF888888.toInt())
            return
        }

        binding.tvPartyDiscoveredHosts.visibility = View.VISIBLE
        binding.tvPartyDiscoveredHosts.text = "Discovered Active Hosts (${hosts.size}) — Tap to Join:"
        binding.tvPartyDiscoveredHosts.setTextColor(0xFF00EEEE.toInt())

        for (h in hosts) {
            val btn = MaterialButton(this).apply {
                val tag = if (h.isExtended) "[EXTENDED] " else "👑 "
                text = "$tag${h.name} • ${h.ip}:${h.port}  [ TAP TO JOIN ]"
                setTextColor(0xFF00EEEE.toInt())
                strokeColor = ColorStateList.valueOf(0xFF00EEEE.toInt())
                strokeWidth = 2
                backgroundTintList = ColorStateList.valueOf(0xFF001822.toInt())
                textSize = 12f
                isAllCaps = false
                setPadding(16, 12, 16, 12)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8
                }
                layoutParams = params
                setOnClickListener {
                    joinedHostIp = h.ip
                    joinedHostPort = h.port
                    Toast.makeText(this@MainActivity, "Joining Host: ${h.name} (${h.ip})", Toast.LENGTH_SHORT).show()
                    binding.tvPartyJoinStatus.text = "PARTY JOINER: Connecting to ${h.name} (${h.ip}:${h.port})..."
                    if (!AudioStreamService.isStreaming) {
                        binding.btnTogglePartyJoin.performClick()
                    } else {
                        ControlChannel.sendPartyJoinAnnouncement(h.ip)
                    }
                }
            }
            binding.layoutDiscoveredHostsList.addView(btn)
        }
    }

    // ------------------------------------------------------------------
    // UI Wiring
    // ------------------------------------------------------------------

    private fun setupUI() {
        suppressSync = true

        // Bitrate spinner with custom dark adapter & selection listener
        val bitrateAdapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            bitrateValues.map { "$it Kbps" }
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                if (v is android.widget.TextView) {
                    v.setTextColor(0xFFFFFFFF.toInt())
                    v.setBackgroundColor(0xFF111111.toInt())
                }
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                if (v is android.widget.TextView) {
                    v.setTextColor(0xFFFFFFFF.toInt())
                    v.setBackgroundColor(0xFF111111.toInt())
                }
                return v
            }
        }
        binding.spinnerBitrate.adapter = bitrateAdapter
        binding.spinnerBitrate.setSelection(7) // 192 Kbps studio default

        val playerBitrateAdapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            bitrateValues.map { "$it Kbps" }
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                if (v is android.widget.TextView) {
                    v.setTextColor(0xFFFFFFFF.toInt())
                    v.setBackgroundColor(0xFF111111.toInt())
                }
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                if (v is android.widget.TextView) {
                    v.setTextColor(0xFFFFFFFF.toInt())
                    v.setBackgroundColor(0xFF111111.toInt())
                }
                return v
            }
        }
        binding.spPlayerBitrate.adapter = playerBitrateAdapter
        binding.spPlayerBitrate.setSelection(7)

        binding.spinnerBitrate.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSync) return
                val selectedKbps = bitrateValues.getOrElse(position) { "128" }
                val expectedKbps = when {
                    binding.rbProfileQuality.isChecked -> "256"
                    binding.rbProfileGaming.isChecked -> "128"
                    binding.rbProfileBalanced.isChecked -> "192"
                    else -> ""
                }
                if (expectedKbps.isNotEmpty() && selectedKbps != expectedKbps) {
                    binding.rbProfileCustom.isChecked = true
                }
                if (binding.spPlayerBitrate.selectedItemPosition != position) {
                    suppressSync = true
                    binding.spPlayerBitrate.setSelection(position)
                    suppressSync = false
                }
                onLocalSettingChanged()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        binding.spPlayerBitrate.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSync) return
                val selectedKbps = bitrateValues.getOrElse(position) { "128" }
                val expectedKbps = when {
                    binding.rbProfileQuality.isChecked -> "256"
                    binding.rbProfileGaming.isChecked -> "128"
                    binding.rbProfileBalanced.isChecked -> "192"
                    else -> ""
                }
                if (expectedKbps.isNotEmpty() && selectedKbps != expectedKbps) {
                    binding.rbProfileCustom.isChecked = true
                }
                if (binding.spinnerBitrate.selectedItemPosition != position) {
                    suppressSync = true
                    binding.spinnerBitrate.setSelection(position)
                    suppressSync = false
                }
                onLocalSettingChanged()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Buffer target sliders (synchronized across PLAY, SERVE, PARTY HOST, and PARTY JOIN)
        val initialTarget = quantizeTarget(AudioNative.nativeGetTargetBufferMs())
        syncAllTargetBufferSliders(initialTarget)

        val onBufferSliderChanged: (com.google.android.material.slider.Slider, Float, Boolean) -> Unit = { s, value, fromUser ->
            if (fromUser) {
                val qVal = quantizeTarget(value)
                AudioNative.nativeSetTargetBufferMs(qVal)
                syncAllTargetBufferSliders(qVal, fromSlider = s)
                syncAllBufferModeRadioGroups("CUSTOM")
                if (!suppressSync) binding.rbProfileCustom.isChecked = true
                onLocalSettingChanged()
            }
        }
        binding.sliderTargetBuffer.addOnChangeListener { slider, value, fromUser -> onBufferSliderChanged(slider, value, fromUser) }
        binding.sliderServeTargetBuffer.addOnChangeListener { slider, value, fromUser -> onBufferSliderChanged(slider, value, fromUser) }
        binding.sliderPartyHostTargetBuffer.addOnChangeListener { slider, value, fromUser -> onBufferSliderChanged(slider, value, fromUser) }

        // Buffer presets radio groups (synchronized across PLAY, SERVE, and PARTY HOST)
        var updatingBufferGroups = false
        val setupBufferGroup: (android.widget.RadioGroup, Int, Int, Int, Int, Int) -> Unit = { group, lowId, medId, highId, autoId, customId ->
            group.setOnCheckedChangeListener { _, checkedId ->
                if (updatingBufferGroups) return@setOnCheckedChangeListener
                updatingBufferGroups = true
                try {
                    when (checkedId) {
                        lowId -> {
                            AudioNative.nativeSetTargetBufferMs(20f)
                            AudioNative.nativeSetAutoMode(false)
                            syncAllTargetBufferSliders(20f)
                            syncAllBufferModeRadioGroups("LOW")
                        }
                        medId -> {
                            AudioNative.nativeSetTargetBufferMs(40f)
                            AudioNative.nativeSetAutoMode(false)
                            syncAllTargetBufferSliders(40f)
                            syncAllBufferModeRadioGroups("MEDIUM")
                        }
                        highId -> {
                            AudioNative.nativeSetTargetBufferMs(80f)
                            AudioNative.nativeSetAutoMode(false)
                            syncAllTargetBufferSliders(80f)
                            syncAllBufferModeRadioGroups("HIGH")
                        }
                        autoId -> {
                            AudioNative.nativeSetAutoMode(true)
                            syncAllBufferModeRadioGroups("AUTO")
                        }
                        customId -> {
                            AudioNative.nativeSetAutoMode(false)
                            syncAllBufferModeRadioGroups("CUSTOM")
                        }
                    }
                    if (!suppressSync) {
                        binding.rbProfileCustom.isChecked = true
                        onLocalSettingChanged()
                    }
                } finally {
                    updatingBufferGroups = false
                }
            }
        }

        setupBufferGroup(binding.rgBufferMode, R.id.rbBufLow, R.id.rbBufMed, R.id.rbBufHigh, R.id.rbBufAuto, R.id.rbBufCustom)
        setupBufferGroup(binding.rgServeBufferMode, R.id.rbServeBufLow, R.id.rbServeBufMed, R.id.rbServeBufHigh, R.id.rbServeBufAuto, R.id.rbServeBufCustom)
        setupBufferGroup(binding.rgPartyHostBufferMode, R.id.rbPartyHostBufLow, R.id.rbPartyHostBufMed, R.id.rbPartyHostBufHigh, R.id.rbPartyHostBufAuto, R.id.rbPartyHostBufCustom)

        // Audio Profile / Focus radio group
        binding.rgAudioProfile.setOnCheckedChangeListener { _, checkedId ->
            if (suppressSync) return@setOnCheckedChangeListener
            when (checkedId) {
                R.id.rbProfileQuality -> {
                    suppressSync = true
                    try {
                        currentFrameMs = 10
                        binding.rbCodecOpus.isChecked = true
                        binding.rbPlayerCodecOpus.isChecked = true
                        val idx = bitrateValues.indexOf("256")
                        if (idx >= 0) {
                            binding.spinnerBitrate.setSelection(idx)
                            binding.spPlayerBitrate.setSelection(idx)
                        }
                        binding.rbBufHigh.isChecked = true
                        syncAllTargetBufferSliders(80f)
                        AudioNative.nativeSetTargetBufferMs(80f)
                        AudioNative.nativeSetAutoMode(false)
                    } finally {
                        suppressSync = false
                    }
                }
                R.id.rbProfileGaming -> {
                    suppressSync = true
                    try {
                        currentFrameMs = 5
                        binding.rbCodecOpus.isChecked = true
                        binding.rbPlayerCodecOpus.isChecked = true
                        val idx = bitrateValues.indexOf("128")
                        if (idx >= 0) {
                            binding.spinnerBitrate.setSelection(idx)
                            binding.spPlayerBitrate.setSelection(idx)
                        }
                        binding.rbBufLow.isChecked = true
                        syncAllTargetBufferSliders(20f)
                        AudioNative.nativeSetTargetBufferMs(20f)
                        AudioNative.nativeSetAutoMode(false)
                    } finally {
                        suppressSync = false
                    }
                }
                R.id.rbProfileBalanced -> {
                    suppressSync = true
                    try {
                        currentFrameMs = 5
                        binding.rbCodecOpus.isChecked = true
                        binding.rbPlayerCodecOpus.isChecked = true
                        val idx = bitrateValues.indexOf("192")
                        if (idx >= 0) {
                            binding.spinnerBitrate.setSelection(idx)
                            binding.spPlayerBitrate.setSelection(idx)
                        }
                        binding.rbBufAuto.isChecked = true
                        syncAllTargetBufferSliders(30f)
                        AudioNative.nativeSetTargetBufferMs(30f)
                        AudioNative.nativeSetAutoMode(true)
                    } finally {
                        suppressSync = false
                    }
                }
            }
            updateCodecBitrateUiState(binding.rbCodecOpus.isChecked)
            onLocalSettingChanged()
        }

        // Equalizer dialog triggers
        binding.btnEqualizerPlay.setOnClickListener { showEqualizerDialog("play", "Play Equalizer") }
        binding.btnEqualizerServe.setOnClickListener { showEqualizerDialog("serve", "Serve Outbound Equalizer") }
        binding.btnEqualizerPartyHost.setOnClickListener { showEqualizerDialog("party_host", "Party Host Broadcast Equalizer") }
        binding.btnEqualizerUniversal.setOnClickListener { showEqualizerDialog("universal", "Universal Device Equalizer") }

        val eqPrefs = getSharedPreferences("AirSpeakerEQ", MODE_PRIVATE)
        binding.cbUniversalEqServeHost.isChecked = eqPrefs.getBoolean("universal_apply_tx", false)
        binding.cbUniversalEqPlayJoin.isChecked = eqPrefs.getBoolean("universal_apply_rx", false)

        binding.cbUniversalEqServeHost.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.edit().putBoolean("universal_apply_tx", isChecked).apply()
            applyEffectiveEqualizerState()
        }
        binding.cbUniversalEqPlayJoin.setOnCheckedChangeListener { _, isChecked ->
            eqPrefs.edit().putBoolean("universal_apply_rx", isChecked).apply()
            applyEffectiveEqualizerState()
        }

        applyEffectiveEqualizerState()
        updateEqButtonLabels()

        // Gain slider & Mute
        try {
            binding.sliderGain.value = quantizeGain(AudioNative.nativeGetVolumeGain())
            binding.tvGainValue.text = "${(binding.sliderGain.value * 100).toInt()}%"
        } catch (_: Exception) {}
        binding.sliderGain.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                // PC UDPSender applies the stream gain relative to phone's hardware volume
                AudioNative.nativeSetVolumeGain(1.0f)
                binding.tvGainValue.text = "${(value * 100).toInt()}%"
                onLocalSettingChanged()
            }
        }

        binding.btnMuteToggle.setOnClickListener {
            val muted = !AudioNative.nativeIsMuted()
            AudioNative.nativeSetMuted(muted)
            binding.btnMuteToggle.text = if (muted) "MUTED" else "MUTE"
            onLocalSettingChanged()
        }

        // Codec selection synced across Player tab and Settings tab
        binding.rgPlayerCodec.setOnCheckedChangeListener { _, checkedId ->
            if (suppressSync) return@setOnCheckedChangeListener
            binding.rbProfileCustom.isChecked = true
            suppressSync = true
            val isOpus = (checkedId == R.id.rbPlayerCodecOpus)
            if (isOpus) {
                binding.rbCodecOpus.isChecked = true
            } else {
                binding.rbCodecPcm.isChecked = true
            }
            updateCodecBitrateUiState(isOpus)
            suppressSync = false
            onLocalSettingChanged()
        }

        binding.rgCodec.setOnCheckedChangeListener { _, checkedId ->
            if (suppressSync) return@setOnCheckedChangeListener
            binding.rbProfileCustom.isChecked = true
            suppressSync = true
            val isOpus = (checkedId == R.id.rbCodecOpus)
            if (isOpus) {
                binding.rbPlayerCodecOpus.isChecked = true
            } else {
                binding.rbPlayerCodecPcm.isChecked = true
            }
            updateCodecBitrateUiState(isOpus)
            suppressSync = false
            onLocalSettingChanged()
        }

        // Serve tab settings & listeners
        binding.rgServeSoundMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbServeGaming) {
                AudioNative.nativeSetTargetBufferMs(20.0f)
            } else if (checkedId == R.id.rbServeQuality) {
                AudioNative.nativeSetTargetBufferMs(80.0f)
            } else if (checkedId == R.id.rbServeBalanced) {
                AudioNative.nativeSetTargetBufferMs(40.0f)
            }
        }
        binding.rgServeCodec.setOnCheckedChangeListener { _, checkedId ->
            val isOpus = (checkedId == R.id.rbServeCodecOpus)
            binding.spinnerServeBitrate.isEnabled = isOpus
            binding.spinnerServeBitrate.alpha = if (isOpus) 1.0f else 0.45f
        }
        val serveBitrates = listOf("64", "96", "128", "192", "256", "320")
        binding.spinnerServeBitrate.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, serveBitrates)
        binding.spinnerServeBitrate.setSelection(3) // 192 kbps default
        binding.spinnerServeBitrate.isEnabled = false
        binding.spinnerServeBitrate.alpha = 0.45f

        binding.btnToggleServe.setOnClickListener { toggleServing() }
        binding.btnSetServeTargetIp.setOnClickListener {
            val ip = binding.etServeTargetIp.text.toString().trim()
            if (ip.isNotBlank()) {
                AudioStreamService.internalAudioStreamerInstance?.updateTargetIp(ip)
                Toast.makeText(this, "Target IP set to $ip", Toast.LENGTH_SHORT).show()
            }
        }

        binding.rgOutputEngine.setOnCheckedChangeListener { _, _ -> if (!suppressSync) onLocalSettingChanged() }
        binding.switchAudioFx.setOnCheckedChangeListener { _, _ -> if (!suppressSync) onLocalSettingChanged() }
        binding.rgExclusiveMode.setOnCheckedChangeListener { _, _ -> if (!suppressSync) onLocalSettingChanged() }
        binding.switchHeadsetDisconnect.setOnCheckedChangeListener { _, _ -> if (!suppressSync) onLocalSettingChanged() }

        // Party Mode Tab Controls (3-State Chooser -> Host or Join -> Extend)
        binding.btnPartyChooseHost.setOnClickListener {
            showPartyScreen("HOST")
        }

        binding.btnPartyChooseJoin.setOnClickListener {
            showPartyScreen("JOIN")
        }

        binding.btnPartyHostBack.setOnClickListener {
            if (isPartyHostBroadcastActive) {
                isPartyHostBroadcastActive = false
                ControlChannel.isHostingOrExtendingParty = false
                ControlChannel.isExtendingParty = false
                AudioNative.nativeSetPartyRelay(false)
                ControlChannel.sendPartyHostStopAnnouncement()
                binding.btnTogglePartyHost.text = "[ 👑 START PARTY BROADCAST (HOST) ]"
                binding.btnTogglePartyHost.setBackgroundColor(Color.parseColor("#002B00"))
                binding.btnTogglePartyHost.setTextColor(Color.parseColor("#00FF00"))
                binding.btnTogglePartyHost.strokeColor = ColorStateList.valueOf(Color.parseColor("#00FF00"))
            }
            if (AudioStreamService.isStreaming && (partyModeScreen == "HOST" || binding.rbPartyHost.isChecked)) {
                val intent = Intent(this, AudioStreamService::class.java).apply {
                    action = AudioStreamService.ACTION_STOP
                }
                startService(intent)
            }
            showPartyScreen("CHOOSER")
        }

        binding.btnPartyJoinBack.setOnClickListener {
            if (isPartyJoinedActive) {
                ControlChannel.sendPartyLeaveAnnouncement(joinedHostIp)
            }
            if (AudioStreamService.isStreaming && partyModeScreen == "JOIN") {
                val intent = Intent(this, AudioStreamService::class.java).apply {
                    action = AudioStreamService.ACTION_STOP
                }
                startService(intent)
            }
            joinedHostIp = ""
            isPartyJoinedActive = false
            isPartyExtendedActive = false
            binding.switchExtendParty.isChecked = false
            AudioNative.nativeSetPartyRelay(false)
            ControlChannel.isHostingOrExtendingParty = false
            ControlChannel.isExtendingParty = false
            binding.layoutPartyExtendCard.visibility = View.GONE
            binding.layoutExtendDetails.visibility = View.GONE
            binding.btnTogglePartyJoin.visibility = View.GONE
            showPartyScreen("CHOOSER")
        }

        binding.btnRescanPartyHosts.setOnClickListener {
            startPreStreamDiscoveryIfNeeded()
            ControlChannel.sendPreStreamBeacon()
            lastDiscoveredHostsKey = ""
            renderDiscoveredHostButtons()
            Toast.makeText(this, "Scanning for party hosts...", Toast.LENGTH_SHORT).show()
        }

        binding.btnToggleExtendParty.setOnClickListener {
            isPartyExtendedActive = !isPartyExtendedActive
            binding.switchExtendParty.isChecked = isPartyExtendedActive
            ControlChannel.isExtendingParty = isPartyExtendedActive
            ControlChannel.isHostingOrExtendingParty = isPartyExtendedActive
            AudioNative.nativeSetPartyRelay(isPartyExtendedActive)
            if (isPartyExtendedActive) {
                AudioNative.nativeSetPartyPeers(ControlChannel.partyPeerIps.toTypedArray())
                updateExtendedQrCode()
                binding.layoutExtendDetails.visibility = View.VISIBLE
                binding.btnToggleExtendParty.text = "[ ■ STOP EXTENDING PARTY ]"
                binding.btnToggleExtendParty.setTextColor(0xFFFF4444.toInt())
                binding.btnToggleExtendParty.strokeColor = ColorStateList.valueOf(0xFFFF4444.toInt())
            } else {
                binding.layoutExtendDetails.visibility = View.GONE
                binding.btnToggleExtendParty.text = "[ 📡 EXTEND PARTY (GENERATE HOTSPOT QR & IP) ]"
                binding.btnToggleExtendParty.setTextColor(0xFF00EEEE.toInt())
                binding.btnToggleExtendParty.strokeColor = ColorStateList.valueOf(0xFF00EEEE.toInt())
            }
            updateStatsUI()
        }

        binding.rgPartyRole.setOnCheckedChangeListener { _, checkedId ->
            val isHost = (checkedId == binding.rbPartyHost.id)
            if (isHost) {
                showPartyScreen("HOST")
            } else {
                showPartyScreen("JOIN")
            }
        }

        binding.btnRefreshHostIpQr.setOnClickListener {
            updateHostQrCode()
            Toast.makeText(this, "Refreshed Host IP & QR Code", Toast.LENGTH_SHORT).show()
        }

        binding.btnTogglePartyHost.setOnClickListener {
            if (isPartyHostBroadcastActive) {
                isPartyHostBroadcastActive = false
                ControlChannel.isHostingOrExtendingParty = false
                ControlChannel.isExtendingParty = false
                AudioNative.nativeSetPartyRelay(false)
                ControlChannel.sendPartyHostStopAnnouncement()
                if (AudioStreamService.isStreaming) {
                    val intent = Intent(this, AudioStreamService::class.java).apply {
                        action = AudioStreamService.ACTION_STOP
                    }
                    startService(intent)
                }
                binding.btnTogglePartyHost.text = "[ 👑 START PARTY BROADCAST (HOST) ]"
                binding.btnTogglePartyHost.setBackgroundColor(Color.parseColor("#002B00"))
                binding.btnTogglePartyHost.setTextColor(Color.parseColor("#00FF00"))
                binding.btnTogglePartyHost.strokeColor = ColorStateList.valueOf(Color.parseColor("#00FF00"))
            } else {
                isPartyHostBroadcastActive = true
                ControlChannel.isHostingOrExtendingParty = true
                ControlChannel.isExtendingParty = false
                AudioNative.nativeSetPartyRelay(true)
                AudioNative.nativeSetPartyPeers(ControlChannel.partyPeerIps.toTypedArray())
                ControlChannel.sendPreStreamBeacon()
                if (!AudioStreamService.isStreaming) {
                    val intent = Intent(this, AudioStreamService::class.java).apply {
                        action = AudioStreamService.ACTION_START
                        putExtra(AudioStreamService.EXTRA_AUDIO_ENGINE, if (binding.rbEngineAudioTrack.isChecked) "AUDIOTRACK_STANDARD" else "OBOE_LOW_LATENCY")
                        putExtra(AudioStreamService.EXTRA_AUDIO_FX, binding.switchAudioFx.isChecked)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
                binding.btnTogglePartyHost.text = "[ ■ STOP PARTY BROADCAST ]"
                binding.btnTogglePartyHost.setBackgroundColor(Color.parseColor("#330000"))
                binding.btnTogglePartyHost.setTextColor(Color.parseColor("#FF4444"))
                binding.btnTogglePartyHost.strokeColor = ColorStateList.valueOf(Color.parseColor("#FF4444"))
            }
            refreshServiceButtonState()
        }

        binding.btnTogglePartyJoin.setOnClickListener {
            if (isPartyJoinedActive || AudioStreamService.isStreaming) {
                ControlChannel.sendPartyLeaveAnnouncement(joinedHostIp)
                joinedHostIp = ""
                isPartyJoinedActive = false
                isPartyExtendedActive = false
                ControlChannel.isHostingOrExtendingParty = false
                ControlChannel.isExtendingParty = false
                AudioNative.nativeSetPartyRelay(false)
                binding.switchExtendParty.isChecked = false
                binding.layoutPartyExtendCard.visibility = View.GONE
                binding.layoutExtendDetails.visibility = View.GONE
                binding.btnTogglePartyJoin.visibility = View.GONE
                binding.tvPartyJoinStatus.text = "PARTY JOINER: Standby (Scan QR or tap an active host above)"
                if (AudioStreamService.isStreaming) {
                    val intent = Intent(this, AudioStreamService::class.java).apply {
                        action = AudioStreamService.ACTION_STOP
                    }
                    startService(intent)
                }
            } else {
                if (joinedHostIp.isEmpty()) {
                    val firstHost = ControlChannel.discoveredPartyHosts.values.firstOrNull()
                    if (firstHost != null) {
                        joinedHostIp = firstHost.ip
                        joinedHostPort = firstHost.port
                    }
                }
                if (joinedHostIp.isEmpty()) {
                    launchQrScanner()
                    return@setOnClickListener
                }
                binding.etPcIp.setText(joinedHostIp)
                ControlChannel.lastKnownPcIp = joinedHostIp
                ControlChannel.partyPeerIps.clear()
                AudioNative.nativeSetPartyPeers(emptyArray())
                ControlChannel.sendPartyJoinAnnouncement(joinedHostIp)
                lastJoinKeepAliveMs = SystemClock.elapsedRealtime()

                isPartyJoinedActive = true
                val isExtend = isPartyExtendedActive || binding.switchExtendParty.isChecked
                ControlChannel.isExtendingParty = isExtend
                ControlChannel.isHostingOrExtendingParty = isExtend
                AudioNative.nativeSetPartyRelay(isExtend)
                if (isExtend) {
                    AudioNative.nativeSetPartyPeers(ControlChannel.partyPeerIps.toTypedArray())
                }

                binding.layoutPartyExtendCard.visibility = View.VISIBLE
                binding.btnTogglePartyJoin.visibility = View.VISIBLE
                binding.btnTogglePartyJoin.text = "[ ■ LEAVE PARTY ]"
                binding.tvPartyJoinStatus.text = "PARTY JOINER: Connected to $joinedHostIp:$joinedHostPort • Playing in sync"

                stopPreStreamDiscovery()
                val intent = Intent(this, AudioStreamService::class.java).apply {
                    action = AudioStreamService.ACTION_START
                    putExtra(AudioStreamService.EXTRA_AUDIO_ENGINE, if (binding.rbEngineAudioTrack.isChecked) "AUDIOTRACK_STANDARD" else "OBOE_LOW_LATENCY")
                    putExtra(AudioStreamService.EXTRA_AUDIO_FX, binding.switchAudioFx.isChecked)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
            refreshServiceButtonState()
        }

        binding.switchExtendParty.setOnCheckedChangeListener { _, isChecked ->
            isPartyExtendedActive = isChecked
            ControlChannel.isExtendingParty = isChecked
            ControlChannel.isHostingOrExtendingParty = isChecked || (partyModeScreen == "HOST")
            AudioNative.nativeSetPartyRelay(isChecked)
            binding.layoutExtendDetails.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                updateExtendedQrCode()
                AudioNative.nativeSetPartyPeers(ControlChannel.partyPeerIps.toTypedArray())
            }
            updateStatsUI()
        }

        binding.btnRefreshExtendIp.setOnClickListener {
            updateExtendedQrCode()
            Toast.makeText(this, "Refreshed Extended IP & QR Code", Toast.LENGTH_SHORT).show()
        }

        binding.tvPartyDiscoveredHosts.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            val hosts = ControlChannel.discoveredPartyHosts.values.filter { (now - it.lastSeenMs) < 6000L }
            if (hosts.isEmpty()) {
                Toast.makeText(this, "No active Party Hosts discovered yet.", Toast.LENGTH_SHORT).show()
            } else if (hosts.size == 1) {
                val h = hosts.first()
                joinedHostIp = h.ip
                joinedHostPort = h.port
                Toast.makeText(this, "Selected: ${h.name} (${h.ip})", Toast.LENGTH_SHORT).show()
                binding.tvPartyJoinStatus.text = "PARTY JOINER: Selected host ${h.name} (${h.ip}:${h.port})"
            } else {
                val hostList = hosts.toList()
                val items = hostList.map { "${if (it.isExtended) "[EXTENDED] " else ""}${it.name} — ${it.ip}:${it.port}" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Select Discovered Party Host")
                    .setItems(items) { _, which ->
                        val selected = hostList[which]
                        joinedHostIp = selected.ip
                        joinedHostPort = selected.port
                        Toast.makeText(this, "Selected: ${selected.name} (${selected.ip})", Toast.LENGTH_SHORT).show()
                        binding.tvPartyJoinStatus.text = "PARTY JOINER: Selected host ${selected.name} (${selected.ip}:${selected.port})"
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        binding.btnScanPartyQrCamera.setOnClickListener {
            launchQrScanner()
        }

        binding.btnScanPartyQrCameraLarge.setOnClickListener {
            launchQrScanner()
        }

        // Host Codec & Bitrate Controls
        binding.rgPartyHostCodec.setOnCheckedChangeListener { _, checkedId ->
            val isOpus = (checkedId == binding.rbPartyHostCodecOpus.id)
            binding.layoutPartyHostBitrate.visibility = if (isOpus) View.VISIBLE else View.GONE
            if (!suppressSync) {
                binding.rbCodecOpus.isChecked = isOpus
                binding.rbCodecPcm.isChecked = !isOpus
                binding.rbPlayerCodecOpus.isChecked = isOpus
                binding.rbPlayerCodecPcm.isChecked = !isOpus
                onLocalSettingChanged()
            }
        }

        val hostBitrateAdapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            bitrateValues.map { "$it Kbps" }
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(0xFF00EEEE.toInt())
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                }
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0xFF1A1A1A.toInt())
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                }
            }
        }
        binding.spinnerPartyHostBitrate.adapter = hostBitrateAdapter
        val currentBitratePos = binding.spinnerBitrate.selectedItemPosition.coerceAtLeast(0)
        binding.spinnerPartyHostBitrate.setSelection(currentBitratePos)
        binding.spinnerPartyHostBitrate.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                if (!suppressSync) {
                    binding.spinnerBitrate.setSelection(position)
                    binding.spPlayerBitrate.setSelection(position)
                    onLocalSettingChanged()
                }
            }
            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
        }

        binding.btnAddPartyPeer.setOnClickListener {
            val raw = binding.etPartyPeerIp.text?.toString()?.trim().orEmpty()
            val (ip, _) = parseHostIpFromUri(raw)
            if (ip.isNotEmpty() && !ControlChannel.partyPeerIps.contains(ip)) {
                ControlChannel.partyPeerIps.add(ip)
                AudioNative.nativeSetPartyPeers(ControlChannel.partyPeerIps.toTypedArray())
                binding.etPartyPeerIp.text?.clear()
                updateStatsUI()
            }
        }
        binding.btnClearPartyPeers.setOnClickListener {
            ControlChannel.partyPeerIps.clear()
            AudioNative.nativeSetPartyPeers(emptyArray())
            updateStatsUI()
        }

        // Help button
        binding.btnHelp.setOnClickListener { showHelpDialog() }
        updateExpectedDelayUI()

        // Start / Stop Player Button (Strictly Normal 1-to-1 Stream: Relaying is OFF)
        binding.btnToggleStream.setOnClickListener {
            if (AudioStreamService.isStreaming) {
                userManuallyStoppedPlayer = true
                val intent = Intent(this, AudioStreamService::class.java).apply {
                    action = AudioStreamService.ACTION_STOP
                }
                startService(intent)
                handler.postDelayed({
                    if (!AudioStreamService.isStreaming && !AudioStreamService.isServing) {
                        startPreStreamDiscoveryIfNeeded()
                    }
                    refreshServiceButtonState()
                }, 500L)
            } else {
                userManuallyStoppedPlayer = false
                AudioNative.nativeSetPartyRelay(false)
                ControlChannel.isHostingOrExtendingParty = false
                ControlChannel.isExtendingParty = false
                lastServiceStartTimeMs = SystemClock.elapsedRealtime()
                stopPreStreamDiscovery()
                val intent = Intent(this, AudioStreamService::class.java).apply {
                    action = AudioStreamService.ACTION_START
                    putExtra(AudioStreamService.EXTRA_AUDIO_ENGINE, if (binding.rbEngineAudioTrack.isChecked) "AUDIOTRACK_STANDARD" else "OBOE_LOW_LATENCY")
                    putExtra(AudioStreamService.EXTRA_AUDIO_FX, binding.switchAudioFx.isChecked)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
            handler.postDelayed({ refreshServiceButtonState() }, 200L)
        }

        // Connect manual PC IP
        val applyManualPcIp = {
            var manualIp = binding.etPcIp.text.toString().trim()
            for (prefix in listOf("http://", "https://", "udp://")) {
                if (manualIp.startsWith(prefix, ignoreCase = true)) {
                    manualIp = manualIp.substring(prefix.length).trim()
                }
            }
            manualIp = manualIp.substringBefore("/").substringBefore(":").trim()
            val ch = AudioStreamService.activeControlChannel ?: preStreamDiscoveryChannel
            if (manualIp.isNotEmpty() && ch?.isOwnLocalIp(manualIp) != true) {
                binding.etPcIp.setText(manualIp)
                binding.etServeTargetIp.setText(manualIp)
                userEditedPcIp = manualIp
                ch?.setActivePcIp(manualIp)
                AudioStreamService.internalAudioStreamerInstance?.updateTargetIp(manualIp)
                binding.etPcIp.clearFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(binding.etPcIp.windowToken, 0)
                onLocalSettingChanged()
            } else {
                userEditedPcIp = null
                binding.etPcIp.setText("")
            }
        }
        binding.btnConnectPcIp.setOnClickListener { applyManualPcIp() }
        binding.etPcIp.setOnEditorActionListener { _, _, _ ->
            applyManualPcIp()
            true
        }

        binding.btnBatteryOpt.setOnClickListener { requestBatteryOptimizationExemption() }
        binding.btnRefreshIps.setOnClickListener { refreshLocalIps() }

        updateCodecBitrateUiState(binding.rbCodecOpus.isChecked)
        showPartyScreen("CHOOSER")
        binding.root.post { suppressSync = false }
    }

    // ------------------------------------------------------------------
    // Settings Synchronization (Two-Way)
    // ------------------------------------------------------------------

    private fun onLocalSettingChanged() {
        if (suppressSync) return
        localRev++
        val syncId = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
        lastSyncId = syncId
        binding.tvSyncRev.text = "REV: $localRev"
        updateExpectedDelayUI()

        val userMuted = (binding.btnMuteToggle.text == "MUTED")
        AudioNative.nativeSetMuted(userMuted)

        val json = JSONObject().apply {
            put("type", "CONFIG_SYNC")
            put("rev", localRev)
            put("sync_id", syncId)
            put("direction", if (AudioStreamService.isServing) "PHONE_TO_PC" else "PC_TO_PHONE")
            put("pc_source", "SYSTEM_AUDIO")
            put("phone_source", "INTERNAL_AUDIO")
            put("audio_profile", when (binding.rgAudioProfile.checkedRadioButtonId) {
                R.id.rbProfileQuality -> "QUALITY"
                R.id.rbProfileGaming -> "GAMING"
                R.id.rbProfileBalanced -> "BALANCED"
                else -> "CUSTOM"
            })
            put("codec", if (binding.rbCodecOpus.isChecked || binding.rbPlayerCodecOpus.isChecked) 1 else 0)
            put("opus_bitrate_kbps", bitrateValues.getOrElse(binding.spinnerBitrate.selectedItemPosition) { "128" }.toInt())
            put("frame_ms", currentFrameMs)
            put("buffer_mode", when (binding.rgBufferMode.checkedRadioButtonId) {
                R.id.rbBufLow -> "LOW"
                R.id.rbBufMed -> "MEDIUM"
                R.id.rbBufHigh -> "HIGH"
                R.id.rbBufAuto -> "AUTO"
                else -> "CUSTOM"
            })
            put("custom_buffer_ms", binding.sliderTargetBuffer.value.toDouble())
            put("auto_buffer", binding.rbBufAuto.isChecked)
            put("volume_gain", binding.sliderGain.value.toDouble())
            put("muted", userMuted)
            put("device", ControlChannel.getPhoneModelName())
            put("audio_output_engine", if (binding.rbEngineAudioTrack.isChecked) "AUDIOTRACK_STANDARD" else "OBOE_LOW_LATENCY")
            put("enable_audio_effects", binding.switchAudioFx.isChecked)
            put("exclusive_audio_mode", when (binding.rgExclusiveMode.checkedRadioButtonId) {
                R.id.rbFocusExclusive -> "EXCLUSIVE"
                R.id.rbFocusAlways -> "ALWAYS_PLAY"
                else -> "MIX_WITH_OTHERS"
            })
            put("stop_on_headset_disconnect", binding.switchHeadsetDisconnect.isChecked)
            put("party_mode", (partyModeScreen == "HOST") || isPartyExtendedActive)
        }

        val lowLatency = binding.rbEngineOboe.isChecked && !binding.switchAudioFx.isChecked
        AudioNative.nativeSetLowLatencyMode(lowLatency)

        val destIp = userEditedPcIp ?: binding.etPcIp.text.toString().trim().ifEmpty { null }
        val ch = AudioStreamService.activeControlChannel ?: preStreamDiscoveryChannel
        if (ch != null) {
            ch.sendConfigSync(json, destIp)
        } else if (destIp != null) {
            // Fallback: send CONFIG_SYNC directly via background thread so setting change is never dropped
            val payload = json.toString().toByteArray(Charsets.UTF_8)
            Thread({
                try {
                    java.net.DatagramSocket().use { sock ->
                        val pkt = java.net.DatagramPacket(payload, payload.size, java.net.InetAddress.getByName(destIp), 5005)
                        sock.send(pkt)
                    }
                } catch (e: Exception) {
                    Log.w("MainActivity", "Fallback sendConfigSync error: ${e.message}")
                }
            }, "FallbackConfigSyncSender").start()
        }
    }

    private fun applyIncomingConfigSync(json: JSONObject) {
        val incomingRev = json.optInt("rev", 0)
        val incomingSyncId = if (json.has("sync_id")) json.getInt("sync_id") else null

        if (incomingSyncId != null) {
            if (seenSyncIds.contains(incomingSyncId)) {
                return
            }
            if (incomingRev < localRev) {
                return
            }
            if (seenSyncIds.size >= 32) {
                seenSyncIds.removeFirst()
            }
            seenSyncIds.addLast(incomingSyncId)
            lastSyncId = incomingSyncId
        } else if (incomingRev <= localRev) {
            return
        }

        suppressSync = true
        try {
            localRev = maxOf(localRev, incomingRev) + 1
            binding.tvSyncRev.text = "REV: $localRev"

            if (json.has("custom_buffer_ms")) {
                val buf = quantizeTarget(json.getDouble("custom_buffer_ms").toFloat())
                if (!binding.sliderTargetBuffer.isPressed) {
                    syncAllTargetBufferSliders(buf)
                }
                AudioNative.nativeSetTargetBufferMs(buf)
            }
            if (json.has("buffer_mode")) {
                val mode = when (json.getString("buffer_mode")) {
                    "LOW" -> "LOW"
                    "MEDIUM" -> "MEDIUM"
                    "HIGH" -> "HIGH"
                    "AUTO" -> "AUTO"
                    else -> "CUSTOM"
                }
                syncAllBufferModeRadioGroups(mode)
            }
            if (json.has("auto_buffer")) {
                AudioNative.nativeSetAutoMode(json.getBoolean("auto_buffer"))
            }
            if (json.has("volume_gain")) {
                val g = json.getDouble("volume_gain").toFloat().coerceIn(0f, 2f)
                try {
                    binding.sliderGain.value = quantizeGain(g)
                    binding.tvGainValue.text = "${(g * 100).toInt()}%"
                } catch (e: Exception) {
                    Log.w("AirSpeaker", "Could not set sliderGain value: ${e.message}")
                }
                AudioNative.nativeSetVolumeGain(1.0f)
            }
            if (json.has("muted")) {
                val m = json.getBoolean("muted")
                AudioNative.nativeSetMuted(m)
                binding.btnMuteToggle.text = if (m) "MUTED" else "MUTE"
            }
            if (json.has("audio_profile")) {
                when (json.getString("audio_profile")) {
                    "QUALITY" -> binding.rbProfileQuality.isChecked = true
                    "GAMING" -> binding.rbProfileGaming.isChecked = true
                    "BALANCED" -> binding.rbProfileBalanced.isChecked = true
                    else -> binding.rbProfileCustom.isChecked = true
                }
            }
            if (json.has("codec")) {
                val isOpus = (json.getInt("codec") == 1)
                binding.rbCodecOpus.isChecked = isOpus
                binding.rbCodecPcm.isChecked = !isOpus
                binding.rbPlayerCodecOpus.isChecked = isOpus
                binding.rbPlayerCodecPcm.isChecked = !isOpus
            }
            if (json.has("opus_bitrate_kbps")) {
                val kbps = json.getInt("opus_bitrate_kbps").toString()
                val idx = bitrateValues.indexOf(kbps)
                if (idx >= 0) {
                    binding.spinnerBitrate.setSelection(idx)
                    binding.spPlayerBitrate.setSelection(idx)
                }
            }
            if (json.has("frame_ms")) {
                currentFrameMs = json.getInt("frame_ms")
            }
            updateCodecBitrateUiState(binding.rbCodecOpus.isChecked)
            updateExpectedDelayUI()

            if (json.has("direction")) {
                val dir = json.getString("direction")
                if (dir == "PHONE_TO_PC" && !AudioStreamService.isServing) {
                    selectTab(1)
                }
            }
            if (json.has("audio_output_engine")) {
                if (json.getString("audio_output_engine") == "AUDIOTRACK_STANDARD") {
                    binding.rbEngineAudioTrack.isChecked = true
                } else {
                    binding.rbEngineOboe.isChecked = true
                }
            }
            if (json.has("enable_audio_effects")) {
                binding.switchAudioFx.isChecked = json.getBoolean("enable_audio_effects")
            }
            if (json.has("exclusive_audio_mode")) {
                when (json.getString("exclusive_audio_mode")) {
                    "EXCLUSIVE" -> binding.rbFocusExclusive.isChecked = true
                    "ALWAYS_PLAY" -> binding.rbFocusAlways.isChecked = true
                    else -> binding.rbFocusMix.isChecked = true
                }
            }
            if (json.has("stop_on_headset_disconnect")) {
                binding.switchHeadsetDisconnect.isChecked = json.getBoolean("stop_on_headset_disconnect")
            }

            val lowLatency = binding.rbEngineOboe.isChecked && !binding.switchAudioFx.isChecked
            AudioNative.nativeSetLowLatencyMode(lowLatency)

            if (json.has("pc_streaming")) {
                pcStreamingHintActive = json.optBoolean("pc_streaming", false)
            }
        } finally {
            suppressSync = false
        }
    }

    // ------------------------------------------------------------------
    // Telemetry & UI Refresh
    // ------------------------------------------------------------------

    private fun toggleServing() {
        if (AudioStreamService.isServing) {
            val intent = Intent(this, AudioStreamService::class.java).apply {
                action = AudioStreamService.ACTION_STOP_SERVE
            }
            startService(intent)
            handler.postDelayed({
                if (!AudioStreamService.isStreaming && !AudioStreamService.isServing) {
                    startPreStreamDiscoveryIfNeeded()
                }
                refreshServiceButtonState()
            }, 500L)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                launchMediaProjectionCapture()
            } else {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        handler.postDelayed({ refreshServiceButtonState() }, 200L)
    }

    private fun refreshServiceButtonState() {
        if (AudioStreamService.isStreaming) {
            binding.btnToggleStream.text = "[ ■ STOP SPEAKER PLAYER (PORT 5004) ]"
            binding.btnToggleStream.setTextColor(0xFFFF4444.toInt())
            binding.btnTogglePartyStream.text = "[ ■ LEAVE / STOP PARTY STREAM ]"
            binding.btnTogglePartyStream.setTextColor(0xFFFF4444.toInt())
            binding.btnTogglePartyStream.backgroundTintList = ColorStateList.valueOf(0xFF330000.toInt())
            val isHostMode = (partyModeScreen == "HOST") || binding.rbPartyHost.isChecked
            if (isHostMode || (AudioNative.nativeIsPartyRelay() && !isPartyExtendedActive && !binding.switchExtendParty.isChecked)) {
                binding.btnTogglePartyHost.text = "[ ■ STOP PARTY BROADCAST (HOST) ]"
                binding.btnTogglePartyHost.setTextColor(0xFFFF4444.toInt())
                binding.btnTogglePartyHost.backgroundTintList = ColorStateList.valueOf(0xFF330000.toInt())
                binding.btnTogglePartyHost.strokeColor = ColorStateList.valueOf(0xFFFF4444.toInt())
            } else {
                if (isPartyExtendedActive || binding.switchExtendParty.isChecked) {
                    binding.btnTogglePartyJoin.text = "[ ■ STOP EXTENDING & LEAVE PARTY ]"
                } else {
                    binding.btnTogglePartyJoin.text = "[ ■ LEAVE PARTY STREAM ]"
                }
                binding.btnTogglePartyJoin.setTextColor(0xFFFF4444.toInt())
                binding.btnTogglePartyJoin.backgroundTintList = ColorStateList.valueOf(0xFF330000.toInt())
                binding.btnTogglePartyJoin.strokeColor = ColorStateList.valueOf(0xFFFF4444.toInt())
            }
        } else {
            binding.btnToggleStream.text = "[ ▶ START SPEAKER PLAYER (PORT 5004) ]"
            binding.btnToggleStream.setTextColor(0xFF00FF00.toInt())
            binding.btnTogglePartyStream.text = "[ 🎉 START / JOIN PARTY STREAM (PORT 5004) ]"
            binding.btnTogglePartyStream.setTextColor(0xFF00FF00.toInt())
            binding.btnTogglePartyStream.backgroundTintList = ColorStateList.valueOf(0xFF002B00.toInt())
            binding.btnTogglePartyHost.text = "[ 👑 START PARTY BROADCAST (HOST) ]"
            binding.btnTogglePartyHost.setTextColor(0xFF00FF00.toInt())
            binding.btnTogglePartyHost.backgroundTintList = ColorStateList.valueOf(0xFF002B00.toInt())
            binding.btnTogglePartyHost.strokeColor = ColorStateList.valueOf(0xFF00FF00.toInt())
            binding.btnTogglePartyJoin.text = if (isPartyExtendedActive || binding.switchExtendParty.isChecked) "[ 📡 JOIN & EXTEND PARTY ]" else "[ 🎧 JOIN PARTY STREAM ]"
            binding.btnTogglePartyJoin.setTextColor(0xFF00FF00.toInt())
            binding.btnTogglePartyJoin.backgroundTintList = ColorStateList.valueOf(0xFF002B00.toInt())
            binding.btnTogglePartyJoin.strokeColor = ColorStateList.valueOf(0xFF00FF00.toInt())
        }

        if (AudioStreamService.isServing) {
            binding.btnToggleServe.text = "[ ■ STOP SERVING AUDIO ]"
            binding.btnToggleServe.setTextColor(0xFFFF4444.toInt())
            binding.tvServeStatus.text = "STREAMING INTERNAL AUDIO (PORT 5004)"
            binding.tvServeStatus.setTextColor(0xFF00FF00.toInt())
            val pps = AudioStreamService.internalAudioStreamerInstance?.txPps?.get() ?: 0
            binding.tvServePps.text = "TX PPS: $pps pps"
            val peak = AudioStreamService.internalAudioStreamerInstance?.peakLevel?.get() ?: 0.0f
            binding.pbServeLevel.progress = (peak * 100).toInt().coerceIn(0, 100)
        } else {
            binding.btnToggleServe.text = "[ 📡 START SERVING AUDIO (PORT 5004) ]"
            binding.btnToggleServe.setTextColor(0xFF00FF88.toInt())
            binding.tvServeStatus.text = "AUDIO SENDER: READY (PORT 5004)"
            binding.tvServeStatus.setTextColor(0xFF888888.toInt())
            binding.tvServePps.text = "TX PPS: 0 pps"
            binding.pbServeLevel.progress = 0
        }

        val statusParts = mutableListOf<String>()
        if (AudioStreamService.isStreaming) statusParts.add("PLAY: ON (5004)")
        if (AudioStreamService.isServing) statusParts.add("SERVE: ON (5004)")
        if (statusParts.isEmpty()) {
            if (pcStreamingHintActive) {
                binding.tvStatus.text = "PC IS STREAMING — TAP START SPEAKER PLAYER"
                binding.tvStatus.setTextColor(0xFF00EEEE.toInt())
            } else {
                binding.tvStatus.text = "STATUS: STOPPED"
                binding.tvStatus.setTextColor(0xFF888888.toInt())
            }
        } else {
            binding.tvStatus.text = "ACTIVE: " + statusParts.joinToString(" | ")
            binding.tvStatus.setTextColor(0xFF00FF00.toInt())
        }
    }

    private fun updateStatsUI() {
        refreshServiceButtonState()
        try {
            val stats = AudioNative.getStats()
            val safeBuffer = if (stats.bufferMs.isFinite()) stats.bufferMs else 0f
            val target = quantizeTarget(stats.targetMs)
            val (estTotal, _) = computeExpectedDelayMs()
            binding.latencyGraph.pushSample(safeBuffer, target, estTotal.toFloat())

            val servePpsNow = AudioStreamService.internalAudioStreamerInstance?.txPps?.get() ?: 0
            val servePeakNow = AudioStreamService.internalAudioStreamerInstance?.peakLevel?.get() ?: 0f
            val activeServeBuf = if (safeBuffer > 0.1f) {
                safeBuffer
            } else if (AudioStreamService.isServing && servePpsNow > 0) {
                target + (servePeakNow * 5.0f)
            } else {
                0f
            }
            binding.latencyGraphServe.pushSample(activeServeBuf, target, estTotal.toFloat())

            binding.tvBufferLevel.text = String.format("%.1f ms", safeBuffer)
            val targetText = if (target % 1f == 0f) "${target.toInt()} ms" else String.format(java.util.Locale.US, "%.1f ms", target)
            binding.tvActiveTarget.text = targetText
            // Section 9: Do not mutate Material Slider with callback telemetry values
            binding.tvPps.text = "${stats.rxPps} pps"
            binding.tvUnderruns.text = "${stats.underruns}"
            binding.tvLostPackets.text = "${stats.lostPackets}"
            binding.tvLatePackets.text = "${stats.latePackets}"
            binding.tvCodec.text = if (stats.codec == 1) "Opus" else "PCM16"

            // Once-per-second advanced telemetry logging
            val adv = AudioNative.getAdvancedStats()
            val currentTimeMs = SystemClock.elapsedRealtime()
            if (currentTimeMs - lastAdvLogTime >= 1000L) {
                lastAdvLogTime = currentTimeMs
                Log.d("NativeStats", "Telemetry: buffer=${adv.bufferMs}ms target=${adv.targetMs}ms underruns=${adv.underruns} lost=${adv.lostPackets} late=${adv.latePackets} dup=${adv.duplicatePackets} concealed=${adv.concealedPackets} driftDrops=${adv.intentionalDriftDrops} pktQueueDrops=${adv.packetQueueOverflowDrops} ringDrops=${adv.pcmRingOverflowDrops} ringUnderruns=${adv.pcmRingUnderrunFrames} availFrames=${adv.pcmRingAvailableFrames} avgCbMicros=${String.format(java.util.Locale.US, "%.1f", adv.callbackAverageMicros)} maxCbMicros=${adv.callbackMaxMicros} misses=${adv.callbackDeadlineMisses} pps=${String.format(java.util.Locale.US, "%.1f", adv.rxPps)}")
            }

            // Auto-update connected PC IP if empty / network changed
            val rawPcIp = AudioStreamService.activeControlChannel?.getActivePcIp()
                ?: preStreamDiscoveryChannel?.getActivePcIp()
                ?: AudioNative.nativeGetSenderIp()
            val isOwn = (AudioStreamService.activeControlChannel ?: preStreamDiscoveryChannel)?.isOwnLocalIp(rawPcIp) == true
            val discoveredPcIp = if (!isOwn && !rawPcIp.isNullOrBlank()) rawPcIp else null

            if (!binding.etPcIp.hasFocus()) {
                val currentText = binding.etPcIp.text?.toString()?.trim().orEmpty()
                val currentIsOwn = (AudioStreamService.activeControlChannel ?: preStreamDiscoveryChannel)?.isOwnLocalIp(currentText) == true
                if (discoveredPcIp != null && (currentText.isEmpty() || currentIsOwn || (userEditedPcIp == null && discoveredPcIp != lastAppliedDiscoveredPcIp))) {
                    lastAppliedDiscoveredPcIp = discoveredPcIp
                    binding.etPcIp.setText(discoveredPcIp)
                }
            }
            if (discoveredPcIp != null && !binding.etServeTargetIp.hasFocus() && binding.etServeTargetIp.text.isNullOrBlank()) {
                binding.etServeTargetIp.setText(discoveredPcIp)
            }
            val displayIp = discoveredPcIp ?: userEditedPcIp
            val pcName = AudioStreamService.activeControlChannel?.getActivePcName()
                ?: preStreamDiscoveryChannel?.getActivePcName()
                ?: "PC"

            val lastPcSeen = AudioStreamService.activeControlChannel?.lastPcSeenElapsedMs
                ?: preStreamDiscoveryChannel?.lastPcSeenElapsedMs
                ?: 0L
            val isConnected = stats.rxPps > 0 || (lastPcSeen > 0 && (SystemClock.elapsedRealtime() - lastPcSeen) < 6000L)

            binding.tvConnectedDeviceTitle.text = if (!displayIp.isNullOrBlank()) {
                if (isConnected) "💻 Connected to: $pcName ($displayIp)"
                else "💻 PC ($displayIp) — Idle / Waiting..."
            } else "💻 Waiting for PC connection..."
            val partyPeers = ControlChannel.partyPeerIps.size
            binding.tvConnectedDeviceSub.text = if (partyPeers > 0) {
                "📱 ${ControlChannel.getPhoneModelName()}  •  🎉 Party Mode: $partyPeers peer(s)"
            } else {
                "📱 This Phone: ${ControlChannel.getPhoneModelName()}"
            }

            // Party Tab UI Status & Peers List
            val isRelay = AudioNative.nativeIsPartyRelay()
            val isExtend = isPartyExtendedActive || binding.switchExtendParty.isChecked
            if (AudioStreamService.isStreaming) {
                if ((partyModeScreen == "HOST") || binding.rbPartyHost.isChecked || (isRelay && !isExtend)) {
                    binding.tvPartyStatusBanner.text = "PARTY STATUS: HOSTING / RELAYING ($partyPeers peer(s) connected)"
                    binding.tvPartyStatusBanner.setTextColor(0xFF00FF00.toInt())
                } else if (isExtend) {
                    binding.tvPartyStatusBanner.text = "PARTY STATUS: JOINED & EXTENDING ($partyPeers downstream peer(s))"
                    binding.tvPartyStatusBanner.setTextColor(0xFF00FF00.toInt())
                } else {
                    binding.tvPartyStatusBanner.text = "PARTY STATUS: JOINED & PLAYING (Shared Sound Active)"
                    binding.tvPartyStatusBanner.setTextColor(0xFF00FF00.toInt())
                }
            } else {
                binding.tvPartyStatusBanner.text = "PARTY STATUS: STANDBY — Tap Start/Join Party Stream"
                binding.tvPartyStatusBanner.setTextColor(0xFF888888.toInt())
            }

            // Update Host peers list
            val sbPeers = StringBuilder()
            if (ControlChannel.partyPeerIps.isNotEmpty()) {
                sbPeers.append("🎉 Connected Joiners ($partyPeers):\n")
                for ((idx, peerIp) in ControlChannel.partyPeerIps.withIndex()) {
                    sbPeers.append("   #${idx + 1}  $peerIp:5004  •  CONNECTED\n")
                }
            } else {
                sbPeers.append("Waiting for other devices to join...")
            }
            binding.tvPartyHostPeersList.text = sbPeers.toString().trimEnd()
            binding.tvPartyPeersList.text = sbPeers.toString().trimEnd()

            // Update Extended Peers list
            if (isExtend) {
                val extPeers = ControlChannel.partyPeerIps
                if (extPeers.isNotEmpty()) {
                    val sbExt = StringBuilder("📡 Connected Downstream Devices (${extPeers.size}):\n")
                    for ((idx, peerIp) in extPeers.withIndex()) {
                        sbExt.append("   #${idx + 1}  $peerIp:5004  •  RELAYING\n")
                    }
                    binding.tvExtendedPeersList.text = sbExt.toString().trimEnd()
                } else {
                    binding.tvExtendedPeersList.text = "Extended Party Peers: Waiting for friends to scan your QR..."
                }
            }

            // Update Discovered Hosts list
            if (partyModeScreen == "JOIN") {
                renderDiscoveredHostButtons()
            }

            // Party Joiner keep-alive re-announcement every 3s so the Host keeps this Joiner registered
            val nowMs = SystemClock.elapsedRealtime()
            if (isPartyJoinedActive && AudioStreamService.isStreaming && (nowMs - lastJoinKeepAliveMs >= 3000L)) {
                lastJoinKeepAliveMs = nowMs
                val hIp = if (joinedHostIp.isNotEmpty()) joinedHostIp else ControlChannel.lastKnownPcIp.orEmpty()
                if (hIp.isNotEmpty()) {
                    val (parsedHostIp, _) = parseHostIpFromUri(hIp)
                    if (parsedHostIp.isNotEmpty()) {
                        ControlChannel.sendPartyJoinAnnouncement(parsedHostIp)
                    }
                }
            }

            // Update Joiner status
            if (AudioStreamService.isStreaming && isPartyJoinedActive) {
                val hIp = if (joinedHostIp.isNotEmpty()) joinedHostIp else displayIp
                val extSuffix = if (isExtend) " • Re-broadcasting to extended peers" else ""
                binding.tvPartyJoinStatus.text = "PARTY JOINER: Connected to $hIp:$joinedHostPort • Playing host sound in sync$extSuffix"
                binding.tvPartyJoinStatus.setTextColor(0xFF00FF00.toInt())
                binding.btnTogglePartyJoin.visibility = View.VISIBLE
                binding.btnTogglePartyJoin.text = "[ ■ LEAVE PARTY ]"
            } else {
                binding.tvPartyJoinStatus.text = "PARTY JOINER: Standby (Scan QR or tap an active host above)"
                binding.tvPartyJoinStatus.setTextColor(0xFF888888.toInt())
                binding.btnTogglePartyJoin.visibility = View.GONE
            }

            // Serve TX PPS
            val servePps = AudioStreamService.internalAudioStreamerInstance?.txPps?.get() ?: 0
            binding.tvServePps.text = "TX PPS: $servePps pps"

            // Keep mute button text synced with native engine mute state
            binding.btnMuteToggle.text = if (AudioNative.nativeIsMuted()) "MUTED" else "MUTE"

        } catch (e: Exception) {
            Log.e("AirSpeaker", "updateStatsUI error", e)
        }
    }

    private fun refreshBatteryOptimizationStatus() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
            if (isIgnoring) {
                binding.btnBatteryOpt.text = "BATTERY OPTIMIZATION: EXEMPT (OK)"
                binding.btnBatteryOpt.isEnabled = false
            } else {
                binding.btnBatteryOpt.text = "REQUEST BATTERY EXEMPTION (RECOMMENDED)"
                binding.btnBatteryOpt.isEnabled = true
            }
        } catch (_: Exception) {}
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun refreshLocalIps() {
        val sb = StringBuilder()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name
                val lower = name.lowercase()
                if (lower.startsWith("rmnet") || lower.startsWith("ccmni") || lower.startsWith("dummy")) {
                    continue
                }
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        sb.append(String.format("%-10s : %s\n", name, addr.hostAddress))
                    }
                }
            }
        } catch (e: Exception) {
            sb.append("Error reading network interfaces: ${e.message}")
        }
        if (sb.isEmpty()) sb.append("No active Wi-Fi / Hotspot / Tether IPv4 found")
        binding.tvIpList.text = sb.toString().trimEnd()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        }
    }
}
