package com.mycarhub.wakeword.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 사용자 목소리로 만든 개인 호출어 모델.
 *
 * @property templates 녹음 회차별 정규화 MFCC+delta 특징열
 * @property threshold 이 사용자에 맞춰 계산한 감지 기준 (DTW 거리, 작을수록 비슷함)
 * @property minMatches 감지 점수에 쓰는 가장 가까운 템플릿 수
 */
class WakeWordModel(
    val name: String,
    val templates: List<Array<FloatArray>>,
    val threshold: Float,
    val minMatches: Int,
    val createdAtMillis: Long,
    val featureVersion: Int = FEATURE_VERSION,
) {
    companion object {
        /** 특징 추출 방식이 바뀌면 올린다. 다른 버전 모델은 재등록이 필요하다. */
        const val FEATURE_VERSION = 2
    }
}

/** 모델 바이너리 저장 형식. */
object WakeWordModelCodec {

    private const val MAGIC = 0x4D434857 // "MCHW"
    private const val FORMAT_VERSION = 1

    fun write(model: WakeWordModel, output: OutputStream) {
        val out = DataOutputStream(output)
        out.writeInt(MAGIC)
        out.writeInt(FORMAT_VERSION)
        out.writeInt(model.featureVersion)
        out.writeUTF(model.name)
        out.writeLong(model.createdAtMillis)
        out.writeFloat(model.threshold)
        out.writeInt(model.minMatches)
        out.writeInt(model.templates.size)
        for (template in model.templates) {
            out.writeInt(template.size)
            out.writeInt(template.firstOrNull()?.size ?: 0)
            for (frame in template) for (v in frame) out.writeFloat(v)
        }
        out.flush()
    }

    fun read(input: InputStream): WakeWordModel {
        val inp = DataInputStream(input)
        if (inp.readInt() != MAGIC) throw IOException("Not a wake word model")
        val format = inp.readInt()
        if (format != FORMAT_VERSION) throw IOException("Unsupported model format $format")
        val featureVersion = inp.readInt()
        val name = inp.readUTF()
        val created = inp.readLong()
        val threshold = inp.readFloat()
        val minMatches = inp.readInt()
        val count = inp.readInt()
        if (count !in 1..MAX_TEMPLATES) throw IOException("Invalid template count $count")
        val templates = List(count) {
            val frames = inp.readInt()
            val dim = inp.readInt()
            if (frames !in 1..MAX_FRAMES || dim !in 1..MAX_DIM) throw IOException("Invalid template shape")
            Array(frames) { FloatArray(dim) { inp.readFloat() } }
        }
        return WakeWordModel(name, templates, threshold, minMatches, created, featureVersion)
    }

    private const val MAX_TEMPLATES = 20
    private const val MAX_FRAMES = 1_000
    private const val MAX_DIM = 128
}

/** 등록된 템플릿과 비교해 점수(작을수록 호출어에 가까움)를 계산한다. */
class WakeWordMatcher(
    val model: WakeWordModel,
    private val pipeline: FeaturePipeline = FeaturePipeline(),
) {
    private val typicalFrames = model.templates.map { it.size }.sorted().let { it[it.size / 2] }
    private val shortestFrames = model.templates.minOf { it.size }

    /** 이미 정규화된 특징열 하나의 점수. */
    fun score(features: Array<FloatArray>): Float {
        val distances = model.templates.map { Dtw.distance(features, it) }.sorted()
        return distances.take(model.minMatches).average().toFloat()
    }

    /**
     * 발화 구간 안에서 호출어 길이 정도의 창을 옮겨 가며 가장 좋은 점수를 찾는다.
     * "붕붕아 음악 틀어줘"처럼 호출어 뒤에 말이 이어져도 호출어 부분을 찾을 수 있다.
     */
    fun bestScore(samples: ShortArray, from: Int = 0, to: Int = samples.size, noise: DoubleArray? = null): Float {
        val raw = pipeline.raw(samples, from, to, noise)
        if (raw.size < shortestFrames / 2) return Dtw.INFINITE
        var best = Dtw.INFINITE
        for (scale in WINDOW_SCALES) {
            val width = (typicalFrames * scale).toInt().coerceIn(1, raw.size)
            var start = 0
            while (true) {
                val end = minOf(start + width, raw.size)
                best = minOf(best, score(pipeline.normalize(raw, start, end)))
                if (end == raw.size) break
                start += WINDOW_STEP_FRAMES
            }
        }
        return best
    }

    private companion object {
        val WINDOW_SCALES = floatArrayOf(0.85f, 1.0f, 1.2f)
        const val WINDOW_STEP_FRAMES = 5 // 50 ms
    }
}
