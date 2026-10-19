package com.transcriber.app.work

import com.transcriber.app.data.Job
import com.transcriber.app.data.Segment
import java.io.File
import kotlin.math.roundToLong

object Subtitles {
    fun ts(seconds: Double, sep: Char): String {
        var ms = (seconds * 1000).roundToLong()
        val h = ms / 3_600_000; ms %= 3_600_000
        val m = ms / 60_000; ms %= 60_000
        val s = ms / 1000; ms %= 1000
        return "%02d:%02d:%02d%c%03d".format(h, m, s, sep, ms)
    }

    fun srt(segs: List<Segment>) = segs.mapIndexed { i, s ->
        "${i + 1}\n${ts(s.start, ',')} --> ${ts(s.end, ',')}\n${s.text}\n"
    }.joinToString("\n")

    fun vtt(segs: List<Segment>) = "WEBVTT\n\n" + segs.joinToString("\n") {
        "${ts(it.start, '.')} --> ${ts(it.end, '.')}\n${it.text}\n"
    }

    fun txt(segs: List<Segment>) = segs.joinToString("\n") { it.text }

    fun writeAll(segs: List<Segment>, dir: File) {
        File(dir, "result.srt").writeText(srt(segs))
        File(dir, "result.vtt").writeText(vtt(segs))
        File(dir, "result.txt").writeText(txt(segs))
        File(dir, "result.json").writeText(Job.segmentsToJson(segs).toString(2))
    }
}
