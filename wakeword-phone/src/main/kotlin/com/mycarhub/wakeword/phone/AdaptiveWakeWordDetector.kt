package com.mycarhub.wakeword.phone

import com.mycarhub.wakeword.core.AudioSpec
import com.mycarhub.wakeword.core.Detection
import com.mycarhub.wakeword.core.DetectorConfig
import com.mycarhub.wakeword.core.FeaturePipeline
import com.mycarhub.wakeword.core.Levels
import com.mycarhub.wakeword.core.WakeWordMatcher
import com.mycarhub.wakeword.core.WakeWordModel
import kotlin.math.max
import kotlin.math.min

/**
 * [com.mycarhub.wakeword.core.StreamingWakeWordDetector]와 같은 감지기에 배경 소음 추정 보완 두 가지를 더했다.
 * 점수 계산과 개인 기준값(threshold)은 그대로다.
 *
 * 갤럭시 실기기에서 확인한 문제: 마이크 시작 직후 거의 무음인 프레임으로 소음 기준이 -100 dB 근처에서 시작하고,
 * 실내 소음이 -55 dB보다 크면 처음부터 '발화 중'으로 판단한다. 발화 중에는 소음 기준을 갱신하지 않아
 * 발화가 끝나지 않고 3초 단위로만 잘려(로그의 segment 3100ms 반복) 호출어 한 번만 따로 평가되지 못한다.
 *
 * 보완:
 * 1. 디지털 무음에 가까운 프레임([SILENCE_DB] 미만)은 소음 기준을 정하는 데 쓰지 않는다.
 * 2. 쉬지 않는 '발화'가 최대 길이에 닿으면 그 구간에서 가장 조용했던 프레임 레벨까지 소음 기준을 올린다.
 *    실제 말에는 음절 사이 짧은 쉼이 있어 기준이 거의 오르지 않고, 일정한 소음이면 기준이 소음 레벨로 맞춰진다.
 */
class AdaptiveWakeWordDetector(
    model: WakeWordModel,
    private val pipeline: FeaturePipeline = FeaturePipeline(),
    private val config: DetectorConfig = DetectorConfig(),
    /** 감지 기준. 기본은 모델의 기준, 폰에서는 [LiveCalibration] 결과를 쓴다 */
    private val threshold: Float = model.threshold,
    /** 감지 판정에 쓰는 채점 방식 */
    private val scoring: Scoring = Scoring.SLIDING,
) {
    enum class Scoring {
        /** 기존: 고정 길이 창을 옮겨 가며 채점 ([WakeWordMatcher.bestScore]) */
        SLIDING,

        /** 등록과 같은 방식으로 발화 경계를 찾아 채점 ([EnrollmentMatchedScorer]) */
        ENROLLMENT_MATCHED,
    }

    fun interface SegmentListener {
        /** @param otherScore [compareScores]일 때 판정에 쓰지 않은 다른 방식의 점수, 아니면 NaN */
        fun onSegmentScored(score: Float, otherScore: Float, threshold: Float, durationMillis: Int)
    }

    /** 진단용: 약 2초마다 입력 레벨과 소음 기준 (오디오 내용은 담지 않음). */
    fun interface LevelListener {
        fun onLevels(averageDb: Float, minDb: Float, noiseDb: Float, inSpeech: Boolean)
    }

    var segmentListener: SegmentListener? = null
    var levelListener: LevelListener? = null

    /** 검증용: 두 채점 방식을 모두 계산해 [SegmentListener]로 알린다 (판정은 [scoring]만 사용). */
    var compareScores = false

    private val matcher = WakeWordMatcher(model, pipeline)
    private val matched = EnrollmentMatchedScorer(model, pipeline)
    private val history = ShortArray(AudioSpec.samplesForMillis(config.maxSegmentMillis + HISTORY_MARGIN_MILLIS))
    private val frame = ShortArray(AudioSpec.LEVEL_FRAME)
    private var frameFill = 0
    private var total = 0L

    private var noiseDb = Float.NaN
    private var loudFrames = 0
    private var inSpeech = false
    private var speechStart = 0L
    private var lastLoud = 0L
    private var cooldownUntil = 0L
    private var segmentMinDb = Float.MAX_VALUE

    private var statFrames = 0
    private var statSum = 0.0
    private var statMin = Float.MAX_VALUE

    private val hangover = AudioSpec.samplesForMillis(config.hangoverMillis)
    private val maxSegment = AudioSpec.samplesForMillis(config.maxSegmentMillis)
    private val padding = AudioSpec.samplesForMillis(config.paddingMillis)
    private val noiseWindow = AudioSpec.samplesForMillis(NOISE_WINDOW_MILLIS)
    private var noiseProfile: DoubleArray? = null

    fun process(chunk: ShortArray, length: Int = chunk.size): Detection? {
        var detection: Detection? = null
        for (i in 0 until length) {
            val s = chunk[i]
            history[(total % history.size).toInt()] = s
            total++
            frame[frameFill++] = s
            if (frameFill == frame.size) {
                frameFill = 0
                onFrame()?.let { detection = it }
            }
        }
        return detection
    }

    private fun onFrame(): Detection? {
        val level = Levels.rmsDb(frame)
        report(level)
        if (level < SILENCE_DB) {
            // 마이크 준비 중 무음: 소음 기준에 넣지 않고 발화로도 보지 않는다
            loudFrames = 0
        } else {
            if (noiseDb.isNaN()) noiseDb = level
            if (!inSpeech) {
                // 조용해지면 빠르게, 시끄러워지면 천천히 따라간다 (발화가 배경으로 흡수되지 않게)
                noiseDb = if (level < noiseDb) 0.9f * noiseDb + 0.1f * level else 0.995f * noiseDb + 0.005f * level
            }
        }
        val loud = !noiseDb.isNaN() && level > max(noiseDb + config.vadMarginDb, config.minSpeechDb)
        if (loud) {
            loudFrames++
            lastLoud = total
            if (!inSpeech && loudFrames >= config.onsetFrames) {
                inSpeech = true
                speechStart = total - config.onsetFrames * AudioSpec.LEVEL_FRAME
                segmentMinDb = Float.MAX_VALUE
            }
        } else {
            loudFrames = 0
        }
        if (!inSpeech) return null
        if (level >= SILENCE_DB) segmentMinDb = min(segmentMinDb, level)

        return when {
            total - lastLoud >= hangover -> {
                inSpeech = false
                evaluate(speechStart, min(total, lastLoud + padding))
            }
            total - speechStart >= maxSegment -> {
                val result = evaluate(speechStart, total)
                speechStart += maxSegment / 2
                // 쉬지 않고 3초 넘게 '발화'면 일정한 배경 소음일 가능성이 높다: 기준을 구간 최저 레벨로 올린다
                if (segmentMinDb != Float.MAX_VALUE && segmentMinDb > noiseDb) noiseDb = segmentMinDb
                segmentMinDb = Float.MAX_VALUE
                result
            }
            else -> null
        }
    }

    private fun report(level: Float) {
        val listener = levelListener ?: return
        statFrames++
        statSum += level
        statMin = min(statMin, level)
        if (statFrames < LEVEL_REPORT_FRAMES) return
        listener.onLevels((statSum / statFrames).toFloat(), statMin, noiseDb, inSpeech)
        statFrames = 0
        statSum = 0.0
        statMin = Float.MAX_VALUE
    }

    private fun evaluate(start: Long, end: Long): Detection? {
        if (end <= cooldownUntil) return null
        val from = max(max(0L, start - padding), total - history.size)
        val length = (end - from).toInt()
        if (length <= 0) return null
        val samples = ShortArray(length) { history[((from + it) % history.size).toInt()] }
        val noise = noiseBefore(from)
        val slidingScore = if (scoring == Scoring.SLIDING || compareScores) matcher.bestScore(samples, noise = noise) else Float.NaN
        val matchedScore = if (scoring == Scoring.ENROLLMENT_MATCHED || compareScores) matched.score(samples, noiseDb, noise) else Float.NaN
        val (score, other) = if (scoring == Scoring.SLIDING) slidingScore to matchedScore else matchedScore to slidingScore
        segmentListener?.onSegmentScored(score, other, threshold, AudioSpec.millisForSamples(length))
        if (score > threshold) return null
        cooldownUntil = total + AudioSpec.samplesForMillis(config.cooldownMillis)
        inSpeech = false
        return Detection(score, threshold, total)
    }

    /** 발화 직전 배경 소음으로 스펙트럼 차감용 소음 특성을 갱신한다. 직전 구간이 짧으면 이전 값을 쓴다. */
    private fun noiseBefore(segmentFrom: Long): DoubleArray? {
        val from = maxOf(0L, total - history.size, segmentFrom - noiseWindow)
        if (segmentFrom - from >= AudioSpec.FRAME_LENGTH * 2) {
            val length = (segmentFrom - from).toInt()
            val samples = ShortArray(length) { history[((from + it) % history.size).toInt()] }
            pipeline.noiseProfile(samples, listOf(0 until length))?.let { noiseProfile = it }
        }
        return noiseProfile
    }

    private companion object {
        const val HISTORY_MARGIN_MILLIS = 1_000
        const val NOISE_WINDOW_MILLIS = 500
        const val SILENCE_DB = -100f
        const val LEVEL_REPORT_FRAMES = 200 // 2초
    }
}
