package com.transcriber.app.work

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.transcriber.app.audio.AudioDecoder
import com.transcriber.app.claude.ClaudeClient
import com.transcriber.app.claude.Prompt
import com.transcriber.app.data.Candidate
import com.transcriber.app.data.Doc
import com.transcriber.app.data.Job
import com.transcriber.app.data.Models
import com.transcriber.app.data.Presets
import com.transcriber.app.data.Segment
import com.transcriber.app.data.Settings
import com.transcriber.app.data.Status
import com.transcriber.app.data.WhisperModel
import com.transcriber.app.doc.DocBuilder
import com.transcriber.app.doc.Frames
import com.transcriber.app.whisper.WhisperLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/** Очередь задач: скачивание модели → звук → Whisper → (Claude → кадры → документ). */
class JobManager(private val app: Context, private val settings: Settings) {
    private val root = File(app.filesDir, "jobs").apply { mkdirs() }
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val lock = Any()

    private var ctxPtr = 0L
    private var ctxModel: String? = null
    private var backendsLoaded = false

    init {
        load()
        scope.launch {
            for (id in queue) {
                runJob(id)
                if (queue.isEmpty) freeContext()
            }
        }
    }

    fun dir(id: String) = File(root, id)
    fun job(id: String) = _jobs.value.firstOrNull { it.id == id }

    private fun load() {
        val list = root.listFiles()?.mapNotNull { d ->
            val f = File(d, "job.json")
            if (!f.exists()) return@mapNotNull null
            try {
                var j = Job.fromJson(JSONObject(f.readText()))
                if (j.isActive) {
                    j = j.copy(status = Status.ERROR, message = "Прервано: приложение было закрыто")
                    f.writeText(j.toJson().toString())
                }
                j
            } catch (_: Exception) {
                null
            }
        }.orEmpty()
        _jobs.value = list.sortedByDescending { it.created }
    }

    private fun update(id: String, persist: Boolean = false, f: (Job) -> Job) {
        synchronized(lock) {
            _jobs.value = _jobs.value.map { if (it.id == id) f(it) else it }
        }
        if (persist) save(id)
    }

    private fun save(id: String) {
        val j = job(id) ?: return
        try {
            File(dir(id), "job.json").writeText(j.toJson().toString())
        } catch (_: IOException) {
        }
    }

    private fun startService() {
        ContextCompat.startForegroundService(app, Intent(app, TranscribeService::class.java))
    }

    fun displayName(uri: Uri): String {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    suspend fun create(
        uri: Uri, model: String, preset: String, language: String,
        diarize: Boolean = false, numSpeakers: Int = 0, quality: String = "balanced",
    ): String = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val d = dir(id).apply { mkdirs() }
        val ext = name.substringAfterLast('.', "").let { if (it.isNotEmpty() && it.length <= 5) ".$it" else "" }
        val input = "input$ext"
        try {
            app.contentResolver.openInputStream(uri)?.use { inp ->
                File(d, input).outputStream().use { inp.copyTo(it, 1 shl 16) }
            } ?: throw IOException("Не удалось открыть файл")
        } catch (e: Exception) {
            d.deleteRecursively()
            throw IOException("Не удалось прочитать файл: ${e.message}", e)
        }
        val job = Job(
            id = id, name = name, input = input, model = model, preset = preset,
            language = if (language == "auto") "" else language,
            diarize = diarize, numSpeakers = numSpeakers, quality = quality,
        )
        synchronized(lock) { _jobs.value = listOf(job) + _jobs.value }
        save(id)
        startService()
        queue.send(id)
        id
    }

    fun cancel(id: String) {
        val j = job(id) ?: return
        when (j.status) {
            Status.QUEUED -> update(id, true) { it.copy(status = Status.CANCELLED, message = "Отменено") }
            Status.PREPARING, Status.RUNNING, Status.POSTPROCESSING -> {
                cancelled += id
                WhisperLib.cancel()
            }
        }
    }

    fun delete(id: String): Boolean {
        val j = job(id) ?: return false
        if (j.isActive) return false
        dir(id).deleteRecursively()
        synchronized(lock) { _jobs.value = _jobs.value.filterNot { it.id == id } }
        return true
    }

    // ---------- распознавание ----------

    private fun context(model: WhisperModel, file: File): Long {
        if (ctxPtr != 0L && ctxModel == model.id) return ctxPtr
        freeContext()
        if (!backendsLoaded) {
            WhisperLib.loadBackends(app.applicationInfo.nativeLibraryDir)
            backendsLoaded = true
        }
        val p = WhisperLib.initContext(file.path)
        if (p == 0L) {
            file.delete()
            throw IOException("Не удалось загрузить модель ${model.id} (файл повреждён — он будет скачан заново)")
        }
        ctxPtr = p
        ctxModel = model.id
        return p
    }

    private fun freeContext() {
        if (ctxPtr != 0L) WhisperLib.freeContext(ctxPtr)
        ctxPtr = 0L
        ctxModel = null
    }

    private fun pct(p: Float) = "${(p * 100).toInt()}%"

    private fun runJob(id: String) {
        val j0 = job(id) ?: return
        if (j0.status != Status.QUEUED) return
        val d = dir(id)
        val input = File(d, j0.input)
        val pcm = File(d, "audio.pcm")
        val isCancelled = { id in cancelled }
        var keepVideo = false
        try {
            WhisperLib.resetCancel()
            val model = Models.get(j0.model)
            update(id, true) { it.copy(status = Status.PREPARING, message = "Подготовка…") }
            val modelFile = if (Models.isDownloaded(app, model)) Models.file(app, model) else Models.ensure(app, model, { p ->
                update(id) { it.copy(message = "Скачивание модели ${model.id}… ${pct(p)}", progress = p) }
            }, isCancelled)
            val vad = if (settings.value.useVad) Models.ensureVad(app, isCancelled) else null

            update(id) { it.copy(message = "Извлечение звука…", progress = 0f) }
            val dur = AudioDecoder.decode(input, pcm, { p ->
                update(id) { it.copy(message = "Извлечение звука… ${pct(p)}", progress = p) }
            }, isCancelled)
            if (dur < 0.2) throw IOException("В файле нет звука")
            update(id) { it.copy(duration = dur, message = "Загрузка модели в память…", progress = 0f) }
            val ctx = context(model, modelFile)
            if (isCancelled()) throw CancellationException()

            update(id, true) { it.copy(status = Status.RUNNING, message = "Распознавание…") }
            val t0 = System.currentTimeMillis()
            var segs = Quality.clean(transcribe(id, ctx, pcm, j0, vad, isCancelled))
            val lang = WhisperLib.detectedLanguage(ctx)
            update(id) { it.copy(segments = segs, detectedLanguage = lang.ifEmpty { null }) }
            if (j0.diarize && segs.isNotEmpty()) segs = diarize(id, pcm, dur, segs, isCancelled)
            pcm.delete()
            val secs = (System.currentTimeMillis() - t0) / 1000.0
            update(id, true) { it.copy(segments = segs, progress = 1f, speed = if (secs > 0) dur / secs else null) }
            Subtitles.writeAll(job(id)!!, d)

            val preset = Presets.get(app, j0.preset)
            keepVideo = preset.usesClaude && segs.isNotEmpty()
            if (keepVideo && settings.value.apiKey.isNotBlank()) {
                postprocess(id, isCancelled)
            } else if (keepVideo) {
                update(id, true) { it.copy(status = Status.AWAITING, message = "Ждёт ответа Claude") }
            } else {
                update(id, true) {
                    it.copy(status = Status.DONE, message = if (segs.isEmpty()) "Готово — речь не найдена" else "Готово")
                }
            }
        } catch (e: CancellationException) {
            update(id) { it.copy(status = Status.CANCELLED, message = "Отменено") }
        } catch (e: Throwable) {
            val msg = when (e) {
                is OutOfMemoryError -> "Не хватило памяти — выберите модель поменьше"
                else -> e.message ?: e.javaClass.simpleName
            }
            update(id) { it.copy(status = Status.ERROR, message = msg) }
        } finally {
            pcm.delete()
            cancelled.remove(id)
            if (!keepVideo) input.delete()
            save(id)
        }
    }

    private fun diarize(id: String, pcm: File, dur: Double, segs: List<Segment>, isCancelled: () -> Boolean): List<Segment> {
        val j = job(id)!!
        if (dur > Diarizer.MAX_SECONDS) {
            update(id) { it.copy(speakerError = "Запись длиннее 3 часов — спикеры не определялись") }
            return segs
        }
        return try {
            update(id) { it.copy(message = "Определение спикеров…", progress = 0f) }
            val samples = AudioDecoder.readAll(pcm)
            val turns = Diarizer.run(app, samples, j.numSpeakers, settings.value.threads.coerceIn(1, Settings.maxThreads()), { p ->
                update(id) { it.copy(message = "Определение спикеров… ${pct(p)}", progress = p) }
            }, isCancelled)
            // разметка по времени — для отладки и будущего пересчёта
            File(dir(id), "speakers.json").writeText(org.json.JSONArray().apply {
                turns.forEach { put(JSONObject().put("start", it.start).put("end", it.end).put("speaker", it.speaker)) }
            }.toString())
            Diarizer.assign(segs, turns)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val msg = if (e is OutOfMemoryError) "не хватило памяти" else (e.message ?: e.javaClass.simpleName)
            update(id) { it.copy(speakerError = "Спикеры не определены: $msg") }
            segs
        }
    }

    /** Переименовать спикера; файлы транскрипта пересобираются с новыми именами. */
    fun renameSpeaker(id: String, speaker: Int, name: String) {
        update(id, true) { j ->
            val names = j.speakerNames.toMutableMap()
            if (name.isBlank()) names.remove(speaker) else names[speaker] = name.trim()
            j.copy(speakerNames = names)
        }
        job(id)?.let { j -> if (!j.isActive && j.segments.isNotEmpty()) Subtitles.writeAll(j, dir(id)) }
    }

    private fun transcribe(
        id: String, ctx: Long, pcm: File, j0: Job, vad: File?, isCancelled: () -> Boolean,
    ): List<Segment> {
        val language = j0.language
        val q = Quality.params(j0.quality)
        val prompt = Quality.prompt(language, settings.value.vocabulary)
        val rate = AudioDecoder.RATE
        val total = pcm.length() / 2
        val chunk = rate * 60L * 10 // по 10 минут — не держим в памяти весь файл
        val segs = mutableListOf<Segment>()
        val threads = settings.value.threads.coerceIn(1, Settings.maxThreads())
        var start = 0L
        while (start < total) {
            if (isCancelled()) throw CancellationException()
            val remain = total - start
            val last = remain <= chunk + chunk / 5
            var samples = AudioDecoder.readSamples(pcm, start, (if (last) remain else chunk).toInt())
            if (!last) samples = samples.copyOf(AudioDecoder.quietestCut(samples))
            val len = samples.size
            val from = start
            val cb = object : WhisperLib.Callback {
                override fun onSegment(t0: Double, t1: Double, text: ByteArray) {
                    val t = String(text, Charsets.UTF_8).trim()
                    if (t.isEmpty()) return
                    val seg = Segment(t0, t1, t)
                    segs += seg
                    update(id) { it.copy(segments = it.segments + seg) }
                }

                override fun onProgress(progress: Int) {
                    val p = ((from + len * progress / 100.0) / total).toFloat()
                    update(id) { it.copy(progress = min(p, 1f), message = "Распознавание… ${pct(min(p, 1f))}") }
                }
            }
            if (AudioDecoder.rms(samples) > 0.0003) {
                val rc = WhisperLib.transcribe(
                    ctx, samples, language.ifEmpty { null }, threads, from.toDouble() / rate, vad?.path,
                    q.beamSize, q.bestOf, q.temperatureInc, prompt, cb,
                )
                if (rc == -200 || isCancelled()) throw CancellationException()
                if (rc != 0) throw IOException("Ошибка распознавания (код $rc)")
            }
            start += len
            val p = (start.toDouble() / total).toFloat()
            update(id) { it.copy(progress = p) }
        }
        return segs
    }

    // ---------- Claude и документ ----------

    private fun postprocess(id: String, isCancelled: () -> Boolean) {
        val j = job(id)!!
        val preset = Presets.get(app, j.preset)
        update(id, true) { it.copy(status = Status.POSTPROCESSING, message = "Структурирование через Claude…", postprocessError = null) }
        try {
            val s = settings.value
            val doc = ClaudeClient.structure(s.apiKey, s.claudeModel, j.segments, preset.prompt, isCancelled, j::speakerName)
            DocBuilder.finalize(doc, preset, File(dir(id), j.input), dir(id)) { msg -> update(id) { it.copy(message = msg) } }
            update(id, true) { it.copy(status = Status.DONE, message = "Готово", hasDocument = true) }
        } catch (e: CancellationException) {
            update(id, true) { it.copy(status = Status.AWAITING, message = "Ждёт ответа Claude") }
        } catch (e: Exception) {
            update(id, true) {
                it.copy(
                    status = if (it.hasDocument) Status.DONE else Status.AWAITING,
                    message = if (it.hasDocument) "Готово" else "Ждёт ответа Claude",
                    postprocessError = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    /** Обработать через Claude API задачу, которая ждёт ответа (или пересобрать документ). */
    fun runClaude(id: String) {
        val j = job(id) ?: return
        if (j.status !in setOf(Status.AWAITING, Status.DONE)) return
        update(id) { it.copy(status = Status.POSTPROCESSING, message = "Структурирование через Claude…") }
        startService()
        scope.launch {
            try {
                postprocess(id, { id in cancelled })
            } finally {
                cancelled.remove(id)
            }
        }
    }

    fun prompt(id: String): String {
        val j = job(id) ?: return ""
        return Prompt.build(j.segments, Presets.get(app, j.preset).prompt, j::speakerName)
    }

    fun videoAvailable(id: String): Boolean {
        val j = job(id) ?: return false
        return File(dir(id), j.input).exists()
    }

    /** Принимает ответ Claude (JSON), вырезает кадры и собирает документ. */
    suspend fun importReply(id: String, text: String) = withContext(Dispatchers.IO) {
        val j = job(id) ?: throw IllegalStateException("Задача не найдена")
        if (j.status !in setOf(Status.AWAITING, Status.DONE)) throw IllegalStateException("Задача сейчас не принимает ответ")
        val preset = Presets.get(app, j.preset)
        val video = File(dir(id), j.input)
        if (preset.screenshots == "sections" && !video.exists()) {
            throw IllegalStateException("Видео уже удалено — документ пересобрать нельзя")
        }
        val doc = Prompt.parseReply(text)
        val prev = j.status to j.message
        update(id) { it.copy(status = Status.POSTPROCESSING, message = "Обработка ответа…", postprocessError = null) }
        try {
            DocBuilder.finalize(doc, preset, video, dir(id)) { msg -> update(id) { it.copy(message = msg) } }
        } catch (e: Exception) {
            update(id) { it.copy(status = prev.first, message = prev.second) }
            throw e
        }
        update(id, true) { it.copy(status = Status.DONE, message = "Готово", hasDocument = true) }
        doc.sections.size
    }

    fun document(id: String): Doc? = try {
        DocBuilder.load(dir(id))
    } catch (_: Exception) {
        null
    }

    suspend fun applySelection(id: String, choices: Map<Int, List<String>>): Doc = withContext(Dispatchers.IO) {
        val j = job(id) ?: throw IllegalStateException("Задача не найдена")
        DocBuilder.applySelection(dir(id), Presets.get(app, j.preset), choices)
    }

    /** Добавляет в кандидаты раздела кадр на произвольной секунде видео. */
    suspend fun addFrame(id: String, section: Int, t: Double): Doc = withContext(Dispatchers.IO) {
        val j = job(id) ?: throw IllegalStateException("Задача не найдена")
        val video = File(dir(id), j.input)
        if (!video.exists()) throw IllegalStateException("Видео удалено")
        if (t < 0) throw IllegalArgumentException("Секунда не может быть отрицательной")
        val out = dir(id)
        val doc = DocBuilder.load(out)
        val sec = doc.sections.getOrNull(section) ?: throw IllegalArgumentException("Нет такого раздела")
        val name = "s%02d_t%d.jpg".format(section + 1, Math.round(t * 10))
        if (sec.candidates.any { it.file == name }) return@withContext doc
        val cand = File(out, "candidates").apply { mkdirs() }
        Frames.frameAt(video, t, File(cand, name))
        val newSec = sec.copy(candidates = (sec.candidates + Candidate(name, Math.round(t * 10) / 10.0)).sortedBy { it.t })
        val newDoc = doc.copy(sections = doc.sections.toMutableList().also { it[section] = newSec })
        DocBuilder.save(newDoc, out)
        newDoc
    }
}
