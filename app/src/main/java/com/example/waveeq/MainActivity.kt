package com.example.waveeq

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EqAudioManager.loadSaved(this)

        setContent {
            val colors = darkColorScheme(
                primary = Color(0xFF00E5FF),
                secondary = Color(0xFF7C4DFF),
                background = Color(0xFF0B0E14),
                surface = Color(0xFF151A24),
                onBackground = Color(0xFFEEF2F7),
                onSurface = Color(0xFFEEF2F7)
            )
            MaterialTheme(colorScheme = colors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WaveEqScreen()
                }
            }
        }
    }
}

@Composable
fun WaveEqScreen() {
    val context = LocalContext.current
    val state by EqAudioManager.state.collectAsState()
    var isTestingAudio by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startEqService(context)
    }

    DisposableEffect(Unit) {
        if (!state.serviceRunning) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startEqService(context)
            }
        }
        onDispose {
            TestTonePlayer.stop()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("WaveEQ APK", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (state.serviceRunning)
                            "Đang chạy nền • ${state.activeSessionsCount} luồng âm thanh"
                        else "Dịch vụ đang tắt",
                        fontSize = 12.sp,
                        color = Color(0xFF8B95A7)
                    )
                }
                Switch(
                    checked = state.eqEnabled,
                    onCheckedChange = { enabled ->
                        if (!state.serviceRunning) startEqService(context)
                        EqAudioManager.setEnabled(context, enabled)
                    }
                )
            }
        }

        // Service & Test Sound Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    if (state.serviceRunning) {
                        context.stopService(Intent(context, EqualizerService::class.java))
                    } else {
                        startEqService(context)
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (state.serviceRunning) "Tắt Service nền" else "Bật Service nền")
            }

            OutlinedButton(
                onClick = {
                    if (isTestingAudio) {
                        TestTonePlayer.stop()
                        isTestingAudio = false
                    } else {
                        if (!state.serviceRunning) startEqService(context)
                        TestTonePlayer.start { sessionId ->
                            EqAudioManager.attachSession(sessionId)
                        }
                        isTestingAudio = true
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (isTestingAudio) "Dừng nhạc thử" else "Phát nhạc thử")
            }
        }

        // Presets
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Chế độ (Presets)", fontSize = 13.sp, color = Color(0xFF8B95A7))
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EqAudioManager.presets.keys.forEach { name ->
                        val selected = state.selectedPreset == name
                        Surface(
                            onClick = { EqAudioManager.applyPreset(context, name) },
                            shape = RoundedCornerShape(50),
                            color = if (selected) Color(0x2200E5FF) else Color(0xFF1C2331),
                            border = BorderStroke(
                                1.dp,
                                if (selected) Color(0xFF00E5FF) else Color.Transparent
                            )
                        ) {
                            Text(
                                text = name,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) Color(0xFF00E5FF) else Color(0xFF8B95A7)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Equalizer Bands
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    for (i in 0 until state.bandCount) {
                        val levelMb = state.bandLevelsMb.getOrElse(i) { 0 }
                        val freqHz = state.bandFreqsHz.getOrElse(i) { 1000 }
                        EqBandSlider(
                            freqLabel = formatFreq(freqHz),
                            levelMb = levelMb,
                            minMb = state.minLevelMb,
                            maxMb = state.maxLevelMb,
                            onChange = { newMb ->
                                EqAudioManager.setBandLevel(context, i, newMb)
                            }
                        )
                    }
                }
            }
        }

        // Bass Boost & Loudness
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Bass Boost", fontWeight = FontWeight.SemiBold)
                    Text("${state.bassStrength / 10}%", color = Color(0xFF00E5FF))
                }
                Slider(
                    value = state.bassStrength.toFloat(),
                    onValueChange = { EqAudioManager.setBass(context, it.toInt()) },
                    valueRange = 0f..1000f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Tăng âm lượng (Loudness)", fontWeight = FontWeight.SemiBold)
                    Text("+${state.loudnessGainMb / 100} dB", color = Color(0xFF00E5FF))
                }
                Slider(
                    value = state.loudnessGainMb.toFloat(),
                    onValueChange = { EqAudioManager.setLoudness(context, it.toInt()) },
                    valueRange = 0f..1500f
                )
            }
        }

        Text(
            text = "Mẹo: Chạm đúp vào thanh tần số để reset về 0 dB. Mở Spotify/YouTube sau khi bật Service nền để app tự động bắt luồng âm thanh.",
            fontSize = 12.sp,
            color = Color(0xFF8B95A7),
            lineHeight = 17.sp
        )
    }
}

@Composable
fun EqBandSlider(
    freqLabel: String,
    levelMb: Int,
    minMb: Int,
    maxMb: Int,
    onChange: (Int) -> Unit
) {
    val db = levelMb / 100
    val currentOnChange by rememberUpdatedState(onChange)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = if (db > 0) "+$db" else "$db",
            fontSize = 11.sp,
            color = Color(0xFF00E5FF),
            fontWeight = FontWeight.Bold
        )

        Box(
            modifier = Modifier
                .width(44.dp)
                .height(190.dp)
                .pointerInput(minMb, maxMb) {
                    detectTapGestures(
                        onDoubleTap = { currentOnChange(0) },
                        onTap = { offset ->
                            val ratio = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                            val value = (minMb + ratio * (maxMb - minMb)).toInt()
                            currentOnChange(value)
                        }
                    )
                }
                .pointerInput(minMb, maxMb) {
                    detectVerticalDragGestures { change, _ ->
                        change.consume()
                        val ratio = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        val value = (minMb + ratio * (maxMb - minMb)).toInt()
                        currentOnChange(value)
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackW = 6.dp.toPx()
                val centerX = size.width / 2f
                val ratio = ((levelMb - minMb).toFloat() / (maxMb - minMb).coerceAtLeast(1)).coerceIn(0f, 1f)
                val thumbY = size.height * (1f - ratio)
                val zeroY = size.height * 0.5f

                // Track nền
                drawRoundRect(
                    color = Color(0xFF252E40),
                    topLeft = Offset(centerX - trackW / 2f, 0f),
                    size = Size(trackW, size.height),
                    cornerRadius = CornerRadius(trackW, trackW)
                )

                // Vạch 0 dB
                drawLine(
                    color = Color(0x44FFFFFF),
                    start = Offset(centerX - 10.dp.toPx(), zeroY),
                    end = Offset(centerX + 10.dp.toPx(), zeroY),
                    strokeWidth = 1.dp.toPx()
                )

                // Thanh màu từ 0 dB tới vị trí kéo
                val fillTop = minOf(thumbY, zeroY)
                val fillHeight = kotlin.math.abs(thumbY - zeroY)
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(Color(0xFF00E5FF), Color(0xFF7C4DFF))),
                    topLeft = Offset(centerX - trackW / 2f, fillTop),
                    size = Size(trackW, fillHeight),
                    cornerRadius = CornerRadius(trackW, trackW)
                )

                // Nút kéo
                drawCircle(
                    color = Color.White,
                    radius = 9.dp.toPx(),
                    center = Offset(centerX, thumbY)
                )
                drawCircle(
                    color = Color(0xFF00E5FF),
                    radius = 5.dp.toPx(),
                    center = Offset(centerX, thumbY)
                )
            }
        }

        Text(
            text = freqLabel,
            fontSize = 11.sp,
            color = Color(0xFF8B95A7)
        )
    }
}

private fun formatFreq(hz: Int): String =
    if (hz >= 1000) "${hz / 1000}k" else "$hz"

private fun startEqService(context: Context) {
    val intent = Intent(context, EqualizerService::class.java)
    ContextCompat.startForegroundService(context, intent)
}

object TestTonePlayer {
    @Volatile
    private var playing = false
    private var audioTrack: AudioTrack? = null

    fun start(onSessionReady: (Int) -> Unit) {
        if (playing) return
        playing = true
        thread(start = true) {
            val sampleRate = 44100
            val bufferSamples = sampleRate / 2
            val pcm = ShortArray(bufferSamples)

            // Tạo âm mẫu gồm Bass (65Hz) + Mid (440Hz) + Treble (3500Hz) để dễ nghe EQ
            for (i in 0 until bufferSamples) {
                val t = i.toDouble() / sampleRate
                val sample = 0.45 * sin(2 * PI * 65.0 * t) +
                    0.25 * sin(2 * PI * 440.0 * t) +
                    0.12 * sin(2 * PI * 3500.0 * t)
                pcm[i] = (sample * Short.MAX_VALUE * 0.6).toInt().toShort()
            }

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack = track
            onSessionReady(track.audioSessionId)
            track.play()

            while (playing) {
                track.write(pcm, 0, pcm.size)
            }

            runCatching {
                track.stop()
                track.release()
            }
        }
    }

    fun stop() {
        playing = false
    }
}
