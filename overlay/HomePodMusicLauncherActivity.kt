package com.airplay.streamer

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.discovery.DiscoveryRepository
import com.airplay.streamer.engine.SinkFactory
import com.airplay.streamer.engine.SpeakerStatus
import com.airplay.streamer.engine.Transport
import com.airplay.streamer.service.AudioCaptureService
import com.airplay.streamer.service.StreamController
import com.airplay.streamer.util.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Minimal Samsung-facing one-touch controller for the unmodified upstream AirPlay sender.
 * Does not bypass MediaProjection. Connecting is always explicitly initiated by the user
 * (main button or Quick Settings tile). The actual consent prompt belongs to upstream.
 */
class HomePodMusicLauncherActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_QUICK_CONNECT = "ua.homepodmusic.extra.QUICK_CONNECT"
        private const val YTM_PACKAGE = "com.google.android.apps.youtube.music"
        private const val STORE = "homepod_music_ui"
        private const val KEY_FAVORITE = "favorite_speaker"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_PENDING = "pendingYoutubeLaunch"
        private const val KEY_TARGET = "pendingTarget"
        private const val KEY_QUICK_START = "quickStart"
        private const val CONNECT_TIMEOUT_MS = 60_000L
    }

    private val prefs by lazy { Prefs(this) }
    private val repository by lazy { DiscoveryRepository.getInstance(this) }
    private val bg = Color.rgb(12, 18, 34)
    private val white = Color.rgb(245, 247, 255)
    private val secondary = Color.rgb(176, 190, 210)
    private val accent = Color.rgb(124, 178, 255)
    private lateinit var status: TextView
    private lateinit var chosen: TextView
    private lateinit var connectButton: Button
    private lateinit var stopButton: Button
    private lateinit var volumeBar: SeekBar
    private lateinit var volumeLabel: TextView
    private lateinit var speakersBox: LinearLayout
    private var discovered: List<AirPlayDevice> = emptyList()
    private var selectedId: String? = null
    private var pendingYoutubeLaunch = false
    private var pendingTarget: String? = null
    private var connectStarted = 0L
    private var quickStart = false
    private var tileAutoConnectConsumed = false
    private var sessionActive = false
    private var activeReceiverId: String? = null

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it } && StreamController.hasRuntimePermissions(this)) {
            status.text = "Дозволи надано. Шукаємо HomePod…"
            repository.ensureFresh()
            maybeStartFromTile()
        } else {
            quickStart = false
            status.text = "Надай дозволи Android, щоб знаходити колонку і передавати аудіо."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        prefs.useShizukuCapture = false // Explicit Android MediaProjection approval, never privileged capture.
        selectedId = getSharedPreferences(STORE, MODE_PRIVATE).getString(KEY_FAVORITE, null)
            ?: prefs.lastSpeakers.singleOrNull()
        pendingYoutubeLaunch = savedInstanceState?.getBoolean(KEY_PENDING) ?: false
        pendingTarget = savedInstanceState?.getString(KEY_TARGET)
        quickStart = savedInstanceState?.getBoolean(KEY_QUICK_START)
            ?: intent.getBooleanExtra(EXTRA_QUICK_CONNECT, false)
        connectStarted = savedInstanceState?.getLong(KEY_STARTED_AT)
            ?: if (pendingYoutubeLaunch) SystemClock.elapsedRealtime() else 0L
        buildUi()
        showDevices()
        if (pendingYoutubeLaunch && pendingTarget != null) {
            scheduleTimeout(pendingTarget!!, connectStarted)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(repository.devices, AudioCaptureService.state) { devices, session -> devices to session }
                    .collect { (devices, session) ->
                        discovered = devices.filter {
                            SinkFactory.transportFor(it, prefs.preferAirPlay2) != Transport.UNSUPPORTED
                        }
                        if (selectedId == null) selectedId = prefs.lastSpeakers.singleOrNull()
                        sessionActive = session.capturing
                        activeReceiverId = session.speakers.firstOrNull {
                            it.status == SpeakerStatus.PLAYING
                        }?.identity
                        showDevices()
                        val target = pendingTarget
                        val speaker = target?.let { session.speaker(it) }
                        if (pendingYoutubeLaunch && target != null) {
                            when (speaker?.status) {
                                SpeakerStatus.PLAYING -> {
                                    if (QuickConnectPolicy.readyToLaunch(session.capturing, true)) {
                                        pendingYoutubeLaunch = false
                                        connectStarted = 0L
                                        status.text = "HomePod підключено. Відкриваємо YouTube Music…"
                                        openYoutubeMusic()
                                    } else {
                                        status.text = "Очікуємо запуску захоплення аудіо…"
                                    }
                                }
                                SpeakerStatus.NEEDS_PIN, SpeakerStatus.NEEDS_PASSWORD,
                                SpeakerStatus.NEEDS_ACCEPT -> {
                                    pendingYoutubeLaunch = false
                                    status.text = "HomePod очікує підтвердження або PIN. Відкриваємо керування підключенням."
                                    startActivity(Intent(this@HomePodMusicLauncherActivity, MainActivity::class.java))
                                }
                                SpeakerStatus.FAILED -> {
                                    pendingYoutubeLaunch = false
                                    status.text = "Не вдалося з’єднатись. Перевір HomePod і спробуй ще раз."
                                }
                                else -> {
                                    if (SystemClock.elapsedRealtime() - connectStarted > CONNECT_TIMEOUT_MS) {
                                        pendingYoutubeLaunch = false
                                        status.text = "Підключення триває надто довго. Спробуй вибрати колонку вручну."
                                    } else {
                                        status.text = "Підключаємо HomePod… Підтверди запит Android."
                                    }
                                }
                            }
                        } else if (session.capturing && session.speakers.any {
                            it.status == SpeakerStatus.PLAYING
                        }) {
                            status.text = "Трансляція активна. Відкрий YouTube Music."
                        } else if (discovered.isNotEmpty()) {
                            status.text = "Колонка доступна. Натисни «Підключити»."
                        } else {
                            status.text = "Шукаємо AirPlay-пристрої у Wi-Fi…"
                        }
                        maybeStartFromTile()
                    }
            }
        }
        if (!StreamController.hasRuntimePermissions(this)) {
            runtimePermissionLauncher.launch(StreamController.runtimePermissions())
        }
    }

    override fun onStart() {
        super.onStart()
        repository.startDiscovery()
    }

    override fun onStop() {
        repository.stopDiscovery()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(KEY_STARTED_AT, connectStarted)
        outState.putBoolean(KEY_PENDING, pendingYoutubeLaunch)
        outState.putString(KEY_TARGET, pendingTarget)
        outState.putBoolean(KEY_QUICK_START, quickStart)
        super.onSaveInstanceState(outState)
    }

    private fun maybeStartFromTile() {
        if (!quickStart || tileAutoConnectConsumed || pendingYoutubeLaunch ||
            !StreamController.hasRuntimePermissions(this)) return
        val preferred = getSharedPreferences(STORE, MODE_PRIVATE)
            .getString(KEY_FAVORITE, null)
        val id = QuickConnectPolicy.choose(preferred, prefs.lastSpeakers,
            discovered.map { it.identity }.toSet())
        val pick = discovered.firstOrNull { it.identity == id }
        if (pick != null) {
            tileAutoConnectConsumed = true
            quickStart = false
            selectAndConnect(pick)
        }
    }

    private fun selectAndConnect(device: AirPlayDevice) {
        when (QuickConnectPolicy.connectAction(
            pendingYoutubeLaunch,
            StreamController.hasRuntimePermissions(this),
            packageManager.getLaunchIntentForPackage(YTM_PACKAGE) != null,
            sessionActive && activeReceiverId == device.identity
        )) {
            QuickConnectPolicy.ConnectionAction.ASK_PERMISSIONS -> {
                status.text = "Спочатку підтверди дозволи Android."
                runtimePermissionLauncher.launch(StreamController.runtimePermissions())
                return
            }
            QuickConnectPolicy.ConnectionAction.BUSY -> return
            QuickConnectPolicy.ConnectionAction.REQUIRE_YOUTUBE -> {
                status.text = "Спочатку встанови YouTube Music."
                return
            }
            QuickConnectPolicy.ConnectionAction.OPEN_YOUTUBE -> {
                openYoutubeMusic()
                return
            }
            QuickConnectPolicy.ConnectionAction.CONNECT -> { /* Continue below. */ }
        }
        selectedId = device.identity
        getSharedPreferences(STORE, MODE_PRIVATE).edit()
            .putString(KEY_FAVORITE, device.identity).apply()
        pendingTarget = device.identity
        pendingYoutubeLaunch = true
        connectStarted = SystemClock.elapsedRealtime()
        status.text = "Підключаємо ${device.displayName}…"
        scheduleTimeout(device.identity, connectStarted)
        StreamController.connect(this, device) // The official system MediaProjection consent.
    }

    private fun scheduleTimeout(identity: String, startedAt: Long) {
        lifecycleScope.launch {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            delay((CONNECT_TIMEOUT_MS - elapsed).coerceAtLeast(1L))
            if (pendingYoutubeLaunch && pendingTarget == identity && connectStarted == startedAt) {
                pendingYoutubeLaunch = false
                status.text = "Час підключення вичерпано. Перевір Wi-Fi або відкрий додаткове керування."
                showDevices()
            }
        }
    }

    private fun openYoutubeMusic() {
        val launch = packageManager.getLaunchIntentForPackage(YTM_PACKAGE)
        if (launch == null) {
            Toast.makeText(this, "Встанови YouTube Music із Google Play", Toast.LENGTH_LONG).show()
            return
        }
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } catch (_: Exception) {
            Toast.makeText(this, "Не вдалося відкрити YouTube Music", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun bgShape(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(18).toFloat()
    }
    private fun label(value: String, size: Float, color: Int = white, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
        }
    private fun action(value: String, color: Int = accent, foreground: Int = bg): Button =
        Button(this).apply {
            isAllCaps = false
            text = value
            textSize = 16f
            setTextColor(foreground)
            background = bgShape(color)
            minHeight = dp(54)
        }
    private fun gap(container: LinearLayout, height: Int) {
        container.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(36), dp(22), dp(32))
        }
        scroll.addView(root)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            background = bgShape(Color.rgb(22, 32, 51))
        }
        val maxWidth = dp(490)
        val usable = resources.displayMetrics.widthPixels - dp(44)
        root.addView(panel, LinearLayout.LayoutParams(minOf(usable, maxWidth), -2))
        panel.addView(label("♫  HomePod Music", 27f, white, true))
        gap(panel, 9)
        panel.addView(label("Samsung → YouTube Music → HomePod", 14f, secondary))
        gap(panel, 24)
        chosen = label("Остання колонка: —", 18f, white, true)
        panel.addView(chosen)
        gap(panel, 12)
        status = label("Шукаємо HomePod…", 14f, secondary)
        panel.addView(status)
        gap(panel, 20)
        connectButton = action("Підключити й відкрити YouTube Music")
        panel.addView(connectButton, LinearLayout.LayoutParams(-1, dp(62)))
        connectButton.contentDescription = "Підключити вибрану колонку й відкрити YouTube Music"
        connectButton.setOnClickListener {
            val preferred = discovered.firstOrNull { it.identity == selectedId }
            if (preferred != null) selectAndConnect(preferred)
            else status.text = "Оберіть HomePod зі списку нижче або оновіть пошук."
        }
        gap(panel, 10)
        stopButton = action("Зупинити трансляцію", Color.rgb(114, 43, 52), white)
        panel.addView(stopButton, LinearLayout.LayoutParams(-1, dp(52)))
        stopButton.setOnClickListener {
            pendingYoutubeLaunch = false
            pendingTarget = null
            quickStart = false
            StreamController.stopAll(this)
            status.text = "Трансляцію зупинено."
            showDevices()
        }
        gap(panel, 14)
        val volumeTitle = label("Гучність HomePod", 15f, white, true)
        panel.addView(volumeTitle)
        gap(panel, 5)
        volumeLabel = label("60%", 13f, secondary)
        panel.addView(volumeLabel)
        volumeBar = SeekBar(this).apply { max = 100; progress = 60; contentDescription = "Гучність HomePod" }
        panel.addView(volumeBar, LinearLayout.LayoutParams(-1, dp(50)))
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updateVolume(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        gap(panel, 12)
        panel.addView(label("Доступні колонки", 16f, white, true))
        gap(panel, 10)
        speakersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(speakersBox)
        gap(panel, 10)
        val refresh = action("Оновити пошук колонок", Color.rgb(42, 59, 88), white)
        panel.addView(refresh, LinearLayout.LayoutParams(-1, dp(50)))
        refresh.setOnClickListener {
            repository.refresh()
            status.text = "Оновлюємо список колонок у Wi-Fi…"
        }
        gap(panel, 20)
        val youtube = action("Відкрити YouTube Music", Color.rgb(42, 59, 88), white)
        panel.addView(youtube, LinearLayout.LayoutParams(-1, dp(50)))
        youtube.setOnClickListener { openYoutubeMusic() }
        gap(panel, 10)
        val advanced = action("Додаткове керування та PIN", Color.rgb(42, 59, 88), white)
        panel.addView(advanced, LinearLayout.LayoutParams(-1, dp(50)))
        advanced.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        gap(panel, 18)
        panel.addView(label("Для нової сесії потрібен системний дозвіл Android. Можна додати плитку HomePod Music до швидких налаштувань Samsung. Кнопка Cast у YouTube Music не змінюється.", 12f, secondary))
        setContentView(scroll)
    }

    private fun updateVolume(progress: Int) {
        val id = selectedId ?: return
        val value = QuickConnectPolicy.volumeFraction(progress)
        prefs.setSpeakerVolume(id, value)
        volumeLabel.text = "${progress.coerceIn(0, 100)}%"
        if (id == activeReceiverId && sessionActive) {
            StreamController.setSpeakerVolume(id, value)
        }
    }

    private fun showDevices() {
        if (!::speakersBox.isInitialized) return
        val selected = discovered.firstOrNull { it.identity == selectedId }
        val savedName = selected?.displayName ?: if (selectedId != null) "збережена (поза мережею)" else "не вибрано"
        chosen.text = "HomePod: $savedName"
        connectButton.isEnabled = QuickConnectPolicy.shouldEnableConnect(selected != null, pendingYoutubeLaunch)
        stopButton.isEnabled = QuickConnectPolicy.shouldEnableStop(sessionActive, pendingYoutubeLaunch)
        stopButton.alpha = if (stopButton.isEnabled) 1f else 0.50f
        val enabledVolume = QuickConnectPolicy.canAdjustRemoteVolume(
            selected != null || (sessionActive && selectedId == activeReceiverId && selectedId != null)
        )
        volumeBar.isEnabled = enabledVolume
        volumeBar.alpha = if (enabledVolume) 1f else 0.50f
        val selectedVolume = selectedId?.let { prefs.speakerVolume(it) } ?: 0.60f
        val volumePct = (selectedVolume * 100).toInt().coerceIn(0, 100)
        if (!volumeBar.isPressed) volumeBar.progress = volumePct
        volumeLabel.text = "$volumePct%"
        connectButton.alpha = if (connectButton.isEnabled) 1f else 0.55f
        speakersBox.removeAllViews()
        for (device in discovered) {
            val active = device.identity == selectedId
            val name = (if (active) "● " else "○ ") + device.displayName
            val button = action(name, Color.rgb(37, 51, 74), white)
            speakersBox.addView(button, LinearLayout.LayoutParams(-1, dp(52)))
            button.contentDescription = "Обрати та підключити ${device.displayName}"
            button.isEnabled = !pendingYoutubeLaunch
            button.setOnClickListener { selectAndConnect(device) }
            gap(speakersBox, 8)
        }
        if (discovered.isEmpty()) {
            speakersBox.addView(label("Колонок поки не знайдено. Перевір Wi-Fi.", 13f, secondary))
        }
    }
}
