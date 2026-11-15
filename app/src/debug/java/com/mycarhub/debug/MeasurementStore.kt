package com.mycarhub.debug

import android.content.Context
import com.mycarhub.R
import com.mycarhub.wakeword.core.AudioSpec
import com.mycarhub.wakeword.core.WavIO
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 개발 빌드 전용: 실제 목소리 측정 세션 저장소 (src/debug 에만 있어 배포 빌드에는 포함되지 않음).
 *
 * 저장 위치는 앱 내부 저장소 files/measurements 이며, 외부로 전송하지 않는다.
 * PC 로는 tools/pull_measurements.ps1 (adb)로만 복사해 MFCC+DTW·임베딩 방식 비교에 사용한다.
 */
class MeasurementStore(context: Context) {

    enum class Condition(val id: String, val labelRes: Int, val expectWakeWord: Boolean) {
        NORMAL("normal", R.string.measure_condition_normal, true),
        VARIATION("variation", R.string.measure_condition_variation, true),
        NOISE("noise", R.string.measure_condition_noise, true),
        SIMILAR("similar", R.string.measure_condition_similar, false),
        CONVERSATION("conversation", R.string.measure_condition_conversation, false),
    }

    val root = File(context.filesDir, DIR)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val consentAtMillis: Long get() = prefs.getLong(KEY_CONSENT, 0L)
    val hasConsent: Boolean get() = consentAtMillis > 0L

    fun giveConsent() {
        prefs.edit().putLong(KEY_CONSENT, System.currentTimeMillis()).apply()
    }

    /** 동의 철회: 저장된 측정 음성도 모두 삭제한다. */
    fun withdrawConsent(): Boolean {
        prefs.edit().remove(KEY_CONSENT).apply()
        return deleteAll()
    }

    fun deleteAll(): Boolean = !root.exists() || root.deleteRecursively()

    fun newSessionDir(condition: Condition): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(root, "${stamp}_${condition.id}").also { it.mkdirs() }
    }

    /** 저장된 세션 메타데이터 (오래된 순). */
    fun sessions(): List<JSONObject> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray()).sortedBy { it.name }.mapNotNull { dir ->
            File(dir, META_FILE).takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        }

    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val DIR = "measurements"
        const val AUDIO_FILE = "audio.wav"
        const val META_FILE = "meta.json"
        private const val PREFS = "measurement"
        private const val KEY_CONSENT = "consent_at"
    }
}

/** 길이를 모르는 녹음을 바로 파일에 쓰는 WAV 저장기. 닫을 때 헤더의 길이를 채운다. */
class StreamingWavWriter(file: File) : Closeable {

    private val out = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(ByteArray(WavIO.HEADER_BYTES))
    }
    private var dataBytes = 0
    private var bytes = ByteArray(0)

    val durationMillis: Int get() = AudioSpec.millisForSamples(dataBytes / 2)

    fun write(samples: ShortArray, count: Int) {
        if (bytes.size < count * 2) bytes = ByteArray(count * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, 0, count)
        out.write(bytes, 0, count * 2)
        dataBytes += count * 2
    }

    override fun close() {
        out.seek(0)
        out.write(WavIO.header(dataBytes))
        out.close()
    }
}
