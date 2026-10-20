package com.transcriber.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Достаёт звук из любого аудио/видео, которое понимает Android (MediaExtractor + MediaCodec),
 * и пишет его в файл: 16 кГц, моно, 16-бит little-endian — формат, нужный Whisper.
 */
object AudioDecoder {
    const val RATE = 16000

    /** @return длительность звука в секундах */
    fun decode(src: File, out: File, progress: (Float) -> Unit, cancelled: () -> Boolean): Double {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(src.path)
        } catch (e: IOException) {
            ex.release()
            throw IOException("Формат файла не поддерживается", e)
        }
        var track = -1
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                track = i; break
            }
        }
        if (track < 0) {
            ex.release()
            throw IOException("В файле нет аудиодорожки")
        }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = try {
            MediaCodec.createDecoderByType(mime).also { it.configure(fmt, null, null, 0); it.start() }
        } catch (e: Exception) {
            ex.release()
            throw IOException("Нет декодера для звука $mime", e)
        }

        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val writer = PcmWriter(out)
        var resampler: Resampler? = null
        try {
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var lastReport = 0L
            while (!outDone) {
                if (cancelled()) throw CancellationException()
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    val cur = resampler
                    if (cur != null && cur.inRate != rate) resampler = Resampler(rate, writer::write)
                } else if (oi >= 0) {
                    val buf = codec.getOutputBuffer(oi)
                    if (buf != null && info.size > 0) {
                        var r = resampler
                        if (r == null) {
                            r = Resampler(rate, writer::write)
                            resampler = r
                        }
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        pushPcm(buf.slice().order(ByteOrder.LITTLE_ENDIAN), encoding, channels, r)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    if (durUs > 0 && info.presentationTimeUs - lastReport > 2_000_000) {
                        lastReport = info.presentationTimeUs
                        progress(min(1f, info.presentationTimeUs.toFloat() / durUs))
                    }
                }
            }
        } finally {
            writer.close()
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            ex.release()
        }
        return writer.count.toDouble() / RATE
    }

    private fun pushPcm(buf: ByteBuffer, encoding: Int, channels: Int, r: Resampler) {
        val ch = max(1, channels)
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buf.asFloatBuffer()
                val frames = fb.remaining() / ch
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until ch) s += fb.get(i * ch + c)
                    r.push(s / ch)
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val frames = buf.remaining() / ch
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until ch) s += ((buf.get(i * ch + c).toInt() and 0xFF) - 128) / 128f
                    r.push(s / ch)
                }
            }
            else -> {
                val sb = buf.asShortBuffer()
                val frames = sb.remaining() / ch
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until ch) s += sb.get(i * ch + c)
                    r.push(s / ch / 32768f)
                }
            }
        }
    }

    /** Читает отрезок PCM-файла [startSample, startSample+count) как float. */
    fun readSamples(pcm: File, startSample: Long, count: Int): FloatArray {
        val bytes = ByteArray(count * 2)
        RandomAccessFile(pcm, "r").use { f ->
            f.seek(startSample * 2)
            var read = 0
            while (read < bytes.size) {
                val n = f.read(bytes, read, bytes.size - read)
                if (n < 0) break
                read += n
            }
        }
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(count) { sb.get(it) / 32768f }
    }

    /** Весь PCM-файл как float, без промежуточной копии всего файла в байтах. */
    fun readAll(pcm: File): FloatArray {
        val total = (pcm.length() / 2).toInt()
        val out = FloatArray(total)
        val block = 1 shl 20
        var pos = 0
        while (pos < total) {
            val n = min(block, total - pos)
            readSamples(pcm, pos.toLong(), n).copyInto(out, pos)
            pos += n
        }
        return out
    }

    /**
     * Точка разреза между кусками: самое тихое полсекундное окно в последних [searchSec] секундах куска,
     * чтобы не резать слово пополам.
     */
    fun quietestCut(samples: FloatArray, searchSec: Int = 30): Int {
        val win = RATE / 2
        val step = RATE / 10
        val from = max(0, samples.size - searchSec * RATE)
        var best = samples.size
        var bestE = Double.MAX_VALUE
        var p = from
        while (p + win <= samples.size) {
            var e = 0.0
            var i = p
            while (i < p + win) { e += samples[i] * samples[i]; i += 4 }
            if (e < bestE) { bestE = e; best = p + win / 2 }
            p += step
        }
        return best
    }

    fun rms(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var e = 0.0
        for (s in samples) e += s * s
        return sqrt(e / samples.size)
    }
}

/** Пересчёт частоты в 16 кГц: усреднение по окну при понижении, линейная интерполяция при повышении. */
class Resampler(val inRate: Int, private val sink: (Float) -> Unit) {
    private val ratio = inRate.toDouble() / AudioDecoder.RATE
    private var acc = 0.0
    private var cnt = 0
    private var inIndex = 0L
    private var outIndex = 0L
    private var boundary = ratio
    private var prev = 0f
    private var started = false
    private var pos = 0.0

    fun push(x: Float) {
        when {
            inRate == AudioDecoder.RATE -> sink(x)
            ratio > 1.0 -> {
                acc += x; cnt++; inIndex++
                if (inIndex >= boundary) {
                    sink((acc / cnt).toFloat())
                    acc = 0.0; cnt = 0; outIndex++
                    boundary = (outIndex + 1) * ratio
                }
            }
            else -> {
                if (!started) {
                    started = true; prev = x; sink(x); pos = ratio; return
                }
                while (pos <= 1.0) {
                    sink(prev + (x - prev) * pos.toFloat()); pos += ratio
                }
                pos -= 1.0
                prev = x
            }
        }
    }
}

private class PcmWriter(file: File) {
    private val os = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val bb = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
    var count = 0L
        private set

    fun write(x: Float) {
        val v = (x.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        bb.putShort(v)
        count++
        if (!bb.hasRemaining()) flush()
    }

    private fun flush() {
        os.write(bb.array(), 0, bb.position())
        bb.clear()
    }

    fun close() {
        flush(); os.close()
    }
}
