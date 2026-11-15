package com.mycarhub.wakeword.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * MFCC 추출기 (16 kHz mono PCM16).
 *
 * 25 ms Hamming 창 / 10 ms 이동, 512점 FFT, 26개 mel 밴드, c1~c12.
 * c0(전체 에너지)는 말하는 크기에 따라 달라지므로 제외한다.
 */
class MfccExtractor(
    private val sampleRate: Int = AudioSpec.SAMPLE_RATE,
    private val frameLength: Int = AudioSpec.FRAME_LENGTH,
    private val frameShift: Int = AudioSpec.FRAME_SHIFT,
    private val fftSize: Int = 512,
    private val melBands: Int = 26,
    private val numCoeffs: Int = 12,
    lowHz: Double = 100.0,
    highHz: Double = 7_600.0,
) {
    val dimension: Int get() = numCoeffs

    private val bins = fftSize / 2 + 1
    private val window = DoubleArray(frameLength) { 0.54 - 0.46 * cos(2 * PI * it / (frameLength - 1)) }
    private val filters: Array<DoubleArray> = melFilterBank(lowHz, highHz)
    private val dct = Array(numCoeffs) { c ->
        DoubleArray(melBands) { b -> cos(PI * (c + 1) * (b + 0.5) / melBands) * sqrt(2.0 / melBands) }
    }
    private val lifter = DoubleArray(numCoeffs) { k -> 1 + LIFTER / 2.0 * sin(PI * (k + 1) / LIFTER) }

    fun frameCount(sampleCount: Int): Int =
        if (sampleCount < frameLength) 0 else 1 + (sampleCount - frameLength) / frameShift

    /**
     * @param noise 배경 소음의 mel 에너지 ([noiseProfile]). 주어지면 mel 영역 스펙트럼 차감으로 정상 소음(도로·공조)을 줄인다.
     */
    fun extract(samples: ShortArray, from: Int = 0, to: Int = samples.size, noise: DoubleArray? = null): Array<FloatArray> =
        cepstra(melEnergies(samples, from, to), noise)

    /** 구간들의 평균 mel 에너지. 프레임이 너무 적으면 null. */
    fun noiseProfile(samples: ShortArray, ranges: List<IntRange>): DoubleArray? {
        val sum = DoubleArray(melBands)
        var frames = 0
        for (range in ranges) {
            if (range.last + 1 - range.first < frameLength) continue
            for (frame in melEnergies(samples, range.first, range.last + 1)) {
                for (b in 0 until melBands) sum[b] += frame[b]
                frames++
            }
        }
        if (frames < MIN_NOISE_FRAMES) return null
        return DoubleArray(melBands) { sum[it] / frames }
    }

    private fun melEnergies(samples: ShortArray, from: Int, to: Int): Array<DoubleArray> {
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        val power = DoubleArray(bins)
        return Array(frameCount(to - from)) { f ->
            val start = from + f * frameShift
            re.fill(0.0)
            im.fill(0.0)
            var prev = if (start > from) samples[start - 1] / 32768.0 else 0.0
            for (i in 0 until frameLength) {
                val x = samples[start + i] / 32768.0
                re[i] = (x - PRE_EMPHASIS * prev) * window[i]
                prev = x
            }
            fft(re, im)
            for (k in 0 until bins) power[k] = re[k] * re[k] + im[k] * im[k]
            DoubleArray(melBands) { b ->
                var energy = 0.0
                val filter = filters[b]
                for (k in 0 until bins) energy += filter[k] * power[k]
                energy
            }
        }
    }

    private fun cepstra(mel: Array<DoubleArray>, noise: DoubleArray?): Array<FloatArray> {
        if (noise != null) {
            for (frame in mel) for (b in 0 until melBands) {
                frame[b] = max(frame[b] - OVER_SUBTRACTION * noise[b], SPECTRAL_FLOOR * noise[b])
            }
        }
        // 구간 최대 에너지보다 아주 작은 성분은 같은 바닥값으로 맞춘다 (조용한 녹음과 시끄러운 입력의 차이 축소)
        var peak = ENERGY_FLOOR
        for (frame in mel) for (e in frame) peak = max(peak, e)
        val floor = max(ENERGY_FLOOR, peak * DYNAMIC_RANGE)
        val logMel = DoubleArray(melBands)
        return Array(mel.size) { f ->
            for (b in 0 until melBands) logMel[b] = ln(max(mel[f][b], floor))
            FloatArray(numCoeffs) { c ->
                var sum = 0.0
                for (b in 0 until melBands) sum += dct[c][b] * logMel[b]
                (sum * lifter[c]).toFloat()
            }
        }
    }

    private fun melFilterBank(lowHz: Double, highHz: Double): Array<DoubleArray> {
        fun toMel(hz: Double) = 2595.0 * log10(1 + hz / 700.0)
        fun toHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1)
        val lowMel = toMel(lowHz)
        val highMel = toMel(highHz)
        val points = IntArray(melBands + 2) {
            val hz = toHz(lowMel + (highMel - lowMel) * it / (melBands + 1))
            floor((fftSize + 1) * hz / sampleRate).toInt().coerceIn(0, bins - 1)
        }
        return Array(melBands) { b ->
            val left = points[b]
            val center = points[b + 1]
            val right = points[b + 2]
            DoubleArray(bins) { k ->
                when {
                    k in left until center -> (k - left).toDouble() / max(1, center - left)
                    k in center..right -> (right - k).toDouble() / max(1, right - center)
                    else -> 0.0
                }
            }
        }
    }

    /** 반복 radix-2 복소 FFT (in-place). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wr = cos(angle)
            val wi = sin(angle)
            val half = len / 2
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr
                    im[b] = im[a] - vi
                    re[a] += vr
                    im[a] += vi
                    val next = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = next
                }
                i += len
            }
            len = len shl 1
        }
    }

    private companion object {
        const val PRE_EMPHASIS = 0.97
        const val LIFTER = 22
        const val ENERGY_FLOOR = 1e-10
        const val OVER_SUBTRACTION = 1.5
        const val SPECTRAL_FLOOR = 0.1
        const val DYNAMIC_RANGE = 1e-5 // 50 dB
        const val MIN_NOISE_FRAMES = 10
    }
}

/**
 * 템플릿과 감지 창이 함께 쓰는 특징 파이프라인: MFCC + delta (24차원), 구간별 평균·분산 정규화(CMVN).
 * CMVN은 마이크·차량 실내 음향 차이를 줄여 준다.
 */
class FeaturePipeline(private val mfcc: MfccExtractor = MfccExtractor()) {

    val dimension: Int get() = mfcc.dimension * 2

    fun noiseProfile(samples: ShortArray, ranges: List<IntRange>): DoubleArray? = mfcc.noiseProfile(samples, ranges)

    /** 정규화 전 특징. 긴 구간에서 여러 창을 잘라 쓸 때 한 번만 계산한다. */
    fun raw(samples: ShortArray, from: Int = 0, to: Int = samples.size, noise: DoubleArray? = null): Array<FloatArray> {
        val base = mfcc.extract(samples, from, to, noise)
        val dim = mfcc.dimension
        val last = base.size - 1
        return Array(base.size) { t ->
            FloatArray(dim * 2).also { out ->
                for (d in 0 until dim) {
                    out[d] = base[t][d]
                    var delta = 0f
                    for (n in 1..DELTA_WINDOW) {
                        delta += n * (base[minOf(t + n, last)][d] - base[maxOf(t - n, 0)][d])
                    }
                    out[dim + d] = delta / DELTA_DENOMINATOR
                }
            }
        }
    }

    fun normalize(frames: Array<FloatArray>, from: Int = 0, to: Int = frames.size): Array<FloatArray> {
        val count = to - from
        if (count <= 0) return emptyArray()
        val dim = frames[from].size
        val mean = DoubleArray(dim)
        val sq = DoubleArray(dim)
        for (t in from until to) for (d in 0 until dim) {
            val v = frames[t][d].toDouble()
            mean[d] += v
            sq[d] += v * v
        }
        val std = DoubleArray(dim)
        for (d in 0 until dim) {
            mean[d] /= count
            std[d] = sqrt(max(sq[d] / count - mean[d] * mean[d], VARIANCE_FLOOR))
        }
        return Array(count) { i ->
            val frame = frames[from + i]
            FloatArray(dim) { d -> ((frame[d] - mean[d]) / std[d]).toFloat() }
        }
    }

    fun compute(samples: ShortArray, from: Int = 0, to: Int = samples.size, noise: DoubleArray? = null): Array<FloatArray> =
        normalize(raw(samples, from, to, noise))

    private companion object {
        const val DELTA_WINDOW = 2
        const val DELTA_DENOMINATOR = 10f // 2 * (1² + 2²)
        const val VARIANCE_FLOOR = 1e-4
    }
}
