package com.example.intercept

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class RecordingService : Service() {

    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var micRecord: AudioRecord? = null
    private var sysRecord: AudioRecord? = null

    companion object {
        private const val TAG = "RecordingService"
        var isRecording = false
            private set

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 101

        // 16000 Hz is the native rate of cellular voice calls.
        // Using 44100 Hz causes Android to reject or silence third-party mic access during calls.
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private var lastResultCode: Int = 0
        private var lastResultData: Intent? = null

        fun setLastResult(code: Int, data: Intent?) {
            lastResultCode = code
            lastResultData = data
        }

        fun hasResultData() = lastResultCode != 0 && lastResultData != null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        startForegroundSafe()

        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, lastResultCode)
                val resultData: Intent? = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) ?: lastResultData
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_RESULT_DATA) ?: lastResultData
                    }
                } catch (e: Exception) {
                    lastResultData
                }

                if (resultCode != 0 && resultData != null) {
                    setLastResult(resultCode, resultData)
                    if (!isRecording) startRecording(resultCode, resultData)
                } else {
                    Log.e(TAG, "No valid result data to start recording")
                    stopSelf()
                }
            }
            ACTION_STOP -> stopRecording()
            else -> { if (!isRecording) stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafe() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Recording Service", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Intercept")
            .setContentText("Recording audio...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical: Failed to start foreground service", e)
        }
    }

    private fun startRecording(resultCode: Int, resultData: Intent) {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection is null, cannot start recording")
                stopSelf()
                return
            }

            mediaProjection?.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() { stopRecording() }
            }, null)

            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 4,
                8192
            )

            // --- Telephony downlink (the other person's voice) ---
            // Tries VOICE_CALL → VOICE_DOWNLINK → AudioPlaybackCapture fallback.
            // VOICE_CALL / VOICE_DOWNLINK work on many OEM ROMs (Samsung, Xiaomi, Oppo, Realme)
            // without needing CAPTURE_AUDIO_OUTPUT. Stock AOSP will block them and fall back.
            sysRecord = tryBuildDownlinkRecord(bufferSize)

            // --- Microphone capture (your voice + earpiece acoustic bleed) ---
            // VOICE_COMMUNICATION: echo-cancelled VoIP source, first attempt.
            // VOICE_RECOGNITION: unprocessed, often survives call routing restrictions.
            // DEFAULT: plain mic, last resort.
            micRecord = tryBuildMicRecord(bufferSize)

            if (micRecord == null && sysRecord == null) {
                Log.e(TAG, "All audio sources failed, cannot record")
                stopSelf()
                return
            }

            sysRecord?.startRecording()
            micRecord?.startRecording()
            isRecording = true

            sendBroadcast(Intent("com.example.intercept.RECORDING_STATUS").putExtra("isRecording", true))
            startMixingThread(bufferSize)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            stopSelf()
        }
    }

    /**
     * Tries to record the other person's voice (telephony downlink) using privileged audio sources.
     *
     * Source priority:
     * 1. VOICE_CALL (4): captures both uplink + downlink in one stream. Works on many OEM ROMs.
     * 2. VOICE_DOWNLINK (3): incoming voice only. Also OEM-dependent.
     * 3. AudioPlaybackCapture: captures app media audio — will NOT get call audio on stock Android,
     *    but kept as a last resort in case some future OS/device allows it.
     *
     * Note: VOICE_CALL and VOICE_DOWNLINK technically require android.permission.CAPTURE_AUDIO_OUTPUT
     * (a signature/system permission) on AOSP. However, many manufacturer ROMs (Samsung, Xiaomi, Realme,
     * Oppo, etc.) relax this restriction and allow third-party apps to use these sources.
     */
    private fun tryBuildDownlinkRecord(bufferSize: Int): AudioRecord? {
        // Try telephony sources first
        val telephonySources = listOf(
            4 to "VOICE_CALL",       // MediaRecorder.AudioSource.VOICE_CALL
            3 to "VOICE_DOWNLINK"    // MediaRecorder.AudioSource.VOICE_DOWNLINK
        )
        for ((source, name) in telephonySources) {
            try {
                val record = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "Downlink record initialized with source: $name — other party voice will be captured!")
                    return record
                } else {
                    Log.w(TAG, "Downlink source $name not available on this device/ROM")
                    record.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Downlink source $name blocked (${e.message}) — this device requires root or OEM unlock")
            }
        }

        // Last resort: AudioPlaybackCapture (won't capture call audio on stock Android)
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(bufferSize)
                .build()
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "Fallback: using AudioPlaybackCapture (call audio not guaranteed)")
                record
            } else {
                record.release(); null
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioPlaybackCapture also failed: ${e.message}")
            null
        }
    }

    /**
     * Tries to create a mic AudioRecord using a priority-ordered list of audio sources.
     *
     * Priority rationale for call recording:
     * - VOICE_COMMUNICATION: Designed for VoIP, has echo-cancel, but Android may mute it during cellular calls.
     * - VOICE_RECOGNITION: Unprocessed mic input, bypasses call audio routing restrictions on many devices.
     * - DEFAULT: Plain microphone, last resort.
     */
    private fun tryBuildMicRecord(bufferSize: Int): AudioRecord? {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION    to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.DEFAULT              to "DEFAULT (MIC)"
        )

        for ((source, name) in sources) {
            try {
                val record = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "Mic record initialized with source: $name")
                    return record
                } else {
                    Log.w(TAG, "Mic source $name failed to initialize, trying next")
                    record.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mic source $name threw exception: ${e.message}, trying next")
            }
        }
        return null
    }

    private fun startMixingThread(bufferSize: Int) {
        thread(name = "RecordingThread", priority = Thread.MAX_PRIORITY) {
            val folder = File(getExternalFilesDir(null), "Intercept")
            if (!folder.exists()) folder.mkdirs()

            val outFile = File(folder, "intercept_${System.currentTimeMillis()}.wav")
            var outputStream: FileOutputStream? = null
            var totalBytesWritten = 0L

            // ShortArray size: bufferSize is in bytes, so bufferSize/2 shorts maps to that many bytes.
            val readSize = bufferSize / 2
            val micBuffer = ShortArray(readSize)
            val sysBuffer = ShortArray(readSize)

            try {
                outputStream = FileOutputStream(outFile)
                writeWavHeader(outputStream, 0)

                while (isRecording) {
                    // --- Read microphone ---
                    val micRead = micRecord?.read(micBuffer, 0, readSize) ?: 0
                    if (micRead < 0) {
                        // Negative value is an error code, not data. Log and continue — do NOT break.
                        Log.w(TAG, "Mic read error: $micRead (may be normal during call routing changes)")
                    }

                    // --- Read system audio (best-effort; null if not available) ---
                    val sysRead = if (sysRecord != null) {
                        val r = sysRecord!!.read(sysBuffer, 0, readSize)
                        if (r < 0) {
                            // Expected during calls — Android blocks VOICE_COMMUNICATION capture.
                            0
                        } else r
                    } else 0

                    val validMic = if (micRead > 0) micRead else 0
                    val validSys = if (sysRead > 0) sysRead else 0
                    val maxRead = maxOf(validMic, validSys)

                    if (maxRead == 0) {
                        // No data yet; back off briefly to avoid spinning
                        Thread.sleep(5)
                        continue
                    }

                    val mixedBuffer = ShortArray(maxRead)
                    for (i in 0 until maxRead) {
                        val micSample = if (i < validMic) micBuffer[i] else 0
                        val sysSample = if (i < validSys) sysBuffer[i] else 0

                        // Boost mic massively (4.5x) during calls: Android severely drops mic sensitivity, 
                        // and we need to pick up the faint remote voice vibrating from the earpiece.
                        val boostedMic = (micSample.toFloat() * 4.5f)
                            .toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()

                        mixedBuffer[i] = AudioMixer.mixPCM(boostedMic, sysSample)
                    }

                    val byteBuffer = ByteBuffer.allocate(mixedBuffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    byteBuffer.asShortBuffer().put(mixedBuffer)
                    val bytes = byteBuffer.array()
                    outputStream.write(bytes)
                    totalBytesWritten += bytes.size
                }

            } catch (e: Exception) {
                Log.e(TAG, "Mixing thread error", e)
            } finally {
                // Always flush and fix the WAV header — even if we exited via exception or break.
                try {
                    outputStream?.flush()
                    outputStream?.close()
                } catch (e: Exception) { /* ignore */ }

                try {
                    updateWavHeader(outFile, totalBytesWritten)
                    Log.d(TAG, "Recording saved: ${outFile.absolutePath} (${totalBytesWritten} bytes)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update WAV header", e)
                }
                cleanup()
            }
        }
    }

    private fun writeWavHeader(out: FileOutputStream, dataSize: Long) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val totalDataLen = dataSize + 36
        val header = ByteArray(44)

        // RIFF chunk
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()

        // fmt sub-chunk
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0  // subchunk1 size
        header[20] = 1; header[21] = 0                                     // PCM format
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (SAMPLE_RATE and 0xff).toByte()
        header[25] = ((SAMPLE_RATE shr 8) and 0xff).toByte()
        header[26] = ((SAMPLE_RATE shr 16) and 0xff).toByte()
        header[27] = ((SAMPLE_RATE shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0

        // data sub-chunk
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (dataSize and 0xff).toByte()
        header[41] = ((dataSize shr 8) and 0xff).toByte()
        header[42] = ((dataSize shr 16) and 0xff).toByte()
        header[43] = ((dataSize shr 24) and 0xff).toByte()

        out.write(header, 0, 44)
    }

    private fun updateWavHeader(file: File, dataSize: Long) {
        if (!file.exists() || dataSize == 0L) return
        try {
            val raf = RandomAccessFile(file, "rw")
            val totalDataLen = dataSize + 36
            raf.seek(4);  raf.write(intToByteArray(totalDataLen.toInt()))
            raf.seek(40); raf.write(intToByteArray(dataSize.toInt()))
            raf.close()
        } catch (e: Exception) {
            Log.e(TAG, "updateWavHeader failed: ${e.message}")
        }
    }

    private fun intToByteArray(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte()
    )

    private fun cleanup() {
        isRecording = false
        try {
            // Use recordingState (RECORDSTATE_RECORDING) not state (STATE_INITIALIZED) —
            // these are different constants and the old code was checking the wrong one.
            sysRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
            }
            micRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}")
        }
        sysRecord = null
        micRecord = null
        mediaProjection?.stop()
        mediaProjection = null
        sendBroadcast(Intent("com.example.intercept.RECORDING_STATUS").putExtra("isRecording", false))
    }

    private fun stopRecording() {
        isRecording = false
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
