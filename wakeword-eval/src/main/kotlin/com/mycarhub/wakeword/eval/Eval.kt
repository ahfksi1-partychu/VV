package com.mycarhub.wakeword.eval

import com.mycarhub.wakeword.core.AudioSpec
import com.mycarhub.wakeword.core.Detection
import com.mycarhub.wakeword.core.Levels
import com.mycarhub.wakeword.core.QualityAnalyzer
import com.mycarhub.wakeword.core.QualityIssue
import com.mycarhub.wakeword.core.StreamingWakeWordDetector
import com.mycarhub.wakeword.core.TrainerConfig
import com.mycarhub.wakeword.core.TrainingResult
import com.mycarhub.wakeword.core.WakeWordModel
import com.mycarhub.wakeword.core.WakeWordModelCodec
import com.mycarhub.wakeword.core.WakeWordTrainer
import com.mycarhub.wakeword.core.WavIO
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.pow
import kotlin.random.Random

/**
 * 합성 음성 데이터로 품질 검사·등록·감지 성능을 측정한다.
 * 인자: <데이터 폴더> [thresholdMargin]
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "export") return exportDeviceAudio(File(args[1]), File(args[2]))
    if (args.firstOrNull() == "startup") return startupCheck(File(args.getOrElse(1) { "wakeword-eval/data/tts" }))
    if (args.firstOrNull() == "calibrate") return calibrationCheck(File(args.getOrElse(1) { "wakeword-eval/data/tts" }))
    if (args.firstOrNull() == "detail") return scoringDetail(File("wakeword-eval/data/tts"))
    if (args.firstOrNull() == "compare") return scoringComparison(File(args.getOrElse(1) { "wakeword-eval/data/tts" }))
    val root = File(args.getOrElse(0) { "wakeword-eval/data/tts" })
    val defaults = TrainerConfig()
    val margin = args.getOrNull(1)?.toFloat() ?: defaults.thresholdMargin
    val minThreshold = args.getOrNull(2)?.toFloat() ?: defaults.minThreshold
    val conversation = loadDir(File(root, "conversation"))
    val names = root.listFiles { f -> f.isDirectory && File(f, "enroll").isDirectory }!!.sortedBy { it.name }
    println("# Wake word evaluation (data=${root.path}, margin=$margin, minThreshold=$minThreshold)")
    var qualityFailures = 0
    for (dir in names) {
        val others = names.filter { it != dir }.flatMap { loadDir(File(it, "positive")) }
        qualityFailures += evaluateName(dir, conversation, others, TrainerConfig(thresholdMargin = margin, minThreshold = minThreshold))
    }
    println()
    println(if (qualityFailures == 0) "QUALITY SUITE: ALL PASS" else "QUALITY SUITE: $qualityFailures FAIL")
}

private class Clip(val name: String, val samples: ShortArray)

private val rng = Random(42)

private fun evaluateName(
    dir: File,
    conversation: List<Clip>,
    otherNames: List<Clip>,
    config: TrainerConfig,
): Int {
    val enroll = loadDir(File(dir, "enroll"))
    val positives = loadDir(File(dir, "positive"))
    val similar = loadDir(File(dir, "similar"))
    val takes = enroll.map { asTake(it.samples) }
    println()
    println("## ${dir.name}")

    val failures = qualitySuite(takes, similar, conversation, config)

    val trainer = WakeWordTrainer(config)
    val result = trainer.train(dir.name, takes)
    if (result !is TrainingResult.Success) {
        println("TRAINING FAILED: $result")
        return failures + 1
    }
    val model = roundTrip(result.model)
    val stats = result.stats
    println()
    println("training: threshold=${fmt(stats.threshold)} loo=${stats.leaveOneOutScores.map(::fmt)} " +
        "meanDist=${stats.meanDistances.map(::fmt)} speechMs=${stats.speechMillis}")

    val road = roadNoise(AudioSpec.SAMPLE_RATE * 120)
    val babble = babbleNoise(conversation, AudioSpec.SAMPLE_RATE * 120)
    val white = whiteNoise(AudioSpec.SAMPLE_RATE * 120)

    val pitchShifted = positives.flatMap { clip ->
        listOf(Clip("${clip.name}@0.92", resample(clip.samples, 0.92)), Clip("${clip.name}@1.08", resample(clip.samples, 1.08)))
    }
    val similarVariants = similar.flatMap { clip ->
        listOf(clip, Clip("${clip.name}@0.92", resample(clip.samples, 0.92)), Clip("${clip.name}@1.08", resample(clip.samples, 1.08)))
    }

    println()
    println("| 조건 | 시행 | 감지 | 비율 | 점수 중앙값 / 최대 (기준 ${fmt(model.threshold)}) |")
    println("|---|---|---|---|---|")
    row("정상 호출 (조용함, 말 빠르기 변형·문장 속 호출)", trials(model, positives))
    row("정상 호출 (조용함, 음높이 ±8% 변형)", trials(model, pitchShifted))
    for ((label, noise) in listOf("도로 소음" to road, "주변 대화(babble)" to babble, "백색 잡음" to white)) {
        for (snr in listOf(10f, 5f, 0f)) {
            row("정상 호출 + $label SNR ${snr.toInt()} dB", trials(model, positives, noise, snr))
        }
    }
    val similarTrials = trials(model, similarVariants)
    row("비슷한 발음 (오인식)", similarTrials)
    row("비슷한 발음 + 도로 소음 SNR 10 dB (오인식)", trials(model, similar, road, 10f))
    row("일상 대화 문장 (오인식)", trials(model, conversation))
    row("다른 호출어 (오인식)", trials(model, otherNames))

    println()
    println("비슷한 발음 중 오인식된 항목: ${similarTrials.detectedNames.ifEmpty { listOf("없음") }}")

    // 장시간 오인식: 일상 대화만 이어 붙여 도로 소음(SNR 15 dB) 위에 10분 재생 + 소음만 재생
    val talkStream = concat(conversation.map { it.samples }, gapMillis = 600)
    val longTalk = mix(repeatTo(talkStream, AudioSpec.SAMPLE_RATE * 600), road, 15f)
    val talkFa = run(model, longTalk).first.size
    val noiseOnly = listOf(road, babble, white).sumOf { run(model, scaleTo(it, -30f)).first.size }
    println("장시간 오인식: 일상 대화+도로소음 10분 → ${talkFa}회 (시간당 ${talkFa * 6}회), 소음만 6분(도로·babble·백색) → ${noiseOnly}회")

    val started = System.nanoTime()
    run(model, longTalk)
    val seconds = (System.nanoTime() - started) / 1e9
    println("처리 속도(PC): 10분 오디오를 ${fmt(seconds.toFloat())}초에 처리 (실시간 대비 ${fmt((600 / seconds).toFloat())}배)")
    return failures
}

/**
 * 에뮬레이터 개발 빌드 테스트용 오디오 내보내기 (앱 files/debug 에 복사해 사용).
 * enroll/take_1..5.wav: 등록 녹음 대용, listen.wav: 대화 → 비슷한 발음 → 대화 → 호출어 순서의 스트림.
 */
private fun exportDeviceAudio(nameDir: File, out: File) {
    val conversation = loadDir(File(nameDir.parentFile, "conversation"))
    val enroll = loadDir(File(nameDir, "enroll"))
    val similar = loadDir(File(nameDir, "similar"))
    val positives = loadDir(File(nameDir, "positive"))
    File(out, "enroll").mkdirs()
    enroll.forEachIndexed { i, clip -> WavIO.write(File(out, "enroll/take_${i + 1}.wav"), asTake(clip.samples)) }
    // 일관성 검사 확인용: 3회차에 비슷한 다른 단어를 녹음한 경우
    WavIO.write(File(out, "inconsistent_take.wav"), asTake(similar[0].samples))
    val parts = listOf(
        roomTone(1_500), conversation[0].samples, roomTone(1_000), similar[0].samples, roomTone(1_000),
        conversation[11].samples, roomTone(1_000), positives.first { it.name == "solo_3" }.samples, roomTone(2_000),
    )
    WavIO.write(File(out, "listen.wav"), concat(parts, 0))
    println("listen.wav = ${conversation[0].name}, ${similar[0].name}, ${conversation[11].name}, solo_3")
    val enrollTakes = enroll.map { asTake(it.samples) }
    val result = WakeWordTrainer().train(nameDir.name, enrollTakes)
    if (result is TrainingResult.Success) {
        val detector = StreamingWakeWordDetector(result.model)
        detector.segmentListener = StreamingWakeWordDetector.SegmentListener { score, threshold, ms ->
            println("  host segment ${ms}ms score=${fmt(score)} threshold=${fmt(threshold)}")
        }
        val stream = WavIO.read(File(out, "listen.wav")).samples
        val chunk = ShortArray(1_600)
        var pos = 0
        while (pos < stream.size) {
            val n = minOf(chunk.size, stream.size - pos)
            System.arraycopy(stream, pos, chunk, 0, n)
            detector.process(chunk, n)?.let { println("  host DETECTED score=${fmt(it.score)} at ${it.atSample * 1000 / AudioSpec.SAMPLE_RATE} ms") }
            pos += n
        }
    }
}

// region 품질 검사 시나리오

private fun qualitySuite(takes: List<ShortArray>, similar: List<Clip>, conversation: List<Clip>, config: TrainerConfig): Int {
    val analyzer = QualityAnalyzer()
    val base = takes[0]
    val report = analyzer.analyze(base)
    val speech = base.copyOfRange(report.speechStart, report.speechEnd)
    val cases = listOf(
        Triple("정상 녹음", base, emptySet()),
        Triple("무음 (마이크 입력 없음)", ShortArray(AudioSpec.SAMPLE_RATE * 3), setOf(QualityIssue.SILENT)),
        Triple("아주 작은 목소리", gainTo(base, report.speechDb, -46f), setOf(QualityIssue.TOO_QUIET)),
        Triple("너무 짧음 (200 ms)", asTake(speech.copyOfRange(0, AudioSpec.samplesForMillis(200))), setOf(QualityIssue.TOO_SHORT)),
        Triple("너무 큼 (클리핑)", gain(base, 12f), setOf(QualityIssue.CLIPPED)),
        Triple("잡음 많음 (SNR 5 dB)", mix(base, whiteNoise(base.size), 5f), setOf(QualityIssue.NOISY)),
        Triple("녹음 시작에서 잘림", base.copyOfRange(report.speechStart + AudioSpec.samplesForMillis(150), base.size), setOf(QualityIssue.CUT_OFF)),
        Triple("호출어 + 긴 문장", asTake(concat(listOf(speech, conversation[1].samples), 50)), setOf(QualityIssue.TOO_LONG)),
    )
    var failures = 0
    println()
    println("| 품질 검사 시나리오 | 기대 | 결과 | 발화 길이·SNR | 판정 |")
    println("|---|---|---|---|---|")
    for ((label, samples, expected) in cases) {
        val r = analyzer.analyze(samples)
        val issues = r.issues.toSet()
        val pass = if (expected.isEmpty()) issues.isEmpty() else issues.containsAll(expected)
        if (!pass) failures++
        val detail = if (r.speechMillis > 0) "${r.speechMillis} ms, ${r.snrDb.toInt()} dB" else "-"
        println("| $label | ${expected.ifEmpty { setOf("통과") }} | ${issues.ifEmpty { setOf("통과") }} | $detail | ${if (pass) "PASS" else "FAIL"} |")
    }

    // 샘플 간 일관성: 3회차를 비슷한 다른 단어로 바꾸면 해당 회차만 재녹음 대상이어야 한다
    val trainer = WakeWordTrainer(config)
    for (replacement in similar.take(3)) {
        val mixed = takes.toMutableList().also { it[2] = asTake(replacement.samples) }
        val result = trainer.train("test", mixed)
        val pass = result is TrainingResult.Inconsistent && result.takes == listOf(2)
        if (!pass) failures++
        val shown = when (result) {
            is TrainingResult.Inconsistent -> "재녹음 요청 ${result.takes.map { it + 1 }}회차"
            is TrainingResult.Success -> "등록됨 (기준 ${fmt(result.stats.threshold)})"
            else -> result.toString()
        }
        println("| 3회차를 '${replacement.name}'(으)로 녹음 | 3회차 재녹음 | $shown | - | ${if (pass) "PASS" else "FAIL"} |")
    }
    return failures
}

// endregion

// region 감지 실행

private class Trials(val total: Int, val detected: Int, val scores: List<Float>, val detectedNames: List<String>)

private fun trials(model: WakeWordModel, clips: List<Clip>, noise: ShortArray? = null, snrDb: Float = 0f): Trials {
    val detectedNames = mutableListOf<String>()
    val scores = mutableListOf<Float>()
    var offset = 0
    for (clip in clips) {
        var stream = concat(listOf(roomTone(800), clip.samples, roomTone(1_000)), 0)
        if (noise != null) {
            val segment = ShortArray(stream.size) { noise[(offset + it) % noise.size] }
            offset += stream.size
            stream = mix(stream, segment, snrDb, reference = clip.samples)
        }
        val (detections, best) = run(model, stream)
        if (detections.isNotEmpty()) detectedNames += clip.name
        scores += best
    }
    return Trials(clips.size, detectedNames.size, scores, detectedNames)
}

private fun run(model: WakeWordModel, audio: ShortArray): Pair<List<Detection>, Float> {
    val detector = StreamingWakeWordDetector(model)
    var best = Float.POSITIVE_INFINITY
    detector.segmentListener = StreamingWakeWordDetector.SegmentListener { score, _, _ -> best = minOf(best, score) }
    val detections = mutableListOf<Detection>()
    val chunk = ShortArray(1_600)
    var pos = 0
    while (pos < audio.size) {
        val n = minOf(chunk.size, audio.size - pos)
        System.arraycopy(audio, pos, chunk, 0, n)
        detector.process(chunk, n)?.let { detections += it }
        pos += n
    }
    return detections to best
}

private fun row(label: String, t: Trials) {
    val finite = t.scores.filter { it.isFinite() }.sorted()
    val median = finite.getOrNull(finite.size / 2)?.let(::fmt) ?: "-"
    val max = finite.lastOrNull()?.let(::fmt) ?: "-"
    val min = finite.firstOrNull()?.let(::fmt) ?: "-"
    println("| $label | ${t.total} | ${t.detected} | ${fmt(100f * t.detected / t.total)}% | $median / $max (최소 $min) |")
}

// endregion

// region 오디오 유틸

private fun loadDir(dir: File): List<Clip> =
    (dir.listFiles { f -> f.extension == "wav" } ?: emptyArray()).sortedBy { it.name }.map {
        val audio = WavIO.read(it)
        require(audio.sampleRate == AudioSpec.SAMPLE_RATE) { "${it.name}: ${audio.sampleRate} Hz" }
        Clip(it.nameWithoutExtension, audio.samples)
    }

/** 실제 3초 녹음처럼: 앞 0.4초 실내 잡음 + 발화 + 나머지 실내 잡음. */
private fun asTake(clip: ShortArray): ShortArray {
    val total = maxOf(AudioSpec.samplesForMillis(2_500), clip.size + AudioSpec.samplesForMillis(800))
    val lead = roomTone(400)
    return concat(listOf(lead, clip, roomTone(AudioSpec.millisForSamples(total - lead.size - clip.size))), 0)
}

private fun roomTone(millis: Int): ShortArray = scaleTo(whiteNoise(AudioSpec.samplesForMillis(millis)), -65f)

private fun whiteNoise(length: Int): ShortArray = ShortArray(length) { (rng.nextFloat() * 2 - 1).times(8_000).toInt().toShort() }

/** 저주파 위주의 도로·엔진 소음 근사 (brown noise). */
private fun roadNoise(length: Int): ShortArray {
    var y = 0.0
    val raw = DoubleArray(length) {
        y = 0.995 * y + (rng.nextDouble() * 2 - 1)
        y
    }
    val peak = raw.maxOf { kotlin.math.abs(it) }
    return ShortArray(length) { (raw[it] / peak * 12_000).toInt().toShort() }
}

/** 여러 대화 문장을 겹친 주변 대화 소음. */
private fun babbleNoise(conversation: List<Clip>, length: Int): ShortArray {
    val out = IntArray(length)
    repeat(4) { voice ->
        val stream = repeatTo(concat(conversation.shuffled(Random(voice)).map { it.samples }, 200), length)
        for (i in 0 until length) out[i] += stream[i]
    }
    return ShortArray(length) { (out[it] / 2).coerceIn(-32_768, 32_767).toShort() }
}

private fun mix(signal: ShortArray, noise: ShortArray, snrDb: Float, reference: ShortArray = signal): ShortArray {
    val speechDb = activeLevel(reference)
    val noiseDb = Levels.rmsDb(noise, 0, minOf(noise.size, signal.size))
    val gain = 10.0.pow((speechDb - snrDb - noiseDb) / 20.0)
    return ShortArray(signal.size) { (signal[it] + noise[it % noise.size] * gain).toInt().coerceIn(-32_768, 32_767).toShort() }
}

/** 발화 부분의 평균 레벨 (가장 큰 프레임보다 25 dB 이상 작은 프레임은 제외). */
private fun activeLevel(samples: ShortArray): Float {
    val levels = Levels.frameLevels(samples)
    val top = levels.maxOrNull() ?: return AudioSpec.MIN_DB
    val active = levels.filter { it > top - 25 }
    val power = active.sumOf { 10.0.pow(it / 10.0) } / active.size
    return (10 * kotlin.math.log10(power)).toFloat()
}

private fun scaleTo(samples: ShortArray, db: Float): ShortArray = gain(samples, db - Levels.rmsDb(samples))

private fun gainTo(samples: ShortArray, currentDb: Float, targetDb: Float) = gain(samples, targetDb - currentDb)

private fun gain(samples: ShortArray, db: Float): ShortArray {
    val g = 10.0.pow(db / 20.0)
    return ShortArray(samples.size) { (samples[it] * g).toInt().coerceIn(-32_768, 32_767).toShort() }
}

/** 선형 보간 리샘플링: factor > 1 이면 빠르고 높은 목소리 (화자 차이 근사). */
private fun resample(samples: ShortArray, factor: Double): ShortArray {
    val length = (samples.size / factor).toInt()
    return ShortArray(length) {
        val pos = it * factor
        val i = pos.toInt()
        val frac = pos - i
        val a = samples[minOf(i, samples.size - 1)]
        val b = samples[minOf(i + 1, samples.size - 1)]
        (a + (b - a) * frac).toInt().toShort()
    }
}

private fun concat(parts: List<ShortArray>, gapMillis: Int): ShortArray {
    val gap = AudioSpec.samplesForMillis(gapMillis)
    val out = ShortArray(parts.sumOf { it.size } + gap * maxOf(0, parts.size - 1))
    var pos = 0
    parts.forEachIndexed { i, part ->
        System.arraycopy(part, 0, out, pos, part.size)
        pos += part.size + if (i < parts.size - 1) gap else 0
    }
    return out
}

private fun repeatTo(samples: ShortArray, length: Int) = ShortArray(length) { samples[it % samples.size] }

private fun roundTrip(model: WakeWordModel): WakeWordModel {
    val bytes = ByteArrayOutputStream().also { WakeWordModelCodec.write(model, it) }.toByteArray()
    return WakeWordModelCodec.read(ByteArrayInputStream(bytes))
}

private fun fmt(v: Float) = "%.3f".format(v)

// endregion

// region 채점 방식 비교 (기존 창 이동 vs 등록 방식)

/**
 * 같은 녹음에 두 채점 방식의 점수를 함께 구해, 기준값과 무관하게 '호출어와 다른 말을 얼마나 잘 가르는지' 비교한다.
 * 인자: compare <데이터 폴더>
 */
private fun scoringComparison(root: File) {
    val conversation = loadDir(File(root, "conversation"))
    val names = root.listFiles { f -> f.isDirectory && File(f, "enroll").isDirectory }!!.sortedBy { it.name }
    val road = roadNoise(AudioSpec.SAMPLE_RATE * 120)
    for (dir in names) {
        val model = (WakeWordTrainer().train(dir.name, loadDir(File(dir, "enroll")).map { asTake(it.samples) }) as TrainingResult.Success).model
        val positives = loadDir(File(dir, "positive"))
        val similar = loadDir(File(dir, "similar"))
        val others = names.filter { it != dir }.flatMap { loadDir(File(it, "positive")) }
        val variants = positives.flatMap { c -> listOf(0.92, 1.0, 1.08).map { f -> if (f == 1.0) c.samples else resample(c.samples, f) } }

        val pos = variants.map { clipScores(model, it) } +
            positives.map { clipScores(model, it.samples, road, 10f) } + positives.map { clipScores(model, it.samples, road, 5f) }
        val sim = similar.flatMap { c -> listOf(0.92, 1.0, 1.08).map { f -> clipScores(model, if (f == 1.0) c.samples else resample(c.samples, f)) } }
        val talk = conversation.map { clipScores(model, it.samples) } + others.map { clipScores(model, it.samples) }
        val longTalk = mix(repeatTo(concat(conversation.map { it.samples }, gapMillis = 600), AudioSpec.SAMPLE_RATE * 600), road, 15f)
        val longSegments = streamScores(model, longTalk)

        println()
        println("## ${dir.name} (등록 기준 ${fmt(model.threshold)}) 호출어 ${pos.size}회, 비슷한 발음 ${sim.size}회, 다른 말 ${talk.size}회 + 대화 10분 구간 ${longSegments.size}개")
        println("| 방식 | 호출어 점수 중앙값 | 다른 말 최저 점수 | 다른 말 0회 기준에서 호출어 감지 | 그때 비슷한 발음 오인식 | 비슷한 발음 10% 이하 기준에서 호출어 감지 | 분리도(AUC: 호출어 vs 비슷한 발음) |")
        println("|---|---|---|---|---|---|---|")
        for ((label, pick) in listOf<Pair<String, (Pair<Float, Float>) -> Float>>("기존 (창 이동)" to { it.first }, "등록 방식" to { it.second })) {
            val p = pos.map(pick).sorted()
            val s = sim.map(pick).sorted()
            val negFloor = (talk.map(pick) + longSegments.map(pick)).min()
            val tTalk = Math.nextDown(negFloor)
            val tSim = s[(s.size * 0.1).toInt()].let(Math::nextDown) // 비슷한 발음의 10% 이하만 통과
            val auc = p.sumOf { a -> s.count { b -> a < b } + 0.5 * s.count { b -> a == b } } / (p.size * s.size.toDouble())
            fun rate(xs: List<Float>, t: Float) = "${fmt(100f * xs.count { it <= t } / xs.size)}%"
            println("| $label | ${fmt(p[p.size / 2])} | ${fmt(negFloor)} | ${rate(p, tTalk)} (기준 ${fmt(tTalk)}) | ${rate(s, tTalk)} | ${rate(p, minOf(tSim, tTalk))} | ${fmt(auc.toFloat())} |")
        }
    }
}

/** 클립 하나(앞뒤 실내 소음)를 판정 없이 흘려 (기존, 등록 방식) 최고 점수. */
private fun clipScores(model: WakeWordModel, clip: ShortArray, noise: ShortArray? = null, snrDb: Float = 0f): Pair<Float, Float> {
    var stream = concat(listOf(roomTone(800), clip, roomTone(1_000)), 0)
    if (noise != null) stream = mix(stream, ShortArray(stream.size) { noise[(clip.size * 7 + it) % noise.size] }, snrDb, reference = clip)
    val segments = streamScores(model, stream)
    return (segments.minOfOrNull { it.first } ?: Float.POSITIVE_INFINITY) to (segments.minOfOrNull { it.second } ?: Float.POSITIVE_INFINITY)
}

private fun streamScores(model: WakeWordModel, audio: ShortArray): List<Pair<Float, Float>> {
    val detector = com.mycarhub.wakeword.phone.AdaptiveWakeWordDetector(model, threshold = 0f)
    detector.compareScores = true
    val out = mutableListOf<Pair<Float, Float>>()
    detector.segmentListener = com.mycarhub.wakeword.phone.AdaptiveWakeWordDetector.SegmentListener { score, other, _, _ -> out += score to other }
    var pos = 0
    while (pos < audio.size) {
        val n = minOf(1_600, audio.size - pos)
        detector.process(audio.copyOfRange(pos, pos + n), n)
        pos += n
    }
    return out
}

// endregion

internal fun scoringDetail(root: File) {
    val dir = File(root, "bungbung")
    val model = (WakeWordTrainer().train(dir.name, loadDir(File(dir, "enroll")).map { asTake(it.samples) }) as TrainingResult.Success).model
    println("templates frames=${model.templates.map { it.size }}")
    for (sub in listOf("positive", "similar")) for (c in loadDir(File(dir, sub))) {
        val segs = streamScores(model, concat(listOf(roomTone(800), c.samples, roomTone(1_000)), 0))
        println("$sub/${c.name} ${AudioSpec.millisForSamples(c.samples.size)}ms segs=${segs.map { "${fmt(it.first)}/${fmt(it.second)}" }}")
    }
}
