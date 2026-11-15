package com.mycarhub.wakeword.data

import android.content.Context
import android.util.Log
import com.mycarhub.wakeword.core.WakeWordModel
import com.mycarhub.wakeword.core.WakeWordModelCodec
import com.mycarhub.wakeword.core.WavIO
import java.io.File
import java.io.IOException

/**
 * 개인 호출어 모델과 등록 녹음 저장소.
 *
 * 앱 전용 내부 저장소(files/wakeword)에만 저장하며 외부로 전송하지 않는다.
 * 클라우드 백업·기기 간 전송 대상에서도 제외한다 (res/xml/backup_rules.xml, data_extraction_rules.xml).
 */
class WakeWordRepository(context: Context) {

    private val root = File(context.filesDir, DIR)
    private val modelFile = File(root, MODEL_FILE)

    fun load(): WakeWordModel? {
        if (!modelFile.isFile) return null
        return try {
            modelFile.inputStream().buffered().use { WakeWordModelCodec.read(it) }
                .takeIf { it.featureVersion == WakeWordModel.FEATURE_VERSION }
                ?: run {
                    Log.w(TAG, "Model feature version changed; re-enrollment required")
                    null
                }
        } catch (e: IOException) {
            Log.w(TAG, "Cannot read wake word model", e)
            null
        }
    }

    /** 모델과 등록 녹음 원본을 함께 저장한다. 다 쓴 뒤 교체하므로 실패해도 이전 등록이 남는다. */
    fun save(model: WakeWordModel, takes: List<ShortArray>) {
        val staging = File(root.parentFile, "$DIR.tmp")
        staging.deleteRecursively()
        val samples = File(staging, SAMPLES_DIR)
        if (!samples.mkdirs()) throw IOException("Cannot create $samples")
        takes.forEachIndexed { i, take -> WavIO.write(File(samples, "take_${i + 1}.wav"), take) }
        File(staging, MODEL_FILE).outputStream().buffered().use { WakeWordModelCodec.write(model, it) }
        root.deleteRecursively()
        if (!staging.renameTo(root)) throw IOException("Cannot move $staging to $root")
    }

    /** 모델과 녹음 원본을 모두 삭제한다. */
    fun delete(): Boolean {
        File(root.parentFile, "$DIR.tmp").deleteRecursively()
        return root.deleteRecursively()
    }

    fun sampleCount(): Int = File(root, SAMPLES_DIR).listFiles()?.size ?: 0

    // region 이전 호출어 보관 (호출어가 '쁘이'로 바뀜)

    private val retiredRoot = File(context.filesDir, RETIRED_DIR)

    /**
     * 등록된 모델의 이름이 지금 호출어([isCurrent])가 아니면 모델과 녹음 원본을 통째로 보관 폴더(files/wakeword-retired/<시각>)로
     * 옮긴다. 지우지 않고 이름만 바꾸는 이동이라 녹음은 그대로 남고, 이후 등록·감지·삭제는 보관 폴더를 건드리지 않는다.
     * 옮겼으면 이전 이름을 돌려준다.
     */
    fun retireIfNot(isCurrent: (String) -> Boolean): String? {
        if (!modelFile.isFile) return null
        val name = readName(modelFile) ?: return null
        if (isCurrent(name)) return null
        if (!retiredRoot.isDirectory && !retiredRoot.mkdirs()) {
            Log.w(TAG, "Cannot create $retiredRoot")
            return null
        }
        val target = File(retiredRoot, System.currentTimeMillis().toString())
        if (!root.renameTo(target)) {
            Log.w(TAG, "Cannot move old wake word to $target")
            return null
        }
        Log.i(TAG, "Old wake word model retired (kept recordings)")
        return name
    }

    /** 지금 등록된 호출어가 없고 보관한 이전 호출어가 있으면 그 이름 (화면 안내용) */
    fun retiredName(): String? {
        if (modelFile.isFile) return null
        val latest = retiredRoot.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name } ?: return null
        return readName(File(latest, MODEL_FILE))
    }

    private fun readName(file: File): String? = try {
        file.inputStream().buffered().use { WakeWordModelCodec.read(it) }.name
    } catch (e: IOException) {
        Log.w(TAG, "Cannot read wake word model name", e)
        null
    }

    // endregion

    private companion object {
        const val TAG = "WakeWordRepository"
        const val RETIRED_DIR = "wakeword-retired"
        const val DIR = "wakeword"
        const val MODEL_FILE = "model.bin"
        const val SAMPLES_DIR = "samples"
    }
}
