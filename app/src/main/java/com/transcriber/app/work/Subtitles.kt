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

    private fun line(s: Segment, name: (Int) -> String) = if (s.speaker >= 0) "${name(s.speaker)}: ${s.text}" else s.text

    fun srt(segs: List<Segment>, name: (Int) -> String = { "" }) = segs.mapIndexed { i, s ->
        "${i + 1}\n${ts(s.start, ',')} --> ${ts(s.end, ',')}\n${line(s, name)}\n"
    }.joinToString("\n")

    fun vtt(segs: List<Segment>, name: (Int) -> String = { "" }) = "WEBVTT\n\n" + segs.joinToString("\n") {
        "${ts(it.start, '.')} --> ${ts(it.end, '.')}\n${line(it, name)}\n"
    }

    /** Без спикеров — фраза на строку; со спикерами — реплики подряд одного человека в один абзац. */
    fun txt(segs: List<Segment>, name: (Int) -> String = { "" }): String {
        if (segs.none { it.speaker >= 0 }) return segs.joinToString("\n") { it.text }
        val sb = StringBuilder()
        var cur = Int.MIN_VALUE
        for (s in segs) {
            if (s.speaker != cur) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                if (s.speaker >= 0) sb.append(name(s.speaker)).append(": ")
                cur = s.speaker
            } else sb.append(' ')
            sb.append(s.text)
        }
        return sb.append('\n').toString()
    }

    fun writeAll(job: Job, dir: File) {
        val segs = job.segments
        File(dir, "result.srt").writeText(srt(segs, job::speakerName))
        File(dir, "result.vtt").writeText(vtt(segs, job::speakerName))
        File(dir, "result.txt").writeText(txt(segs, job::speakerName))
        val json = Job.segmentsToJson(segs)
        for (i in 0 until json.length()) {
            val o = json.getJSONObject(i)
            if (o.has("speaker")) o.put("speaker_name", job.speakerName(o.getInt("speaker")))
        }
        File(dir, "result.json").writeText(json.toString(2))
    }
}
