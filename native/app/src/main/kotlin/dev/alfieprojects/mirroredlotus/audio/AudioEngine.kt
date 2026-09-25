package dev.alfieprojects.mirroredlotus.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import dev.alfieprojects.mirroredlotus.core.audio.SpatialMixer

/**
 * Plays [mixer]'s output on a low-latency float `AudioTrack` from a dedicated
 * urgent-audio thread. Replaces the WebView's Web Audio graph: no per-burst
 * node allocation, no JS-thread scheduling between a burst on screen and its
 * sound.
 */
class AudioEngine(context: Context, private val mixer: SpatialMixer) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        running = true
        thread = Thread({ loop() }, "lotus-audio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = outputSampleRate(audioManager)
        val burst = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME) // media volume: the rocker controls it
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBytes, burst * 4 * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack unavailable; running silent", e)
            return
        }
        val frames = burst.coerceIn(64, 1024)
        val buf = FloatArray(frames * 2)
        mixer.drainStale()
        try {
            track.play()
            while (running) {
                mixer.render(buf, frames)
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio loop stopped", e)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    companion object {
        private const val TAG = "LotusAudio"

        fun outputSampleRate(context: Context): Int =
            outputSampleRate(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)

        private fun outputSampleRate(am: AudioManager): Int =
            am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
    }
}

/**
 * Earphone/Bluetooth auto-detection (`detectEarphones` + `devicechange` in the
 * web build), from the audio routing rather than device-name guessing.
 */
class EarphoneDetector(context: Context, private val onChange: (Boolean) -> Unit) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var last: Boolean? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = check()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = check()
    }

    fun register() {
        audioManager.registerAudioDeviceCallback(callback, handler)
        check()
    }

    fun unregister() = audioManager.unregisterAudioDeviceCallback(callback)

    fun present(): Boolean = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONES }

    private fun check() {
        val now = present()
        if (now != last) {
            last = now
            onChange(now)
        }
    }

    private companion object {
        val HEADPHONES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }
}
