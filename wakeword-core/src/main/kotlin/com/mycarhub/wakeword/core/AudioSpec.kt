package com.mycarhub.wakeword.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** 녹음·감지 공통 오디오 형식: 16 kHz, 16-bit, mono PCM. */
object AudioSpec {
    const val SAMPLE_RATE = 16_000

    /** 레벨 측정 단위 (10 ms). */
    const val LEVEL_FRAME = 160

    /** MFCC 분석 창 25 ms / 이동 10 ms. */
    const val FRAME_LENGTH = 400
    const val FRAME_SHIFT = 160

    const val MIN_DB = -120f

    fun samplesForMillis(millis: Int): Int = SAMPLE_RATE / 1000 * millis

    fun millisForSamples(samples: Int): Int = samples * 1000 / SAMPLE_RATE
}

/** dBFS 레벨 계산. */
object Levels {

    fun rmsDb(samples: ShortArray, from: Int = 0, to: Int = samples.size): Float {
        if (to <= from) return AudioSpec.MIN_DB
        var sum = 0.0
        for (i in from until to) {
            val x = samples[i].toDouble()
            sum += x * x
        }
        return toDb(sqrt(sum / (to - from)))
    }

    fun peakDb(samples: ShortArray, from: Int = 0, to: Int = samples.size): Float {
        var peak = 0
        for (i in from until to) peak = max(peak, kotlin.math.abs(samples[i].toInt()))
        return toDb(peak.toDouble())
    }

    /** 10 ms 단위 RMS 레벨 목록. */
    fun frameLevels(samples: ShortArray, from: Int = 0, to: Int = samples.size): FloatArray {
        val count = (to - from) / AudioSpec.LEVEL_FRAME
        return FloatArray(count) {
            val start = from + it * AudioSpec.LEVEL_FRAME
            rmsDb(samples, start, start + AudioSpec.LEVEL_FRAME)
        }
    }

    fun toDb(amplitude: Double): Float =
        if (amplitude <= 0.0) AudioSpec.MIN_DB
        else max(AudioSpec.MIN_DB, (20 * log10(amplitude / 32768.0)).toFloat())
}
