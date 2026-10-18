package com.example.voicechanger

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var pitchLabel: TextView
    private lateinit var formantLabel: TextView
    private lateinit var pitchSlider: SeekBar
    private lateinit var formantSlider: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }

        val title = TextView(this).apply {
            text = "Natural Voice Changer"
            textSize = 26f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title)

        statusText = TextView(this).apply {
            text = "Status: Stopped (Ready)"
            textSize = 15f
            setTextColor(Color.parseColor("#4CAF50"))
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 40)
        }
        root.addView(statusText)

        toggleButton = Button(this).apply {
            text = "START BACKGROUND VOICE ENGINE"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#1E88E5"))
            setTextColor(Color.WHITE)
            setPadding(24, 32, 24, 32)
            setOnClickListener { toggleVoiceService() }
        }
        root.addView(toggleButton)

        // Preset Header
        val presetHeader = TextView(this).apply {
            text = "Natural Voice Presets"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 48, 0, 16)
        }
        root.addView(presetHeader)

        root.addView(createPresetButton("Natural Female (Soft & Warm)", 1.22f, 1350f, 5.0f))
        root.addView(createPresetButton("Natural Deep Male (Resonant)", 0.82f, 750f, 6.0f))
        root.addView(createPresetButton("Warm Studio Broadcaster", 0.94f, 900f, 4.5f))
        root.addView(createPresetButton("Anonymous Masked Voice", 0.72f, 620f, 8.0f))
        root.addView(createPresetButton("Clean Bypass (Original Voice)", 1.00f, 1000f, 0.0f))

        // Fine-tuning Sliders
        pitchLabel = TextView(this).apply {
            text = "Pitch Shift Ratio: 1.00x"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, 40, 0, 8)
        }
        root.addView(pitchLabel)

        pitchSlider = SeekBar(this).apply {
            max = 100
            progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val ratio = 0.60f + (progress / 100f) * 0.80f // 0.60x to 1.40x
                    VoiceChangerService.pitchShift = ratio
                    pitchLabel.text = String.format("Pitch Shift Ratio: %.2fx", ratio)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(pitchSlider)

        formantLabel = TextView(this).apply {
            text = "Vocal Tract Formant Center: 1000 Hz"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, 32, 0, 8)
        }
        root.addView(formantLabel)

        formantSlider = SeekBar(this).apply {
            max = 100
            progress = 40
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val freq = 500f + (progress / 100f) * 1500f // 500 Hz to 2000 Hz
                    VoiceChangerService.formantFreq = freq
                    VoiceChangerService.formantGainDb = 5.5f
                    formantLabel.text = String.format("Vocal Tract Formant Center: %.0f Hz", freq)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(formantSlider)

        val guideBox = TextView(this).apply {
            text = "HOW TO USE WITH GAMES & CHAT APPS:\n\n" +
                   "1. Plug in wired earphones or an AUX loopback splitter so your speaker does not echo into the mic.\n" +
                   "2. Tap START BACKGROUND VOICE ENGINE above.\n" +
                   "3. Minimize this app and open your game or chat app. The foreground notification keeps the engine alive in real time."
            textSize = 14f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, 48, 0, 24)
        }
        root.addView(guideBox)

        scrollView.addView(root)
        setContentView(scrollView)

        requestAudioPermissions()
        updateUIState()
    }

    private fun createPresetButton(label: String, pitch: Float, formantHz: Float, gainDb: Float): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2C2C2C"))
            setOnClickListener {
                VoiceChangerService.pitchShift = pitch
                VoiceChangerService.formantFreq = formantHz
                VoiceChangerService.formantGainDb = gainDb

                pitchSlider.progress = ((pitch - 0.60f) / 0.80f * 100f).toInt().coerceIn(0, 100)
                formantSlider.progress = ((formantHz - 500f) / 1500f * 100f).toInt().coerceIn(0, 100)
                Toast.makeText(this@MainActivity, "Applied: $label", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleVoiceService() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissions()
            return
        }

        val intent = Intent(this, VoiceChangerService::class.java)
        if (!VoiceChangerService.isRunning) {
            startForegroundService(intent)
            VoiceChangerService.isRunning = true
        } else {
            stopService(intent)
            VoiceChangerService.isRunning = false
        }
        updateUIState()
    }

    private fun updateUIState() {
        if (VoiceChangerService.isRunning) {
            statusText.text = "Status: RUNNING IN BACKGROUND"
            statusText.setTextColor(Color.parseColor("#FFCA28"))
            toggleButton.text = "STOP VOICE ENGINE"
            toggleButton.setBackgroundColor(Color.parseColor("#D32F2F"))
        } else {
            statusText.text = "Status: Stopped (Ready)"
            statusText.setTextColor(Color.parseColor("#4CAF50"))
            toggleButton.text = "START BACKGROUND VOICE ENGINE"
            toggleButton.setBackgroundColor(Color.parseColor("#1E88E5"))
        }
    }

    private fun requestAudioPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(perms.toTypedArray(), 101)
    }
}

class VoiceChangerService : Service() {

    companion object {
        @Volatile var isRunning = false
        @Volatile var pitchShift = 1.22f
        @Volatile var formantFreq = 1350f
        @Volatile var formantGainDb = 5.0f
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "voice_changer_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "Real-Time Voice Changer",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Natural Voice Changer Active")
            .setContentText("Processing microphone audio in real time...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }

        isRunning = true
        startAudioEngine()
        return START_STICKY
    }

    private fun startAudioEngine() {
        thread(start = true, name = "NaturalVoiceDSP") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val sampleRate = 44100
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            } catch (e: SecurityException) {
                isRunning = false
                return@thread
            }

            // Enable hardware echo cancellation and noise suppression if supported
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(recorder.audioSessionId)?.enabled = true
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(recorder.audioSessionId)?.enabled = true
            }

            val player = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            // Circular buffer for smooth Dual-Delay Hanning-Window Pitch Shifting
            val ringSize = 4096
            val ringMask = ringSize - 1
            val ringBuffer = FloatArray(ringSize)
            var writeIdx = 0
            var phase = 0.0f
            val grainSize = 1024f

            // Biquad Formant Filter state variables
            var x1 = 0f
            var x2 = 0f
            var y1 = 0f
            var y2 = 0f

            val inBuf = ShortArray(512)
            val outBuf = ShortArray(512)

            recorder.startRecording()
            player.play()

            while (isRunning) {
                val read = recorder.read(inBuf, 0, inBuf.size)
                if (read > 0) {
                    val currentPitch = pitchShift
                    val f0 = formantFreq
                    val dBgain = formantGainDb

                    // Compute peaking EQ biquad coefficients for vocal tract resonance
                    val a = 10.0.pow(dBgain / 40.0).toFloat()
                    val omega = (2.0 * PI * f0 / sampleRate).toFloat()
                    val sn = sin(omega)
                    val cs = cos(omega)
                    val alpha = sn / (2.0f * 2.2f) // Q = 2.2 for vocal formant peak

                    val b0 = 1f + alpha * a
                    val b1 = -2f * cs
                    val b2 = 1f - alpha * a
                    val a0 = 1f + alpha / a
                    val a1 = -2f * cs
                    val a2 = 1f - alpha / a

                    for (i in 0 until read) {
                        val sample = inBuf[i] / 32768.0f
                        ringBuffer[writeIdx] = sample

                        // Dual-head crossfading delay line to eliminate pitch clicks
                        val delayA = phase * grainSize
                        val phaseB = (phase + 0.5f) % 1.0f
                        val delayB = phaseB * grainSize

                        val readA = (writeIdx - delayA + ringSize) % ringSize
                        val readB = (writeIdx - delayB + ringSize) % ringSize

                        val idxA0 = readA.toInt() and ringMask
                        val idxA1 = (idxA0 + 1) and ringMask
                        val fracA = readA - readA.toInt()
                        val sampA = ringBuffer[idxA0] * (1f - fracA) + ringBuffer[idxA1] * fracA

                        val idxB0 = readB.toInt() and ringMask
                        val idxB1 = (idxB0 + 1) and ringMask
                        val fracB = readB - readB.toInt()
                        val sampB = ringBuffer[idxB0] * (1f - fracB) + ringBuffer[idxB1] * fracB

                        val winA = 0.5f * (1f - cos(2.0 * PI * phase)).toFloat()
                        val winB = 0.5f * (1f - cos(2.0 * PI * phaseB)).toFloat()

                        val pitched = sampA * winA + sampB * winB

                        // Advance grain phase based on pitch ratio
                        val rateStep = (1.0f - currentPitch) / grainSize
                        phase = (phase + rateStep) % 1.0f
                        if (phase < 0f) phase += 1.0f

                        // Apply Vocal Tract Formant Filter
                        val filtered = (b0 / a0) * pitched + (b1 / a0) * x1 + (b2 / a0) * x2 -
                                       (a1 / a0) * y1 - (a2 / a0) * y2
                        x2 = x1
                        x1 = pitched
                        y2 = y1
                        y1 = filtered

                        // Soft analog warmth limiter to prevent clipping
                        val warmOut = tanh(filtered * 1.25f)
                        outBuf[i] = (warmOut * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()

                        writeIdx = (writeIdx + 1) and ringMask
                    }

                    player.write(outBuf, 0, read)
                }
            }

            recorder.stop()
            recorder.release()
            player.stop()
            player.release()
        }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }
}
