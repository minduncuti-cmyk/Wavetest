package com.example.waveeq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

data class EqUiState(
    val serviceRunning: Boolean = false,
    val eqEnabled: Boolean = true,
    val bandCount: Int = 5,
    val bandFreqsHz: List<Int> = listOf(60, 230, 910, 3600, 14000),
    val minLevelMb: Int = -1500,
    val maxLevelMb: Int = 1500,
    val bandLevelsMb: List<Int> = listOf(0, 0, 0, 0, 0),
    val bassStrength: Int = 0,       // 0..1000
    val loudnessGainMb: Int = 0,     // 0..1500
    val selectedPreset: String = "Flat",
    val activeSessionsCount: Int = 0
)

private class EffectChain(sessionId: Int) {
    val equalizer: Equalizer? = runCatching { Equalizer(100, sessionId) }.getOrNull()
    val bassBoost: BassBoost? = runCatching { BassBoost(100, sessionId) }.getOrNull()
    val loudness: LoudnessEnhancer? = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()

    fun apply(state: EqUiState) {
        val active = state.eqEnabled
        equalizer?.let { eq ->
            runCatching {
                eq.enabled = active
                val bands = eq.numberOfBands.toInt()
                for (i in 0 until bands) {
                    val lvl = state.bandLevelsMb.getOrElse(i) { 0 }
                        .coerceIn(state.minLevelMb, state.maxLevelMb)
                    eq.setBandLevel(i.toShort(), lvl.toShort())
                }
            }
        }
        bassBoost?.let { bb ->
            runCatching {
                if (bb.strengthSupported) {
                    bb.enabled = active && state.bassStrength > 0
                    bb.setStrength(state.bassStrength.coerceIn(0, 1000).toShort())
                }
            }
        }
        loudness?.let { le ->
            runCatching {
                le.enabled = active && state.loudnessGainMb > 0
                le.setTargetGain(state.loudnessGainMb.coerceIn(0, 1500))
            }
        }
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { loudness?.release() }
    }
}

object EqAudioManager {
    private const val PREFS_NAME = "waveeq_prefs"
    private val _state = MutableStateFlow(EqUiState())
    val state: StateFlow<EqUiState> = _state.asStateFlow()

    private val sessions = ConcurrentHashMap<Int, EffectChain>()
    private var metadataLoaded = false

    val presets: Map<String, List<Int>> = mapOf(
        "Flat" to listOf(0, 0, 0, 0, 0),
        "Bass" to listOf(900, 600, 100, 0, 200),
        "Pop" to listOf(-200, 400, 700, 400, -100),
        "Rock" to listOf(700, 400, -300, 500, 800),
        "Vocal" to listOf(-400, -100, 700, 800, 200),
        "EDM" to listOf(800, 500, -200, 400, 700)
    )

    fun loadSaved(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("enabled", true)
        val bass = prefs.getInt("bass", 0)
        val loud = prefs.getInt("loud", 0)
        val preset = prefs.getString("preset", "Flat") ?: "Flat"
        val count = prefs.getInt("bandCount", 5)
        val levels = (0 until count).map { idx -> prefs.getInt("band_$idx", 0) }
        _state.update {
            it.copy(
                eqEnabled = enabled,
                bassStrength = bass,
                loudnessGainMb = loud,
                selectedPreset = preset,
                bandLevelsMb = levels
            )
        }
    }

    private fun save(context: Context) {
        val s = _state.value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean("enabled", s.eqEnabled)
            putInt("bass", s.bassStrength)
            putInt("loud", s.loudnessGainMb)
            putString("preset", s.selectedPreset)
            putInt("bandCount", s.bandLevelsMb.size)
            s.bandLevelsMb.forEachIndexed { idx, v -> putInt("band_$idx", v) }
            apply()
        }
    }

    fun attachSession(sessionId: Int) {
        if (sessionId < 0) return
        val existing = sessions[sessionId]
        if (existing != null) {
            existing.apply(_state.value)
            return
        }
        val chain = EffectChain(sessionId)
        if (chain.equalizer != null || chain.bassBoost != null || chain.loudness != null) {
            if (!metadataLoaded && chain.equalizer != null) {
                runCatching {
                    val eq = chain.equalizer
                    val count = eq.numberOfBands.toInt()
                    val range = eq.bandLevelRange
                    val freqs = (0 until count).map { eq.getCenterFreq(it.toShort()) / 1000 }
                    val current = _state.value.bandLevelsMb
                    val resized = (0 until count).map { current.getOrElse(it) { 0 } }
                    _state.update {
                        it.copy(
                            bandCount = count,
                            bandFreqsHz = freqs,
                            minLevelMb = range[0].toInt(),
                            maxLevelMb = range[1].toInt(),
                            bandLevelsMb = resized
                        )
                    }
                    metadataLoaded = true
                }
            }
            sessions[sessionId] = chain
            chain.apply(_state.value)
            _state.update { it.copy(activeSessionsCount = sessions.size) }
        }
    }

    fun detachSession(sessionId: Int) {
        sessions.remove(sessionId)?.release()
        _state.update { it.copy(activeSessionsCount = sessions.size) }
    }

    fun releaseAll() {
        sessions.values.forEach { it.release() }
        sessions.clear()
        _state.update { it.copy(activeSessionsCount = 0, serviceRunning = false) }
    }

    fun setServiceRunning(running: Boolean) {
        _state.update { it.copy(serviceRunning = running) }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        _state.update { it.copy(eqEnabled = enabled) }
        applyAll()
        save(context)
    }

    fun setBandLevel(context: Context, index: Int, levelMb: Int) {
        _state.update { s ->
            val updated = s.bandLevelsMb.toMutableList()
            if (index in updated.indices) updated[index] = levelMb
            s.copy(bandLevelsMb = updated, selectedPreset = "Custom")
        }
        applyAll()
        save(context)
    }

    fun applyPreset(context: Context, name: String) {
        val values = presets[name] ?: return
        _state.update { s ->
            val resized = (0 until s.bandCount).map { idx ->
                val raw = values.getOrElse(idx) { 0 }
                raw.coerceIn(s.minLevelMb, s.maxLevelMb)
            }
            s.copy(bandLevelsMb = resized, selectedPreset = name)
        }
        applyAll()
        save(context)
    }

    fun setBass(context: Context, strength: Int) {
        _state.update { it.copy(bassStrength = strength) }
        applyAll()
        save(context)
    }

    fun setLoudness(context: Context, gainMb: Int) {
        _state.update { it.copy(loudnessGainMb = gainMb) }
        applyAll()
        save(context)
    }

    private fun applyAll() {
        val s = _state.value
        sessions.values.forEach { it.apply(s) }
    }
}

class EqualizerService : Service() {

    companion object {
        private const val CHANNEL_ID = "waveeq_channel"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.example.waveeq.STOP"
    }

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (sessionId <= 0) return

            when (action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    EqAudioManager.attachSession(sessionId)
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    EqAudioManager.detachSession(sessionId)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        EqAudioManager.loadSaved(this)
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        ContextCompat.registerReceiver(this, sessionReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        // Gắn vào session 0 (toàn hệ thống trên các máy hỗ trợ)
        EqAudioManager.attachSession(0)
        EqAudioManager.setServiceRunning(true)

        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(sessionReceiver) }
        EqAudioManager.releaseAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WaveEQ Equalizer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Giữ Equalizer hoạt động ngầm"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, EqualizerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WaveEQ đang hoạt động")
            .setContentText("Đang xử lý âm thanh Equalizer & Bass Boost")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tắt", stopIntent)
            .setOngoing(true)
            .build()
    }
}
