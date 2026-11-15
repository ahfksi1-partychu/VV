package com.mycarhub.wakeword.core

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM WAV 읽기·쓰기 (mono 저장, 읽을 때 stereo는 mono로 합친다). */
object WavIO {

    const val HEADER_BYTES = 44

    fun write(file: File, samples: ShortArray, sampleRate: Int = AudioSpec.SAMPLE_RATE) {
        val dataBytes = samples.size * 2
        val buffer = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(header(dataBytes, sampleRate))
        for (s in samples) buffer.putShort(s)
        file.writeBytes(buffer.array())
    }

    /** mono 16-bit PCM WAV 헤더. 길이를 나중에 아는 스트리밍 저장은 끝에서 이 헤더로 덮어쓴다. */
    fun header(dataBytes: Int, sampleRate: Int = AudioSpec.SAMPLE_RATE): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataBytes)
        return buffer.array()
    }

    class Audio(val samples: ShortArray, val sampleRate: Int)

    fun read(file: File): Audio {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() < 12 || tag(buffer) != "RIFF") throw IOException("Not a RIFF file: $file")
        buffer.int
        if (tag(buffer) != "WAVE") throw IOException("Not a WAVE file: $file")
        var channels = 0
        var sampleRate = 0
        var bits = 0
        while (buffer.remaining() >= 8) {
            val id = tag(buffer)
            val size = buffer.int
            val next = buffer.position() + size + (size and 1)
            when (id) {
                "fmt " -> {
                    val format = buffer.short.toInt()
                    channels = buffer.short.toInt()
                    sampleRate = buffer.int
                    buffer.int
                    buffer.short
                    bits = buffer.short.toInt()
                    if (format != 1 && format != -2) throw IOException("Unsupported WAV format $format")
                }
                "data" -> {
                    if (bits != 16 || channels !in 1..2) throw IOException("Need 16-bit mono/stereo PCM: $file")
                    val frames = minOf(size, buffer.remaining()) / (2 * channels)
                    val samples = ShortArray(frames) {
                        var sum = 0
                        repeat(channels) { sum += buffer.short }
                        (sum / channels).toShort()
                    }
                    return Audio(samples, sampleRate)
                }
            }
            buffer.position(minOf(next, buffer.limit()))
        }
        throw IOException("No data chunk: $file")
    }

    private fun tag(buffer: ByteBuffer): String {
        val bytes = ByteArray(4)
        buffer.get(bytes)
        return String(bytes, Charsets.US_ASCII)
    }
}
