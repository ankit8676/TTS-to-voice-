package com.example.srtvoice

import java.io.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Small PCM WAV reader/writer for the common 16-bit mono/stereo PCM format emitted by Android TTS engines. */
data class PcmWav(val sampleRate: Int, val channels: Int, val samples: ShortArray)

object WavTools {
    fun read(file: File): PcmWav {
        val bytes = file.readBytes()
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") {
            "TTS output was not a PCM WAV file on this phone. Try another TTS engine."
        }
        var offset = 12
        var channels = 1
        var sampleRate = 22050
        var bits = 16
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4)
            val size = leInt(bytes, offset + 4).coerceAtLeast(0)
            val body = offset + 8
            if (id == "fmt " && size >= 16 && body + 16 <= bytes.size) {
                channels = leShort(bytes, body + 2)
                sampleRate = leInt(bytes, body + 4)
                bits = leShort(bytes, body + 14)
            } else if (id == "data") {
                dataOffset = body
                dataSize = min(size, bytes.size - body)
                break
            }
            offset = body + size + (size % 2)
        }
        require(dataOffset >= 0 && bits == 16 && (channels == 1 || channels == 2)) { "Only 16-bit PCM mono/stereo WAV is supported." }
        val count = dataSize / 2
        val pcm = ShortArray(count) { i -> leShort(bytes, dataOffset + i * 2).toShort() }
        return PcmWav(sampleRate, channels, pcm)
    }

    /** Fits by resampling (speeding up only) when the speech exceeds its subtitle slot. */
    fun fitToSlot(input: PcmWav, slotMs: Long): PcmWav {
        val targetFrames = max(1L, slotMs * input.sampleRate / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val sourceFrames = input.samples.size / input.channels
        if (sourceFrames <= targetFrames) return input
        val out = ShortArray(targetFrames * input.channels)
        for (frame in 0 until targetFrames) {
            val sourcePos = frame.toDouble() * sourceFrames / targetFrames
            val i0 = sourcePos.toInt().coerceAtMost(sourceFrames - 1)
            val i1 = min(i0 + 1, sourceFrames - 1)
            val frac = sourcePos - i0
            for (ch in 0 until input.channels) {
                val a = input.samples[i0 * input.channels + ch].toDouble()
                val b = input.samples[i1 * input.channels + ch].toDouble()
                out[frame * input.channels + ch] = (a + (b - a) * frac).roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return PcmWav(input.sampleRate, input.channels, out)
    }

    fun writeHeader(out: OutputStream, sampleRate: Int, channels: Int, dataBytes: Long) {
        val blockAlign = channels * 2
        out.write("RIFF".toByteArray(Charsets.US_ASCII)); writeInt(out, (36 + dataBytes).toInt())
        out.write("WAVEfmt ".toByteArray(Charsets.US_ASCII)); writeInt(out, 16)
        writeShort(out, 1); writeShort(out, channels); writeInt(out, sampleRate)
        writeInt(out, sampleRate * blockAlign); writeShort(out, blockAlign); writeShort(out, 16)
        out.write("data".toByteArray(Charsets.US_ASCII)); writeInt(out, dataBytes.toInt())
    }

    private fun leInt(b: ByteArray, i: Int) = (b[i].toInt() and 255) or ((b[i+1].toInt() and 255) shl 8) or ((b[i+2].toInt() and 255) shl 16) or ((b[i+3].toInt() and 255) shl 24)
    private fun leShort(b: ByteArray, i: Int) = (b[i].toInt() and 255) or ((b[i+1].toInt() and 255) shl 8)
    private fun writeInt(out: OutputStream, v: Int) { out.write(v and 255); out.write((v ushr 8) and 255); out.write((v ushr 16) and 255); out.write((v ushr 24) and 255) }
    private fun writeShort(out: OutputStream, v: Int) { out.write(v and 255); out.write((v ushr 8) and 255) }
}
