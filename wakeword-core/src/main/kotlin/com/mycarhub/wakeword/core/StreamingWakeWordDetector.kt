package com.mycarhub.wakeword.core

import kotlin.math.max
import kotlin.math.min

data class Detection(
    val score: Float,
    val threshold: Float,
    /** 스트림 시작부터 센 감지 시점 (샘플) */
    val atSample: Long,
)

data class DetectorConfig(
    /** 배경 소음보다 이만큼 크면 발화로 본다 */
    val vadMarginDb: Float = 9f,
    val minSpeechDb: Float = -55f,
    /** 연속으로 이 프레임 수(10 ms) 이상 커야 발화 시작 */
    val onsetFrames: Int = 3,
    /** 이 시간 동안 조용하면 발화 끝 */
    val hangoverMillis: Int = 300,
    /** 쉬지 않고 말이 이어지면 이 길이마다 중간 평가 */
    val maxSegmentMillis: Int = 3_000,
    val paddingMillis: Int = 100,
    /** 감지 직후 같은 발화로 중복 감지하지 않는 시간 */
    val cooldownMillis: Int = 1_500,
)

/**
 * 마이크 스트림에서 호출어를 찾는 감지기.
 *
 * 적응형 에너지 VAD로 발화 구간을 자르고, 구간이 끝나면 [WakeWordMatcher]로 점수를 계산해
 * 개인 기준값 이하이면 감지로 판단한다. 스레드 안전하지 않으므로 한 스레드에서만 호출한다.
 */
class StreamingWakeWordDetector(
    model: WakeWordModel,
    private val pipeline: FeaturePipeline = FeaturePipeline(),
    private val config: DetectorConfig = DetectorConfig(),
) {
    fun interface SegmentListener {
        fun onSegmentScored(score: Float, threshold: Float, durationMillis: Int)
    }

    var segmentListener: SegmentListener? = null

    private val matcher = WakeWordMatcher(model, pipeline)
    private val threshold = model.threshold
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

    fun reset() {
        frameFill = 0
        noiseDb = Float.NaN
        loudFrames = 0
        inSpeech = false
    }

    private fun onFrame(): Detection? {
        val level = Levels.rmsDb(frame)
        if (noiseDb.isNaN()) noiseDb = level
        if (!inSpeech) {
            // 조용해지면 빠르게, 시끄러워지면 천천히 따라간다 (발화가 배경으로 흡수되지 않게)
            noiseDb = if (level < noiseDb) 0.9f * noiseDb + 0.1f * level else 0.995f * noiseDb + 0.005f * level
        }
        val loud = level > max(noiseDb + config.vadMarginDb, config.minSpeechDb)
        if (loud) {
            loudFrames++
            lastLoud = total
            if (!inSpeech && loudFrames >= config.onsetFrames) {
                inSpeech = true
                speechStart = total - config.onsetFrames * AudioSpec.LEVEL_FRAME
            }
        } else {
            loudFrames = 0
        }
        if (!inSpeech) return null

        return when {
            total - lastLoud >= hangover -> {
                inSpeech = false
                evaluate(speechStart, min(total, lastLoud + padding))
            }
            total - speechStart >= maxSegment -> {
                val result = evaluate(speechStart, total)
                speechStart += maxSegment / 2
                result
            }
            else -> null
        }
    }

    private fun evaluate(start: Long, end: Long): Detection? {
        if (end <= cooldownUntil) return null
        val from = max(max(0L, start - padding), total - history.size)
        val length = (end - from).toInt()
        if (length <= 0) return null
        val samples = ShortArray(length) { history[((from + it) % history.size).toInt()] }
        val score = matcher.bestScore(samples, noise = noiseBefore(from))
        segmentListener?.onSegmentScored(score, threshold, AudioSpec.millisForSamples(length))
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
    }
}
