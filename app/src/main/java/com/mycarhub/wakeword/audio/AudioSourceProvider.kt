package com.mycarhub.wakeword.audio

import android.content.Context
import android.util.Log
import com.mycarhub.BuildConfig
import java.io.File

/** 녹음 회차·호출어 감지에 쓸 입력을 만든다. */
interface AudioSourceProvider {
    fun forTake(index: Int): PcmSource

    fun forListening(onSilenced: (Boolean) -> Unit): PcmSource

    /** 실제 마이크 대신 테스트 파일을 쓰는 중인지 (화면에 표시). */
    val usesTestAudio: Boolean
}

/**
 * 기본은 차량 마이크.
 *
 * 개발(debug) 빌드에서만, 앱 내부 저장소의 debug 폴더에 테스트 WAV가 있으면 마이크 대신 사용한다.
 * (`files/debug/enroll/take_N.wav`, `files/debug/listen.wav`) 배포(release) 빌드에서는 항상 마이크를 쓴다.
 */
class DefaultAudioSourceProvider(private val context: Context) : AudioSourceProvider {

    private val debugDir: File? = if (BuildConfig.DEBUG) File(context.filesDir, "debug") else null

    override fun forTake(index: Int): PcmSource {
        val file = debugDir?.let { File(it, "enroll/take_${index + 1}.wav") }
        if (file != null && file.isFile) {
            Log.i(TAG, "Using test audio for take ${index + 1}: $file")
            return WavFilePcmSource(file)
        }
        return MicrophonePcmSource(context)
    }

    override fun forListening(onSilenced: (Boolean) -> Unit): PcmSource {
        val file = debugDir?.let { File(it, "listen.wav") }
        if (file != null && file.isFile) {
            Log.i(TAG, "Using test audio for listening: $file")
            return WavFilePcmSource(file)
        }
        return MicrophonePcmSource(context, onSilenced)
    }

    override val usesTestAudio: Boolean
        get() = debugDir?.let { File(it, "listen.wav").isFile || File(it, "enroll").isDirectory } == true

    private companion object {
        const val TAG = "AudioSourceProvider"
    }
}
