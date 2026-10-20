package com.transcriber.app.work

import android.content.Context
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.transcriber.app.data.Models
import com.transcriber.app.data.Segment
import java.io.File
import java.util.concurrent.CancellationException

/** Кто говорит: сегментация pyannote + голосовые «отпечатки» + кластеризация (sherpa-onnx, офлайн). */
object Diarizer {
    data class Turn(val start: Double, val end: Double, val speaker: Int)

    const val SEGMENTATION_ASSET = "diarization/segmentation.onnx"
    const val MAX_SECONDS = 3 * 3600

    private fun segmentationFile(ctx: Context): File {
        val f = File(Models.dir(ctx), "pyannote-segmentation-3.0.onnx")
        if (!f.exists()) {
            ctx.assets.open(SEGMENTATION_ASSET).use { i -> f.outputStream().use { i.copyTo(it) } }
        }
        return f
    }

    fun run(
        ctx: Context,
        samples: FloatArray,
        numSpeakers: Int,
        threads: Int,
        progress: (Float) -> Unit,
        cancelled: () -> Boolean,
    ): List<Turn> {
        val embedding = Models.ensureSpeakerModel(ctx, progress, cancelled)
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(segmentationFile(ctx).path),
                numThreads = threads,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(model = embedding.path, numThreads = threads),
            clustering = FastClusteringConfig(
                numClusters = if (numSpeakers > 0) numSpeakers else -1,
                threshold = 0.5f,
            ),
            minDurationOn = 0.3f,
            minDurationOff = 0.5f,
        )
        val sd = OfflineSpeakerDiarization(null, config)
        try {
            val result = sd.processWithCallback(samples, { done, total, _ ->
                if (total > 0) progress(done.toFloat() / total)
                if (cancelled()) 1 else 0
            })
            if (cancelled()) throw CancellationException()
            return renumber(result.map { Turn(it.start.toDouble(), it.end.toDouble(), it.speaker) })
        } finally {
            sd.release()
        }
    }

    /** Спикеры нумеруются в порядке первого появления: «Спикер 1» — тот, кто заговорил первым. */
    fun renumber(turns: List<Turn>): List<Turn> {
        val order = LinkedHashMap<Int, Int>()
        turns.sortedBy { it.start }.forEach { if (it.speaker !in order) order[it.speaker] = order.size }
        return turns.map { it.copy(speaker = order[it.speaker] ?: it.speaker) }
    }

    /** Каждой фразе Whisper — спикер, который говорил дольше всего в её интервале (или ближайший). */
    fun assign(segs: List<Segment>, turns: List<Turn>): List<Segment> {
        if (turns.isEmpty()) return segs
        return segs.map { s ->
            val overlap = HashMap<Int, Double>()
            for (t in turns) {
                val o = minOf(s.end, t.end) - maxOf(s.start, t.start)
                if (o > 0) overlap[t.speaker] = (overlap[t.speaker] ?: 0.0) + o
            }
            val sp = overlap.maxByOrNull { it.value }?.key ?: turns.minBy { t ->
                val mid = (s.start + s.end) / 2
                minOf(kotlin.math.abs(t.start - mid), kotlin.math.abs(t.end - mid))
            }.speaker
            s.copy(speaker = sp)
        }
    }
}
