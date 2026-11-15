package com.mycarhub.wakeword.eval

import com.mycarhub.wakeword.core.AudioSpec
import com.mycarhub.wakeword.core.Levels
import com.mycarhub.wakeword.core.TrainingResult
import com.mycarhub.wakeword.core.WakeWordTrainer
import com.mycarhub.wakeword.core.WavIO
import com.mycarhub.wakeword.phone.LiveCalibration
import java.io.File
import kotlin.math.pow
import kotlin.random.Random

/**
 * 등록 기준(발화 경계로 자른 비교)과 실시간 방식 기준([LiveCalibration])을 비교한다.
 * 인자: calibrate <데이터 폴더>
 */
fun calibrationCheck(root: File) {
    val rng = Random(11)
    fun roomTone(millis: Int): ShortArray {
        val raw = ShortArray(AudioSpec.samplesForMillis(millis)) { ((rng.nextFloat() * 2 - 1) * 8_000).toInt().toShort() }
        val g = 10.0.pow((-65.0 - Levels.rmsDb(raw)) / 20.0)
        return ShortArray(raw.size) { (raw[it] * g).toInt().toShort() }
    }
    val names = root.listFiles { f -> f.isDirectory && File(f, "enroll").isDirectory }!!.sortedBy { it.name }
    for (dir in names) {
        // 앱 녹음과 같이 3.5초: 앞 0.4초 실내 소음 + 발화 + 나머지 실내 소음
        val takes = File(dir, "enroll").listFiles { f -> f.extension == "wav" }!!.sortedBy { it.name }.map {
            val clip = WavIO.read(it).samples
            roomTone(400) + clip + roomTone(3_500 - 400 - AudioSpec.millisForSamples(clip.size))
        }
        val model = (WakeWordTrainer().train(dir.name, takes) as TrainingResult.Success).model
        val result = LiveCalibration.calibrate(model, takes)!!
        println(
            "${dir.name}: model threshold=%.3f live LOO=%s -> threshold=%.3f%s".format(
                result.modelThreshold,
                result.liveScores.map { "%.3f".format(it) },
                result.threshold,
                if (result.capped) " (capped)" else "",
            ),
        )
    }
}
