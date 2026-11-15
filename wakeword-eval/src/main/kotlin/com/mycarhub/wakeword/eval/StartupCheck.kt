package com.mycarhub.wakeword.eval

import com.mycarhub.wakeword.core.AudioSpec
import com.mycarhub.wakeword.core.Detection
import com.mycarhub.wakeword.core.Levels
import com.mycarhub.wakeword.core.StreamingWakeWordDetector
import com.mycarhub.wakeword.core.TrainingResult
import com.mycarhub.wakeword.core.WakeWordModel
import com.mycarhub.wakeword.core.WakeWordTrainer
import com.mycarhub.wakeword.core.WavIO
import com.mycarhub.wakeword.phone.AdaptiveWakeWordDetector
import java.io.File
import kotlin.math.pow
import kotlin.random.Random

/** 스트림에 넣을 수 있는 감지기 (원본·대안 비교용). */
fun interface StreamDetector {
    fun process(chunk: ShortArray, length: Int): Detection?
}

/**
 * 마이크 시작 직후 상황 재현: 짧은 무음(마이크 준비) → 일정한 실내 소음 → 호출어 → 소음.
 * 소음 레벨별로 호출어가 감지되는지, 발화 구간이 최대 길이로만 잘리는지(갇힘) 본다.
 * 인자: startup <데이터 폴더> [이름]
 */
fun startupCheck(
    root: File,
    name: String = "bungbung",
    detectors: Map<String, (WakeWordModel, (Int) -> Unit) -> StreamDetector> = mapOf("original" to ::original, "adaptive (phone)" to ::adaptive),
) {
    val dir = File(root, name)
    val rng = Random(7)
    fun load(sub: String) = File(dir, sub).listFiles { f -> f.extension == "wav" }!!.sortedBy { it.name }.map { WavIO.read(it).samples }
    fun noise(millis: Int, db: Float): ShortArray {
        val raw = ShortArray(AudioSpec.samplesForMillis(millis)) { ((rng.nextFloat() * 2 - 1) * 8_000).toInt().toShort() }
        val g = 10.0.pow((db - Levels.rmsDb(raw)) / 20.0)
        return ShortArray(raw.size) { (raw[it] * g).toInt().toShort() }
    }
    fun scale(s: ShortArray, db: Float): ShortArray {
        val g = 10.0.pow((db - Levels.rmsDb(s)) / 20.0)
        return ShortArray(s.size) { (s[it] * g).toInt().coerceIn(-32_768, 32_767).toShort() }
    }
    fun withRoom(clip: ShortArray, db: Float) = noise(400, db) + clip + noise(1_200, db)

    val model = (WakeWordTrainer().train(name, load("enroll").map { withRoom(it, -65f) }) as TrainingResult.Success).model
    val positives = load("positive").filter { true }
    println("# startup check ($name, threshold=%.3f)".format(model.threshold))

    for ((label, make) in detectors) {
        println()
        println("## $label")
        for (noiseDb in listOf(-70f, -60f, -52f, -45f)) {
            for (startup in listOf(false, true)) {
                var hits = 0
                var maxCuts = 0
                var segments = 0
                for (clip in positives) {
                    // 무음 300 ms(마이크 준비) → 소음 4 s → 호출어(-26 dBFS) → 소음 3 s
                    val stream = (if (startup) ShortArray(AudioSpec.samplesForMillis(300)) else ShortArray(0)) +
                        noise(4_000, noiseDb) + mixed(scale(clip, -26f), noise(AudioSpec.millisForSamples(clip.size) + 10, noiseDb)) +
                        noise(3_000, noiseDb)
                    val detector = make(model) { millis ->
                        segments++
                        if (millis >= 3_000) maxCuts++
                    }
                    var hit = false
                    var at = 0
                    while (at < stream.size) {
                        val n = minOf(1_600, stream.size - at)
                        if (detector.process(stream.copyOfRange(at, at + n), n) != null) hit = true
                        at += n
                    }
                    if (hit) hits++
                }
                println(
                    "noise %4.0f dB, startup silence %-5s: detected %d/%d, segments=%d (cut at max length: %d)"
                        .format(noiseDb, startup, hits, positives.size, segments, maxCuts),
                )
            }
        }
    }
}

private fun mixed(a: ShortArray, b: ShortArray) = ShortArray(a.size) { (a[it] + b[it]).coerceIn(-32_768, 32_767).toShort() }

private fun original(model: WakeWordModel, onSegment: (Int) -> Unit): StreamDetector {
    val d = StreamingWakeWordDetector(model)
    d.segmentListener = StreamingWakeWordDetector.SegmentListener { _, _, millis -> onSegment(millis) }
    return StreamDetector { chunk, length -> d.process(chunk, length) }
}

private fun adaptive(model: WakeWordModel, onSegment: (Int) -> Unit): StreamDetector {
    val d = AdaptiveWakeWordDetector(model)
    d.segmentListener = AdaptiveWakeWordDetector.SegmentListener { _, _, _, millis -> onSegment(millis) }
    return StreamDetector { chunk, length -> d.process(chunk, length) }
}
