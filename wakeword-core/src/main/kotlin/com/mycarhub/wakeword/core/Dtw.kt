package com.mycarhub.wakeword.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dynamic Time Warping 거리.
 *
 * 프레임 비용은 코사인 거리(0~2), 누적 비용은 두 길이의 합으로 나눠 정규화한다.
 * 말 빠르기 차이는 허용하되 길이가 2배 넘게 차이 나면 다른 말로 보고 무한대를 돌려준다.
 */
object Dtw {

    const val INFINITE = Float.POSITIVE_INFINITY

    fun distance(a: Array<FloatArray>, b: Array<FloatArray>, bandRatio: Float = 0.2f): Float {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return INFINITE
        if (max(n, m) > 2 * min(n, m)) return INFINITE
        val ua = unit(a)
        val ub = unit(b)
        val band = max(MIN_BAND, ceil(bandRatio * max(n, m)).toInt()) + abs(n - m)
        var prev = FloatArray(m) { INFINITE }
        var cur = FloatArray(m)
        for (i in 0 until n) {
            cur.fill(INFINITE)
            val center = if (n == 1) 0 else (i.toLong() * (m - 1) / (n - 1)).toInt()
            val lo = max(0, center - band)
            val hi = min(m - 1, center + band)
            for (j in lo..hi) {
                val cost = 1f - dot(ua[i], ub[j])
                val best = if (i == 0 && j == 0) 0f else minOf(
                    if (i > 0) prev[j] else INFINITE,
                    if (j > 0) cur[j - 1] else INFINITE,
                    if (i > 0 && j > 0) prev[j - 1] else INFINITE,
                )
                cur[j] = cost + best
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[m - 1] / (n + m)
    }

    private fun unit(frames: Array<FloatArray>): Array<FloatArray> = Array(frames.size) { t ->
        val f = frames[t]
        var norm = 0.0
        for (v in f) norm += v * v
        val scale = if (norm > 0) (1 / sqrt(norm)).toFloat() else 0f
        FloatArray(f.size) { f[it] * scale }
    }

    private fun dot(x: FloatArray, y: FloatArray): Float {
        var s = 0f
        for (i in x.indices) s += x[i] * y[i]
        return s
    }

    private const val MIN_BAND = 3
}
