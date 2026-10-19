package com.transcriber.app.doc

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.transcriber.app.data.Candidate
import com.transcriber.app.data.Doc
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Кадры из видео: «устоявшиеся» экраны/слайды для каждого раздела документа. */
object Frames {
    private const val MAX_CANDIDATES = 6
    private const val STABLE = 0.003f   // кадр «устоялся»: с прошлой выборки изменилось < 0,3 % пикселей
    private const val NEW_STATE = 0.02f // новый экран/слайд: изменилось > 2 % пикселей относительно последнего кандидата
    private const val SW = 160
    private const val SH = 90

    fun changed(a: IntArray, b: IntArray): Float {
        var n = 0
        for (i in a.indices) if (abs(a[i] - b[i]) > 16) n++
        return n.toFloat() / a.size
    }

    private class Kept(val t: Double, val small: IntArray, val bmp: Bitmap)

    fun hasVideo(file: File): Boolean = try {
        VideoReader(file).close(); true
    } catch (_: Exception) {
        false
    }

    fun extractCandidates(video: File, doc: Doc, dir: File, progress: (Int, Int) -> Unit): Doc {
        dir.mkdirs()
        val reader = try {
            VideoReader(video)
        } catch (_: Exception) {
            return doc // нет видеодорожки или нечитаемый файл
        }
        reader.use { r ->
            var prevDefault: IntArray? = null
            val n = doc.sections.size
            val sections = doc.sections.mapIndexed { i, sec ->
                progress(i + 1, n)
                val t0 = sec.start
                val nxt = if (i + 1 < n) doc.sections[i + 1].start else t0 + 25
                val t1 = max(min(nxt - 0.3, t0 + 25), t0 + 3)
                val kept = try {
                    collect(r, t0, t1)
                } catch (_: Exception) {
                    // декодер не отдал кадры — берём один кадр через MediaMetadataRetriever
                    retrieverFrame(video, t0 + 1.5)?.let { listOf(Kept(t0 + 1.5, smallOf(it), it)) } ?: emptyList()
                }
                val cands = kept.mapIndexed { k, kp ->
                    val name = "s%02d_%d.jpg".format(i + 1, k + 1)
                    saveJpeg(kp.bmp, File(dir, name))
                    kp.bmp.recycle()
                    Candidate(name, (kp.t * 10).roundToInt() / 10.0)
                }
                var images = emptyList<String>()
                if (kept.isNotEmpty()) {
                    val first = kept[0].small
                    // тот же экран, что уже выбран для прошлого раздела, — по умолчанию без картинки
                    val pd = prevDefault
                    if (pd == null || changed(pd, first) >= NEW_STATE) {
                        images = listOf(cands[0].file)
                        prevDefault = first
                    }
                }
                sec.copy(candidates = cands, images = images)
            }
            return doc.copy(sections = sections)
        }
    }

    private fun collect(r: VideoReader, t0: Double, t1: Double): List<Kept> {
        val kept = mutableListOf<Kept>()
        var fallback: Kept? = null
        var prev: IntArray? = null
        var nextT = t0
        r.scan(max(t0 - 1, 0.0)) { ft, frame ->
            if (ft < nextT) return@scan true
            nextT = ft + 0.5
            val small = frame.small()
            if (fallback == null && ft >= t0 + 1.5) fallback = Kept(ft, small, frame.bitmap())
            val p = prev
            val stable = p != null && changed(p, small) < STABLE
            prev = small
            if (stable && ft >= t0 + 0.5 &&
                (kept.isEmpty() || (changed(kept.last().small, small) > NEW_STATE && ft - kept.last().t >= 2))
            ) {
                kept += Kept(ft, small, frame.bitmap())
                if (kept.size >= MAX_CANDIDATES) return@scan false
            }
            ft <= t1
        }
        val fb = fallback
        if (kept.isEmpty() && fb != null) kept += fb
        else fb?.bmp?.recycle()
        return kept
    }

    /** Кадр на произвольной секунде; возвращает время найденного кадра. */
    fun frameAt(video: File, t: Double, dest: File): Double {
        var bmp: Bitmap? = null
        var found = t
        try {
            VideoReader(video).use { r ->
                if (r.durationSec > 0 && t > r.durationSec) throw IllegalArgumentException("Секунда вне длительности видео")
                r.scan(max(t - 1, 0.0)) { ft, frame ->
                    if (ft >= t) {
                        bmp = frame.bitmap(); found = ft; false
                    } else true
                }
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            bmp = null
        }
        val b = bmp ?: retrieverFrame(video, t) ?: throw IllegalArgumentException("Кадр не найден")
        saveJpeg(b, dest)
        b.recycle()
        return found
    }

    private fun retrieverFrame(video: File, t: Double): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(video.path)
            mmr.getFrameAtTime((t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)?.let { scaleDown(it) }
        } catch (_: Exception) {
            null
        } finally {
            mmr.release()
        }
    }

    private fun smallOf(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, SW, SH, true)
        val px = IntArray(SW * SH)
        s.getPixels(px, 0, SW, 0, 0, SW, SH)
        if (s !== b) s.recycle()
        return IntArray(px.size) { val c = px[it]; (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10 }
    }

    private fun scaleDown(b: Bitmap): Bitmap {
        if (b.width <= 1280) return b
        val s = Bitmap.createScaledBitmap(b, 1280, (b.height * 1280f / b.width).roundToInt(), true)
        if (s !== b) b.recycle()
        return s
    }

    fun saveJpeg(b: Bitmap, f: File) {
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }
}

/** Кадр, полученный из декодера: даёт уменьшенную яркость для сравнения и цветную картинку. */
class DecodedFrame(private val image: Image, private val rotation: Int) {
    fun small(): IntArray {
        val crop = image.cropRect
        val y = image.planes[0]
        val buf = y.buffer
        val out = IntArray(160 * 90)
        for (j in 0 until 90) {
            val sy = crop.top + j * crop.height() / 90
            for (i in 0 until 160) {
                val sx = crop.left + i * crop.width() / 160
                out[j * 160 + i] = buf.get(sy * y.rowStride + sx * y.pixelStride).toInt() and 0xFF
            }
        }
        return out
    }

    fun bitmap(maxW: Int = 1280): Bitmap {
        val crop = image.cropRect
        val cw = crop.width()
        val ch = crop.height()
        val w = min(cw, maxW)
        val h = max(1, (ch.toLong() * w / cw).toInt())
        val yp = image.planes[0]; val up = image.planes[1]; val vp = image.planes[2]
        val yb = yp.buffer; val ub = up.buffer; val vb = vp.buffer
        val px = IntArray(w * h)
        for (j in 0 until h) {
            val sy = crop.top + j * ch / h
            val yRow = sy * yp.rowStride
            val uvRowU = (sy / 2) * up.rowStride
            val uvRowV = (sy / 2) * vp.rowStride
            for (i in 0 until w) {
                val sx = crop.left + i * cw / w
                val yy = (yb.get(yRow + sx * yp.pixelStride).toInt() and 0xFF) - 16
                val u = (ub.get(uvRowU + (sx / 2) * up.pixelStride).toInt() and 0xFF) - 128
                val v = (vb.get(uvRowV + (sx / 2) * vp.pixelStride).toInt() and 0xFF) - 128
                val c = 1192 * max(yy, 0)
                val r = ((c + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((c - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((c + 2066 * u) shr 10).coerceIn(0, 255)
                px[j * w + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        if (rotation == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }
}

/** Последовательное декодирование видеодорожки (быстрее, чем MediaMetadataRetriever на каждый кадр). */
class VideoReader(file: File) : Closeable {
    private val ex = MediaExtractor()
    private val codec: MediaCodec
    private val rotation: Int
    val durationSec: Double

    init {
        ex.setDataSource(file.path)
        var track = -1
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; break }
        }
        if (track < 0) { ex.release(); throw IOException("Нет видеодорожки") }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        durationSec = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
        rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        codec = try {
            MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!).also {
                it.configure(fmt, null, null, 0); it.start()
            }
        } catch (e: Exception) {
            ex.release(); throw IOException("Нет декодера видео", e)
        }
    }

    /** Декодирует кадры начиная с [fromSec]; [onFrame] возвращает false, чтобы остановиться. */
    fun scan(fromSec: Double, onFrame: (Double, DecodedFrame) -> Boolean) {
        codec.flush()
        ex.seekTo((fromSec * 1_000_000).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var idle = 0
        while (true) {
            if (!inDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance()
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            if (oi >= 0) {
                idle = 0
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                var go = true
                if (info.size > 0) {
                    val img = codec.getOutputImage(oi) ?: run {
                        codec.releaseOutputBuffer(oi, false); throw IOException("Декодер не отдаёт кадры")
                    }
                    try {
                        go = onFrame(info.presentationTimeUs / 1e6, DecodedFrame(img, rotation))
                    } finally {
                        img.close()
                        codec.releaseOutputBuffer(oi, false)
                    }
                } else codec.releaseOutputBuffer(oi, false)
                if (!go || eos) return
            } else if (inDone && ++idle > 300) return
        }
    }

    override fun close() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
        ex.release()
    }
}
