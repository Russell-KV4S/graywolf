package com.nw5w.graywolf.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.nw5w.graywolf.jni.ModemBridge
import kotlin.concurrent.thread

class AudioPump {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    @Synchronized
    fun start(sampleRate: Int = 22050) {
        if (running) return
        val minBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) {
            Log.w(TAG, "AudioRecord.getMinBufferSize=$minBytes; skipping RX start")
            return
        }
        val bufBytes = minBytes * 4
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes,
            )
        } catch (se: SecurityException) {
            Log.w(TAG, "AudioRecord permission missing; skipping RX start", se)
            return
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord construction failed; skipping RX start: $t")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord state=${rec.state}; releasing and skipping RX start")
            try { rec.release() } catch (_: Throwable) {}
            return
        }
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord.startRecording failed; releasing: $t")
            try { rec.release() } catch (_: Throwable) {}
            return
        }
        Log.i(TAG, "AudioRecord init rate=$sampleRate bufBytes=$bufBytes (min=$minBytes)")
        recorder = rec
        running = true
        val bufShorts = bufBytes / 2
        thread = thread(name = "audio-pump", priority = Thread.MAX_PRIORITY) {
            val scratch = ShortArray(bufShorts)
            while (running) {
                val n = rec.read(scratch, 0, scratch.size)
                if (n > 0) {
                    ModemBridge.modemPushSamples(scratch, n)
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read=$n")
                }
            }
            try { rec.stop() } catch (_: Throwable) {}
            rec.release()
            Log.i(TAG, "audio pump exited")
        }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
        recorder = null
    }

    companion object { private const val TAG = "AudioPump" }
}
