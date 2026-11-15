package com.mycarhub.wakeword.core

import kotlin.math.max
import kotlin.math.min

enum class QualityIssue {
    /** 소리가 거의 없음 */
    SILENT,

    /** 목소리가 너무 작음 */
    TOO_QUIET,

    /** 발화가 너무 짧음 */
    TOO_SHORT,

    /** 발화가 너무 김 (호출어 외 다른 말 포함 등) */
    TOO_LONG,

    /** 입력이 너무 커서 찌그러짐 */
    CLIPPED,

    /** 배경 소음 대비 목소리가 충분히 크지 않음 */
    NOISY,

    /** 녹음 시작 또는 끝에서 말이 잘림 */
    CUT_OFF,
}

data class QualityReport(
    val issues: List<QualityIssue>,
    /** 템플릿으로 쓸 구간 (앞뒤 여유 포함, 샘플 단위). 발화를 찾지 못하면 0..0 */
    val speechStart: Int,
    val speechEnd: Int,
    val speechMillis: Int,
    val speechDb: Float,
    val noiseDb: Float,
    val peakDb: Float,
    val clippedRatio: Float,
) {
    val isAcceptable: Boolean get() = issues.isEmpty()
    val snrDb: Float get() = speechDb - noiseDb
}

/**
 * 녹음 1회분의 품질 검사. 에너지 기반으로 발화 구간을 찾고 무음·길이·음량·잡음·클리핑·잘림을 판단한다.
 */
class QualityAnalyzer(private val limits: QualityLimits = QualityLimits()) {

    fun analyze(samples: ShortArray): QualityReport {
        val levels = Levels.frameLevels(samples)
        val peakDb = Levels.peakDb(samples)
        if (levels.isEmpty() || peakDb < limits.silentPeakDb) {
            return report(listOf(QualityIssue.SILENT), peakDb = peakDb, noiseDb = levels.percentile(0.1f))
        }

        val noiseFloor = levels.percentile(NOISE_PERCENTILE)
        val segment = findSpeech(levels, max(noiseFloor + limits.vadMarginDb, limits.minSpeechDb))
        if (segment == null) {
            // 소리는 있지만 배경보다 뚜렷하게 큰 구간이 없음: 큰 소음이면 잡음, 아니면 무음
            val issue = if (noiseFloor > limits.minSpeechDb) QualityIssue.NOISY else QualityIssue.SILENT
            return report(listOf(issue), peakDb = peakDb, noiseDb = noiseFloor)
        }

        val frame = AudioSpec.LEVEL_FRAME
        val rawStart = segment.first * frame
        val rawEnd = segment.last * frame + frame
        val speechDb = Levels.rmsDb(samples, rawStart, rawEnd)
        val noiseDb = noiseOutside(levels, segment) ?: noiseFloor
        val clipped = clippedRatio(samples, rawStart, rawEnd)
        val millis = AudioSpec.millisForSamples(rawEnd - rawStart)

        val issues = buildList {
            if (segment.first <= EDGE_FRAMES || segment.last >= levels.size - 1 - EDGE_FRAMES) add(QualityIssue.CUT_OFF)
            if (millis < limits.minSpeechMillis) add(QualityIssue.TOO_SHORT)
            if (millis > limits.maxSpeechMillis) add(QualityIssue.TOO_LONG)
            if (speechDb < limits.minSpeechLevelDb) add(QualityIssue.TOO_QUIET)
            if (clipped > limits.maxClippedRatio) add(QualityIssue.CLIPPED)
            if (speechDb - noiseDb < limits.minSnrDb) add(QualityIssue.NOISY)
        }
        val pad = AudioSpec.samplesForMillis(PAD_MILLIS)
        return QualityReport(
            issues = issues,
            speechStart = max(0, rawStart - pad),
            speechEnd = min(samples.size, rawEnd + pad),
            speechMillis = millis,
            speechDb = speechDb,
            noiseDb = noiseDb,
            peakDb = peakDb,
            clippedRatio = clipped,
        )
    }

    /** 가장 에너지가 큰 발화 덩어리. 짧은 끊김(파열음, 숨)은 하나로 합친다. 프레임 인덱스 (끝 포함). */
    private fun findSpeech(levels: FloatArray, threshold: Float): IntRange? {
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (i in levels.indices) {
            val loud = levels[i] > threshold
            if (loud && start < 0) start = i
            if (!loud && start >= 0) {
                runs += start until i
                start = -1
            }
        }
        if (start >= 0) runs += start until levels.size

        val merged = mutableListOf<IntRange>()
        for (run in runs) {
            val last = merged.lastOrNull()
            if (last != null && run.first - last.last - 1 <= MERGE_GAP_FRAMES) {
                merged[merged.size - 1] = last.first..run.last
            } else {
                merged += run
            }
        }
        return merged
            .filter { it.last - it.first + 1 >= MIN_RUN_FRAMES }
            .maxByOrNull { range -> range.sumOf { dbToPower(levels[it]) } }
    }

    private fun noiseOutside(levels: FloatArray, segment: IntRange): Float? {
        val guard = 5
        val outside = levels.filterIndexed { i, _ -> i < segment.first - guard || i > segment.last + guard }
        if (outside.size < MIN_NOISE_FRAMES) return null
        // 평균 전력 기준 (dB 평균은 조용한 순간에 치우친다)
        val power = outside.sumOf { dbToPower(it) } / outside.size
        return (10 * kotlin.math.log10(power)).toFloat()
    }

    private fun clippedRatio(samples: ShortArray, from: Int, to: Int): Float {
        var count = 0
        for (i in from until to) if (kotlin.math.abs(samples[i].toInt()) >= CLIP_LEVEL) count++
        return if (to > from) count.toFloat() / (to - from) else 0f
    }

    private fun report(issues: List<QualityIssue>, peakDb: Float, noiseDb: Float) = QualityReport(
        issues = issues, speechStart = 0, speechEnd = 0, speechMillis = 0,
        speechDb = AudioSpec.MIN_DB, noiseDb = noiseDb, peakDb = peakDb, clippedRatio = 0f,
    )

    private companion object {
        const val NOISE_PERCENTILE = 0.1f
        const val MERGE_GAP_FRAMES = 25 // 250 ms
        const val MIN_RUN_FRAMES = 5 // 50 ms 미만은 잡음(클릭)으로 본다
        const val MIN_NOISE_FRAMES = 20
        const val EDGE_FRAMES = 1
        const val PAD_MILLIS = 50
        const val CLIP_LEVEL = 32_000
    }
}

data class QualityLimits(
    val silentPeakDb: Float = -45f,
    val minSpeechDb: Float = -55f,
    val vadMarginDb: Float = 10f,
    val minSpeechMillis: Int = 300,
    val maxSpeechMillis: Int = 2_000,
    val minSpeechLevelDb: Float = -40f,
    val maxClippedRatio: Float = 0.001f,
    val minSnrDb: Float = 12f,
)

internal fun dbToPower(db: Float): Double = Math.pow(10.0, db / 10.0)

internal fun FloatArray.percentile(p: Float): Float {
    if (isEmpty()) return AudioSpec.MIN_DB
    val sorted = sortedArray()
    return sorted[((sorted.size - 1) * p).toInt()]
}
