package com.mycarhub.wakeword.phone

import com.mycarhub.wakeword.core.AudioSpec
import com.mycarhub.wakeword.core.TrainerConfig
import com.mycarhub.wakeword.core.WakeWordModel

/**
 * 등록 녹음 5개로 '실시간 감지와 같은 방식'의 감지 기준을 다시 계산한다 (재녹음 없음).
 *
 * 등록 때 기준값은 녹음에서 발화 경계를 정확히 잘라 서로 비교한 점수로 정한다. 실시간 감지는 소리 크기로 구간을 자르고
 * 고정 길이 창을 50 ms씩 옮겨 비교하므로, 같은 목소리라도 점수가 더 높게 나온다. 그래서 등록 기준이 실사용보다 빡빡하다.
 *
 * 여기서는 회차 i를 뺀 나머지 4개 템플릿으로 모델을 만들고, 회차 i 녹음을 실제 감지기([AdaptiveWakeWordDetector])에
 * 스트림으로 흘려 가장 좋은 구간 점수를 구한다 (leave-one-out). 그 최댓값에 등록 때와 같은 여유 배수를 곱한다.
 * 다만 일상 대화 오인식을 막기 위해 [ceiling]을 넘지 않고, 원래 기준보다 낮아지지도 않는다.
 */
object LiveCalibration {

    data class Result(
        /** 회차별 실시간 방식 점수 (작을수록 가까움) */
        val liveScores: List<Float>,
        /** 회차별 등록 방식 채점([EnrollmentMatchedScorer]) 점수. 기준 계산에는 아직 쓰지 않는다 */
        val matchedScores: List<Float>,
        val modelThreshold: Float,
        /** 실제 감지에 쓰는 기준 */
        val threshold: Float,
        /** 상한에 걸려 계산값보다 낮춘 경우 */
        val capped: Boolean,
    )

    /** 일상 대화 오인식이 시작되는 점수대 아래 (PC 평가: 0.26에서 시간당 30회, 0.22에서 0회) */
    const val DEFAULT_CEILING = 0.22f

    fun calibrate(
        model: WakeWordModel,
        takes: List<ShortArray>,
        margin: Float = TrainerConfig().thresholdMargin,
        ceiling: Float = DEFAULT_CEILING,
    ): Result? {
        if (takes.size != model.templates.size || takes.size < 3) return null
        val scores = takes.indices.map { i ->
            val others = WakeWordModel(
                name = model.name,
                templates = model.templates.filterIndexed { j, _ -> j != i },
                threshold = 0f, // 점수만 본다 (감지로 끊기지 않게)
                minMatches = model.minMatches,
                createdAtMillis = model.createdAtMillis,
            )
            streamBestScores(others, takes[i])
        }
        val live = scores.map { it.first }
        val raw = live.max() * margin
        val threshold = raw.coerceAtMost(ceiling).coerceAtLeast(model.threshold)
        return Result(live, scores.map { it.second }, model.threshold, threshold, capped = raw > ceiling && ceiling > model.threshold)
    }

    /** 녹음 하나를 실시간과 같이 100 ms씩 감지기에 넣고, 뒤에 무음을 붙여 마지막 구간까지 평가한다. (기존 방식, 등록 방식) 최고 점수 */
    private fun streamBestScores(model: WakeWordModel, take: ShortArray): Pair<Float, Float> {
        var best = Float.POSITIVE_INFINITY
        var bestMatched = Float.POSITIVE_INFINITY
        val detector = AdaptiveWakeWordDetector(model)
        detector.compareScores = true
        detector.segmentListener = AdaptiveWakeWordDetector.SegmentListener { score, matched, _, _ ->
            best = minOf(best, score)
            bestMatched = minOf(bestMatched, matched)
        }
        val tail = ShortArray(AudioSpec.samplesForMillis(TAIL_MILLIS))
        val stream = take + tail
        var at = 0
        while (at < stream.size) {
            val n = minOf(CHUNK, stream.size - at)
            detector.process(stream.copyOfRange(at, at + n), n)
            at += n
        }
        return best to bestMatched
    }

    private const val CHUNK = 1_600
    private const val TAIL_MILLIS = 800
}
