package com.transcriber.app.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

data class WhisperModel(val id: String, val title: String, val file: String, val sizeMb: Int)

/** Модели Whisper в формате ggml (квантованные — меньше и быстрее на телефоне). */
object Models {
    val ALL = listOf(
        WhisperModel("tiny", "tiny — очень быстро, грубо", "ggml-tiny-q5_1.bin", 31),
        WhisperModel("base", "base — быстро", "ggml-base-q5_1.bin", 57),
        WhisperModel("small", "small — баланс", "ggml-small-q5_1.bin", 181),
        WhisperModel("medium", "medium — точнее, медленно", "ggml-medium-q5_0.bin", 514),
        WhisperModel("large-v3-turbo", "large-v3-turbo — лучшее, очень медленно", "ggml-large-v3-turbo-q5_0.bin", 547),
    )
    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    private const val VAD_FILE = "ggml-silero-v5.1.2.bin"
    private const val VAD_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/$VAD_FILE"

    // CAM++ (3D-Speaker, обучена на китайской и английской речи): на тестах разделяет голоса
    // заметно надёжнее VoxCeleb-моделей; порог 0.5–0.7 даёт одинаковый результат
    private const val SPEAKER_FILE = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
    const val SPEAKER_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/$SPEAKER_FILE"
    const val SPEAKER_MB = 28

    fun speakerFile(ctx: Context) = File(dir(ctx), SPEAKER_FILE)

    /** Модель голосов для определения спикеров (скачивается при первом использовании). */
    fun ensureSpeakerModel(ctx: Context, progress: (Float) -> Unit, cancelled: () -> Boolean): File {
        val f = speakerFile(ctx)
        if (!f.exists()) download(SPEAKER_URL, f, progress, cancelled)
        return f
    }

    fun get(id: String) = ALL.firstOrNull { it.id == id } ?: ALL[2]

    fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context, m: WhisperModel) = File(dir(ctx), m.file)
    fun isDownloaded(ctx: Context, m: WhisperModel) = file(ctx, m).exists()
    fun vadFile(ctx: Context) = File(dir(ctx), VAD_FILE)

    fun ensure(ctx: Context, m: WhisperModel, progress: (Float) -> Unit, cancelled: () -> Boolean): File {
        val f = file(ctx, m)
        if (!f.exists()) download(BASE_URL + m.file, f, progress, cancelled)
        return f
    }

    /** VAD-модель маленькая (~1 МБ); если скачать не удалось — работаем без неё. */
    fun ensureVad(ctx: Context, cancelled: () -> Boolean): File? {
        val f = vadFile(ctx)
        if (f.exists()) return f
        return try {
            download(VAD_URL, f, {}, cancelled); f
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    fun delete(ctx: Context, m: WhisperModel) {
        file(ctx, m).delete()
    }

    private fun download(url: String, dest: File, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val part = File(dest.path + ".part")
        var conn: HttpURLConnection? = null
        try {
            var u = URL(url)
            // HuggingFace отдаёт файл через редирект на CDN
            repeat(5) {
                conn = (u.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Transcriber-Android")
                }
                val code = conn!!.responseCode
                if (code in 300..399) {
                    val loc = conn!!.getHeaderField("Location") ?: throw IOException("Редирект без адреса")
                    u = URL(u, loc)
                    conn!!.disconnect()
                } else {
                    if (code != 200) throw IOException("Ошибка загрузки модели: HTTP $code")
                    val total = conn!!.contentLengthLong
                    conn!!.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            var done = 0L
                            var last = 0L
                            while (true) {
                                if (cancelled()) throw CancellationException()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0 && done - last > 1_000_000) {
                                    last = done; progress(done.toFloat() / total)
                                }
                            }
                            if (total > 0 && done != total) throw IOException("Загрузка прервалась")
                        }
                    }
                    if (!part.renameTo(dest)) throw IOException("Не удалось сохранить модель")
                    return
                }
            }
            throw IOException("Слишком много редиректов")
        } catch (e: Exception) {
            part.delete()
            if (e is IOException && e.message?.startsWith("Ошибка") != true && e !is java.io.FileNotFoundException) {
                throw IOException("Не удалось скачать модель — проверьте интернет (${e.message})", e)
            }
            throw e
        } finally {
            conn?.disconnect()
        }
    }
}
