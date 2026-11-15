package com.mycarhub.wakeword

import com.mycarhub.wakeword.audio.PcmSource
import com.mycarhub.wakeword.core.AudioSpec
import com.mycarhub.wakeword.core.Levels

/**
 * 등록용 녹음 1회. 최대 [MAX_MILLIS] 동안 녹음하고, 말이 끝난 뒤 잠시 조용하면 일찍 멈춘다.
 * 호출 스레드를 막으므로 백그라운드 스레드에서 호출한다.
 */
class TakeRecorder {

    fun interface Progress {
        fun onLevel(levelDb: Float, elapsedMillis: Int)
    }

    sealed interface Result {
        class Recorded(val samples: ShortArray) : Result
        data object MicrophoneUnavailable : Result
        data object Cancelled : Result
    }

    fun record(source: PcmSource, progress: Progress, isCancelled: () -> Boolean): Result {
        if (!source.start()) return Result.MicrophoneUnavailable
        val maxSamples = AudioSpec.samplesForMillis(MAX_MILLIS)
        val out = ShortArray(maxSamples)
        val chunk = ShortArray(AudioSpec.samplesForMillis(CHUNK_MILLIS))
        var filled = 0
        var analyzed = 0
        val levels = ArrayList<Float>()
        var speechSeen = false
        var quietFrames = 0
        try {
            while (filled < maxSamples) {
                if (isCancelled()) return Result.Cancelled
                val n = source.read(chunk)
                if (n < 0) break
                val copy = minOf(n, maxSamples - filled)
                System.arraycopy(chunk, 0, out, filled, copy)
                filled += copy

                while (analyzed + AudioSpec.LEVEL_FRAME <= filled) {
                    levels += Levels.rmsDb(out, analyzed, analyzed + AudioSpec.LEVEL_FRAME)
                    analyzed += AudioSpec.LEVEL_FRAME
                }
                progress.onLevel(Levels.rmsDb(chunk, 0, copy), AudioSpec.millisForSamples(filled))

                // 처음 0.15초를 배경 소음 기준으로 삼아 말의 시작·끝을 판단한다
                if (levels.size > NOISE_FRAMES) {
                    val noise = levels.subList(0, NOISE_FRAMES).sorted()[NOISE_FRAMES / 2]
                    val threshold = maxOf(noise + 12f, -50f)
                    val level = levels.last()
                    if (level > threshold) {
                        speechSeen = true
                        quietFrames = 0
                    } else {
                        quietFrames++
                    }
                    val elapsed = AudioSpec.millisForSamples(filled)
                    if (speechSeen && quietFrames * 10 >= TRAILING_SILENCE_MILLIS && elapsed >= MIN_MILLIS) break
                }
            }
        } finally {
            source.stop()
        }
        return Result.Recorded(out.copyOf(filled))
    }

    companion object {
        const val MAX_MILLIS = 3_500
        const val MIN_MILLIS = 1_200
        const val TRAILING_SILENCE_MILLIS = 700
        const val CHUNK_MILLIS = 50
        private const val NOISE_FRAMES = 15
    }
}
