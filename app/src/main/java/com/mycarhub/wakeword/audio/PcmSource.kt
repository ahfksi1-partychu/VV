package com.mycarhub.wakeword.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.mycarhub.wakeword.core.AudioSpec
import com.mycarhub.wakeword.core.WavIO
import java.io.File
import java.io.IOException

/** 16 kHz mono PCM16 입력. 녹음·감지 로직은 마이크와 테스트용 파일을 구분하지 않는다. */
interface PcmSource {
    fun start(): Boolean

    /** 블로킹 읽기. 입력이 끝났거나 오류면 -1. */
    fun read(buffer: ShortArray): Int

    fun stop()
}

/**
 * 차량 마이크 입력.
 *
 * 음성 인식용 소스(VOICE_RECOGNITION)를 쓰고 privacy-sensitive로 열지 않는다.
 * 비공개 캡처로 열면 시스템 음성비서(Gleo)의 호출어 감지까지 막기 때문이다.
 * 다른 앱(예: Gleo 화면)이 마이크 우선권을 가져가면 무음을 받게 되며, 이를 [onSilenced]로 알린다.
 */
class MicrophonePcmSource(
    private val context: Context,
    private val onSilenced: ((Boolean) -> Unit)? = null,
) : PcmSource {

    /** true면 start() 때 기기가 지원하는 에코 제거(AEC)·잡음 억제를 붙인다 (휴대폰 호출어 감지용). 기본은 끔. */
    var echoCancel: Boolean = false

    private var record: AudioRecord? = null
    private var effects: List<android.media.audiofx.AudioEffect> = emptyList()
    private var callback: AudioManager.AudioRecordingCallback? = null

    @SuppressLint("MissingPermission") // 아래에서 직접 확인
    override fun start(): Boolean {
        if (!hasPermission(context)) return false
        val minBuffer = AudioRecord.getMinBufferSize(AudioSpec.SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            Log.w(TAG, "Unsupported recording format: $minBuffer")
            return false
        }
        val created = try {
            build(maxOf(minBuffer, AudioSpec.SAMPLE_RATE * 2)) // 최소 1초 버퍼
        } catch (e: RuntimeException) {
            Log.w(TAG, "AudioRecord creation failed", e)
            return false
        }
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && onSilenced != null) {
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                    configs.firstOrNull { it.clientAudioSessionId == created.audioSessionId }
                        ?.let { onSilenced.invoke(it.isClientSilenced) }
                }
            }
            created.registerAudioRecordingCallback(context.mainExecutor, cb)
            callback = cb
        }
        try {
            created.startRecording()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startRecording failed", e)
            release(created)
            return false
        }
        if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.w(TAG, "Microphone busy or unavailable")
            release(created)
            return false
        }
        record = created
        if (echoCancel) effects = attachEffects(created.audioSessionId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            created.activeRecordingConfiguration?.let { onSilenced?.invoke(it.isClientSilenced) }
        }
        return true
    }

    override fun read(buffer: ShortArray): Int {
        val rec = record ?: return -1
        val n = rec.read(buffer, 0, buffer.size)
        return if (n < 0) -1 else n
    }

    override fun stop() {
        record?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            release(it)
        }
        record = null
    }

    private fun release(rec: AudioRecord) {
        effects.forEach { runCatching { it.release() } }
        effects = emptyList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) callback?.let { rec.unregisterAudioRecordingCallback(it) }
        callback = null
        rec.release()
    }

    private fun attachEffects(session: Int): List<android.media.audiofx.AudioEffect> {
        val list = mutableListOf<android.media.audiofx.AudioEffect>()
        if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
            runCatching { android.media.audiofx.AcousticEchoCanceler.create(session) }.getOrNull()?.let { it.enabled = true; list += it }
        }
        if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
            runCatching { android.media.audiofx.NoiseSuppressor.create(session) }.getOrNull()?.let { it.enabled = true; list += it }
        }
        Log.i(TAG, "effects aec=${android.media.audiofx.AcousticEchoCanceler.isAvailable()} ns=${android.media.audiofx.NoiseSuppressor.isAvailable()} attached=${list.size}")
        return list
    }

    @SuppressLint("MissingPermission")
    private fun build(bufferBytes: Int): AudioRecord =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(AudioSpec.SAMPLE_RATE)
                        .setChannelMask(CHANNEL)
                        .setEncoding(ENCODING)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setPrivacySensitive(false)
                .build()
        } else {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, AudioSpec.SAMPLE_RATE, CHANNEL, ENCODING, bufferBytes)
        }

    companion object {
        private const val TAG = "MicrophonePcmSource"
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}

/** 개발 빌드 전용 테스트 입력: WAV 파일을 실시간 속도로 재생하듯 읽는다. */
class WavFilePcmSource(private val file: File) : PcmSource {

    private var samples: ShortArray? = null
    private var position = 0
    private var startedAt = 0L

    override fun start(): Boolean {
        samples = try {
            WavIO.read(file).also { require(it.sampleRate == AudioSpec.SAMPLE_RATE) }.samples
        } catch (e: IOException) {
            Log.w("WavFilePcmSource", "Cannot read $file", e)
            return false
        } catch (e: IllegalArgumentException) {
            Log.w("WavFilePcmSource", "$file is not 16 kHz", e)
            return false
        }
        position = 0
        startedAt = SystemClock.elapsedRealtime()
        return true
    }

    override fun read(buffer: ShortArray): Int {
        val data = samples ?: return -1
        if (position >= data.size) return -1
        val n = minOf(buffer.size, data.size - position)
        System.arraycopy(data, position, buffer, 0, n)
        position += n
        val due = startedAt + AudioSpec.millisForSamples(position)
        val wait = due - SystemClock.elapsedRealtime()
        if (wait > 0) SystemClock.sleep(wait)
        return n
    }

    override fun stop() {
        samples = null
    }
}
