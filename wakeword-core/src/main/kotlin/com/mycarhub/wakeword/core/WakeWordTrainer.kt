package com.mycarhub.wakeword.core

import kotlin.math.max

data class TrainerConfig(
    val takeCount: Int = 3,
    /** 감지 점수 = 가장 가까운 템플릿 N개의 평균 거리 */
    val minMatches: Int = 1,
    /** 등록 샘플끼리의 최대 점수에 곱하는 여유 배수 */
    val thresholdMargin: Float = 1.15f,
    val minThreshold: Float = 0.12f,
    /** 이보다 큰 기준이 필요할 만큼 샘플이 제각각이면 모델을 만들지 않는다 (오인식 방지) */
    val maxThreshold: Float = 0.40f,
    /** 다른 샘플과의 평균 거리가 중앙값의 이 배수를 넘으면 일관성 불량 */
    val outlierRatio: Float = 1.3f,
    val outlierMinGap: Float = 0.03f,
    /** 발화 길이가 중앙값 대비 이 비율 밖이면 일관성 불량 */
    val durationRatioRange: ClosedFloatingPointRange<Float> = 0.6f..1.67f,
)

data class TrainingStats(
    /** 회차별 leave-one-out 점수 (나머지 템플릿만으로 계산한 감지 점수) */
    val leaveOneOutScores: List<Float>,
    /** 회차별 다른 회차와의 평균 DTW 거리 */
    val meanDistances: List<Float>,
    val speechMillis: List<Int>,
    val threshold: Float,
)

sealed interface TrainingResult {
    data class Success(val model: WakeWordModel, val stats: TrainingStats) : TrainingResult

    /** 품질 검사를 통과하지 못한 회차 (0부터 시작하는 인덱스) */
    data class PoorQuality(val issues: Map<Int, List<QualityIssue>>) : TrainingResult

    /** 다른 회차와 발음·길이가 크게 다른 회차. 해당 회차만 다시 녹음하면 된다. */
    data class Inconsistent(val takes: List<Int>, val stats: TrainingStats) : TrainingResult

    data class NotEnoughTakes(val required: Int, val actual: Int) : TrainingResult
}

/**
 * 녹음 5회분으로 개인 호출어 모델을 만든다.
 *
 * 1. 회차별 품질 검사 → 발화 구간의 MFCC+delta 템플릿 추출
 * 2. 모든 회차 쌍의 DTW 거리로 일관성 검사 (튀는 회차는 재녹음 요청)
 * 3. leave-one-out 점수로 이 사용자에게 맞는 감지 기준값 계산
 */
class WakeWordTrainer(
    private val config: TrainerConfig = TrainerConfig(),
    private val analyzer: QualityAnalyzer = QualityAnalyzer(),
    private val pipeline: FeaturePipeline = FeaturePipeline(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val takeCount: Int get() = config.takeCount

    fun analyze(samples: ShortArray): QualityReport = analyzer.analyze(samples)

    fun train(name: String, takes: List<ShortArray>): TrainingResult {
        if (takes.size < config.takeCount) return TrainingResult.NotEnoughTakes(config.takeCount, takes.size)

        val reports = takes.map { analyzer.analyze(it) }
        val poor = reports.withIndex().filter { !it.value.isAcceptable }.associate { it.index to it.value.issues }
        if (poor.isNotEmpty()) return TrainingResult.PoorQuality(poor)

        val templates = takes.zip(reports) { samples, report ->
            val noise = pipeline.noiseProfile(samples, listOf(0 until report.speechStart, report.speechEnd until samples.size))
            pipeline.compute(samples, report.speechStart, report.speechEnd, noise)
        }
        val n = templates.size
        val distances = Array(n) { FloatArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val d = Dtw.distance(templates[i], templates[j])
            distances[i][j] = d
            distances[j][i] = d
        }

        val meanDistances = List(n) { i -> (0 until n).filter { it != i }.map { distances[i][it] }.average().toFloat() }
        val looScores = List(n) { i ->
            (0 until n).filter { it != i }.map { distances[i][it] }.sorted().take(config.minMatches).average().toFloat()
        }
        val millis = reports.map { it.speechMillis }
        // 3개 녹음은 일부러 다르게(목소리·속도·높낮이) 받으므로 발음·길이 차이로 재녹음을 요구하지 않는다.
        // 각 녹음은 독립 템플릿이며 어느 하나와만 가까워도 감지된다 (minMatches = 1).
        val threshold = (looScores.max() * config.thresholdMargin)
            .coerceAtLeast(config.minThreshold)
            .coerceAtMost(config.maxThreshold)
        val stats = TrainingStats(looScores, meanDistances, millis, threshold)

        val model = WakeWordModel(
            name = name,
            templates = templates,
            threshold = threshold,
            minMatches = config.minMatches,
            createdAtMillis = clock(),
        )
        return TrainingResult.Success(model, stats)
    }

    private fun findOutliers(meanDistances: List<Float>, millis: List<Int>): List<Int> {
        val medianDistance = meanDistances.sorted()[meanDistances.size / 2]
        val medianMillis = millis.sorted()[millis.size / 2].toFloat()
        return meanDistances.indices.filter { i ->
            val d = meanDistances[i]
            val distanceOutlier = d > medianDistance * config.outlierRatio && d - medianDistance > config.outlierMinGap
            val durationOutlier = millis[i] / max(1f, medianMillis) !in config.durationRatioRange
            distanceOutlier || durationOutlier
        }.sortedByDescending { meanDistances[it] }
    }
}
