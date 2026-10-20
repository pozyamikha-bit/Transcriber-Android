package com.transcriber.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.transcriber.app.data.Models
import com.transcriber.app.data.Status
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SmokeTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val out = File(ctx.filesDir, "ci").apply { mkdirs() }

    private fun asset(name: String): File {
        val f = File(ctx.cacheDir, name)
        testCtx.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun waitIdle(id: String, timeoutMs: Long = 15 * 60_000): com.transcriber.app.data.Job {
        val t0 = System.currentTimeMillis()
        while (true) {
            val j = ctx.app.jobs.job(id)!!
            if (!j.isActive) return j
            if (System.currentTimeMillis() - t0 > timeoutMs) throw AssertionError("timeout: ${j.message}")
            Thread.sleep(1000)
        }
    }

    private fun log(s: String) {
        File(out, "result.txt").appendText(s + "\n")
        android.util.Log.i("SmokeTest", s)
    }

    @Test
    fun a_transcribeAudio() = runBlocking {
        val wav = asset("jfk.wav")
        val t = System.currentTimeMillis()
        val id = ctx.app.jobs.create(Uri.fromFile(wav), "tiny", "transcript", "en")
        val j = waitIdle(id)
        log("audio: status=${j.status} msg=${j.message} time=${(System.currentTimeMillis() - t) / 1000}s")
        log("audio text: " + j.segments.joinToString(" | ") { "[%.1f-%.1f] %s".format(it.start, it.end, it.text) })
        log("vad model present: ${Models.vadFile(ctx).exists()}")
        File(out, "audio_job.txt").writeText(id)
        assertEquals(j.message, Status.DONE, j.status)
        assertTrue(j.segments.joinToString(" ").lowercase().contains("country"))
        val dir = ctx.app.jobs.dir(id)
        assertTrue(File(dir, "result.srt").readText().contains("-->"))
    }

    @Test
    fun b_videoDocument() = runBlocking {
        val mp4 = asset("test.mp4")
        val id = ctx.app.jobs.create(Uri.fromFile(mp4), "tiny", "howto", "en")
        val j = waitIdle(id)
        log("video: status=${j.status} msg=${j.message} segs=${j.segments.size}")
        assertEquals(j.message, Status.AWAITING, j.status)
        val prompt = ctx.app.jobs.prompt(id)
        assertTrue(prompt.contains("ТРАНСКРИПТ"))
        val reply = """Вот документ:
```json
{"title":"Тестовая инструкция","summary":"Вводная **часть**.\n\n- пункт один\n- пункт два",
 "sections":[
  {"title":"Красный экран","body":"1. Открыть меню\n2. Нажать `OK`\n   - вложенный пункт\n- [ ] проверить","start":1},
  {"title":"Синий экран","body":"| Ошибка | Решение |\n|---|---|\n| A | B |","start":6}],
 "appendix":"## Открытые вопросы\n- вопрос"}
```"""
        val n = ctx.app.jobs.importReply(id, reply)
        assertEquals(2, n)
        var doc = ctx.app.jobs.document(id)!!
        log("candidates: " + doc.sections.map { s -> s.candidates.map { it.file to it.t } to s.images })
        assertTrue(doc.sections[0].candidates.isNotEmpty())
        assertTrue(doc.sections[1].candidates.isNotEmpty())
        doc = ctx.app.jobs.addFrame(id, 1, 8.0)
        val all = doc.sections[1].candidates.map { it.file }
        doc = ctx.app.jobs.applySelection(id, mapOf(1 to all))
        log("after selection: " + doc.sections.map { it.images })
        val dir = ctx.app.jobs.dir(id)
        for (n2 in listOf("document.docx", "document.md", "document.html", "document.zip")) {
            File(dir, n2).copyTo(File(out, n2), overwrite = true)
        }
        File(dir, "candidates").listFiles()?.forEach { it.copyTo(File(out, "cand_" + it.name), overwrite = true) }
        ZipFile(File(dir, "document.docx")).use { z -> assertTrue(z.getEntry("word/document.xml") != null) }
        File(out, "video_job.txt").writeText(id)
    }

    @Test
    fun c_modelUrls() {
        // все модели из списка действительно лежат на HuggingFace
        val urls = Models.ALL.map { "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${it.file}" } + listOf(
            "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin",
            Models.SPEAKER_URL,
        )
        val bad = urls.filter { u ->
            val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c.requestMethod = "HEAD"
            c.instanceFollowRedirects = false
            val code = c.responseCode
            c.disconnect()
            log("url $code $u")
            code !in 200..399
        }
        assertTrue("недоступны: $bad", bad.isEmpty())
    }

    private fun speakersRun(file: String, lang: String, num: Int): com.transcriber.app.data.Job = runBlocking {
        val wav = asset(file)
        val t = System.currentTimeMillis()
        val id = ctx.app.jobs.create(Uri.fromFile(wav), "tiny", "transcript", lang, diarize = true, numSpeakers = num, quality = "fast")
        val j = waitIdle(id)
        log("[$file n=$num] status=${j.status} err=${j.speakerError} time=${(System.currentTimeMillis() - t) / 1000}s speakers=${j.speakers}")
        log("  turns: " + File(ctx.app.jobs.dir(id), "speakers.json").let { if (it.exists()) it.readText() else "-" })
        log("  segs: " + j.segments.joinToString(" | ") { "[%.1f-%.1f S%d] %s".format(it.start, it.end, it.speaker, it.text.take(40)) })
        assertEquals(j.message, Status.DONE, j.status)
        assertEquals(null, j.speakerError)
        File(out, "speakers_job.txt").writeText(id)
        j
    }

    @Test
    fun d_speakersFour() {
        // эталонная запись sherpa-onnx: 4 человека
        val j = speakersRun("four_speakers.wav", "zh", 0)
        assertTrue("найдено спикеров: ${j.speakers}", j.speakers.size in 3..5)
        ctx.app.jobs.renameSpeaker(j.id, 0, "Анна")
        val txt = File(ctx.app.jobs.dir(j.id), "result.txt").readText()
        assertTrue(txt.startsWith("Анна: "))
    }

    @Test
    fun e_speakersSynthetic() {
        speakersRun("two_speakers.wav", "en", 0)
        speakersRun("two_speakers.wav", "en", 2)
    }
}
