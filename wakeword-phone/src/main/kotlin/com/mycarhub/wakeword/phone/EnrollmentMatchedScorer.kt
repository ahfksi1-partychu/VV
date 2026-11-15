package com.mycarhub.wakeword.phone

import com.mycarhub.wakeword.core.AudioSpec
import com.mycarhub.wakeword.core.Dtw
import com.mycarhub.wakeword.core.FeaturePipeline
import com.mycarhub.wakeword.core.Levels
import com.mycarhub.wakeword.core.WakeWordMatcher
import com.mycarhub.wakeword.core.WakeWordModel
import kotlin.math.max
import kotlin.math.min

/**
 * 실시간 발화 구간을 '등록 때와 같은 방식'으로 잘라 채점한다 (비교·검증용 대안 채점기).
 *
 * 등록 템플릿은 녹음에서 발화 경계(소음 + 10 dB, 250 ms 이하 끊김은 합침)를 찾고 앞뒤 50 ms만 붙여
 * 그 구간 전체로 정규화(CMVN)한 특징이다. 기존 실시간 채점([WakeWordMatcher.bestScore])은 고정 길이 창을
 * 50 ms씩 옮기므로 창 경계가 실제 발화 경계와 어긋나고, 말 빠르기가 창 길이(0.85·1.0·1.2배)와 다르면
 * 무음이 섞이거나 말이 잘린 채로 정규화된다.
 *
 * 여기서는:
 * 1. 구간 안에서 등록과 같은 기준으로 발화 덩어리를 찾는다.
 * 2. 덩어리가 호출어 길이 범위면 등록과 같이 경계 ±50 ms로 자르고, 경계 오차에 강하도록 시작·끝을 조금씩 옮겨 본다.
 * 3. 호출어 뒤에 말이 이어진 긴 덩어리는 기존처럼 창을 옮겨 찾는다.
 * 말 빠르기 차이는 DTW가 흡수한다 (길이 2배 이내).
 */
class EnrollmentMatchedScorer(
    model: WakeWordModel,
    private val pipeline: FeaturePipeline = FeaturePipeline(),
) {
    private val matcher = WakeWordMatcher(model, pipeline)
    private val typicalSamples = model.templates.map { it.size }.sorted()[model.templates.size / 2] * AudioSpec.FRAME_SHIFT
    private val maxWordSamples = (typicalSamples * MAX_LENGTH_RATIO).toInt()

    /**
     * @param noiseDb 감지기가 추정한 배경 소음 레벨
     * @param noise 발화 직전 소음의 mel 에너지 (스펙트럼 차감용)
     */
    fun score(samples: ShortArray, noiseDb: Float, noise: DoubleArray?): Float {
        val levels = Levels.frameLevels(samples)
        val threshold = max(noiseDb + VAD_MARGIN_DB, MIN_SPEECH_DB)
        var best = Dtw.INFINITE
        for (run in speechRuns(levels, threshold)) {
            val start = run.first * AudioSpec.LEVEL_FRAME
            val end = (run.last + 1) * AudioSpec.LEVEL_FRAME
            // 호출어 길이를 넘는 덩어리(호출어 + 문장)는 호출어 위치를 모르므로 기존처럼 창을 옮겨 찾는다
            val from = max(0, start - PAD)
            val to = min(samples.size, end + PAD)
            best = min(best, if (end - start <= maxWordSamples) scoreWord(samples, start, end, noise) else matcher.bestScore(samples, from, to, noise))
        }
        return best
    }

    private fun scoreWord(samples: ShortArray, start: Int, end: Int, noise: DoubleArray?): Float {
        var best = Dtw.INFINITE
        for (ds in JITTER) for (de in JITTER) {
            val from = (start - PAD + ds).coerceIn(0, samples.size)
            val to = (end + PAD + de).coerceIn(0, samples.size)
            if (to - from < AudioSpec.FRAME_LENGTH * 2) continue
            best = min(best, matcher.score(pipeline.compute(samples, from, to, noise)))
        }
        return best
    }

    /** 등록 품질 검사([com.mycarhub.wakeword.core.QualityAnalyzer])와 같은 발화 덩어리 찾기. 프레임 인덱스 (끝 포함). */
    private fun speechRuns(levels: FloatArray, threshold: Float): List<IntRange> {
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
            if (last != null && run.first - last.last - 1 <= MERGE_GAP_FRAMES) merged[merged.size - 1] = last.first..run.last else merged += run
        }
        return merged.filter { it.last - it.first + 1 >= MIN_RUN_FRAMES }
    }

    private companion object {
        const val VAD_MARGIN_DB = 10f
        const val MIN_SPEECH_DB = -55f
        const val MERGE_GAP_FRAMES = 25
        const val MIN_RUN_FRAMES = 5
        val PAD = AudioSpec.samplesForMillis(50)
        /** 경계 오차 허용: 시작·끝을 -30/0/+30 ms 옮겨 본다 */
        val JITTER = intArrayOf(-AudioSpec.samplesForMillis(30), 0, AudioSpec.samplesForMillis(30))
        /** 등록 일관성 검사의 길이 허용 범위(최대 1.67배)와 같다 */
        const val MAX_LENGTH_RATIO = 1.67f
    }
}
