package com.transcriber.app.doc

import android.util.Base64
import com.transcriber.app.data.Doc
import com.transcriber.app.data.Preset
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Сборка документа: кадры → images/, затем MD, HTML, DOCX и ZIP. */
object DocBuilder {
    fun ts(sec: Double): String {
        val s = sec.toInt()
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }

    fun load(out: File): Doc = Doc.fromJson(JSONObject(File(out, "document.json").readText()))

    fun save(doc: Doc, out: File) {
        File(out, "document.json").writeText(doc.toJson().toString(2))
    }

    fun markdown(doc: Doc, numbered: Boolean): String {
        val lines = mutableListOf("# ${doc.title}", "", doc.summary, "")
        doc.sections.forEachIndexed { i, sec ->
            val prefix = if (numbered) "${i + 1}. " else ""
            lines += listOf("## $prefix${sec.title}  ·  ${ts(sec.start)}", "")
            sec.images.forEach { lines += listOf("![${sec.title}](images/$it)", "") }
            lines += listOf(sec.body, "")
        }
        if (doc.appendix.isNotBlank()) lines += listOf(doc.appendix.trim(), "")
        return lines.joinToString("\n")
    }

    /** HTML с картинками внутри файла (base64) — один файл удобно пересылать с телефона. */
    fun html(doc: Doc, numbered: Boolean, imagesDir: File): String {
        val parts = StringBuilder()
        doc.sections.forEachIndexed { i, sec ->
            val prefix = if (numbered) "${i + 1}. " else ""
            val img = sec.images.joinToString("") { name ->
                val f = File(imagesDir, name)
                if (f.exists()) "<img src=\"data:image/jpeg;base64,${Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)}\" alt=\"\">" else ""
            }
            parts.append("<section><h2>$prefix${MdHtml.esc(sec.title)} <small>${ts(sec.start)}</small></h2>$img${MdHtml.body(sec.body)}</section>\n")
        }
        return """<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${MdHtml.esc(doc.title)}</title>
<style>
body{font:16px/1.6 system-ui,sans-serif;max-width:820px;margin:0 auto;padding:24px 16px;color:#1c2026}
h1{font-size:26px} h2{font-size:20px;margin-top:32px} h2 small{color:#6b7280;font-weight:400;font-size:13px}
img{max-width:100%;border:1px solid #e3e6eb;border-radius:8px;margin:8px 0}
.summary{color:#374151} code{background:#f1f3f5;padding:1px 5px;border-radius:4px}
table{border-collapse:collapse;width:100%;margin:12px 0;font-size:14px}
th,td{border:1px solid #e3e6eb;padding:6px 10px;text-align:left;vertical-align:top} th{background:#f6f7f9}
li{margin:3px 0} h3{font-size:17px}
</style></head><body>
<h1>${MdHtml.esc(doc.title)}</h1><div class="summary">${MdHtml.body(doc.summary)}</div>
$parts
${MdHtml.body(doc.appendix)}
</body></html>"""
    }

    fun zip(out: File) {
        ZipOutputStream(File(out, "document.zip").outputStream()).use { z ->
            for (name in listOf("document.docx", "document.md", "document.html")) {
                val f = File(out, name)
                if (f.exists()) { z.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            }
            File(out, "images").listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }?.forEach { f ->
                z.putNextEntry(ZipEntry("images/${f.name}")); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
    }

    /** Копирует выбранные кадры в images/ и собирает MD, HTML, DOCX, ZIP. */
    fun assemble(doc0: Doc, preset: Preset, out: File): Doc {
        val images = File(out, "images")
        images.deleteRecursively()
        images.mkdirs()
        val cand = File(out, "candidates")
        val doc = doc0.copy(sections = doc0.sections.map { sec ->
            val kept = sec.images.filter { name ->
                val src = File(cand, name)
                if (src.exists()) { src.copyTo(File(images, name), overwrite = true); true } else false
            }
            sec.copy(images = kept)
        })
        save(doc, out)
        File(out, "document.md").writeText(markdown(doc, preset.numbered))
        File(out, "document.html").writeText(html(doc, preset.numbered, images))
        Docx.build(doc, preset.numbered, images, File(out, "document.docx"))
        zip(out)
        return doc
    }

    /** Кадры-кандидаты + сборка документа из готовой структуры. */
    fun finalize(doc0: Doc, preset: Preset, video: File?, out: File, stage: (String) -> Unit): Doc {
        var doc = doc0.copy(sections = doc0.sections.sortedBy { it.start }.map { it.copy(candidates = emptyList(), images = emptyList()) })
        File(out, "candidates").deleteRecursively()
        if (preset.screenshots == "sections" && video != null && video.exists()) {
            stage("Извлечение кадров…")
            doc = Frames.extractCandidates(video, doc, File(out, "candidates")) { i, n -> stage("Извлечение кадров… ${i}/${n}") }
        }
        stage("Сборка документа…")
        return assemble(doc, preset, out)
    }

    /** Применяет выбор скриншотов: индекс раздела → кадры; кадры идут по времени. */
    fun applySelection(out: File, preset: Preset, choices: Map<Int, List<String>>): Doc {
        val doc = load(out)
        val sections = doc.sections.mapIndexed { idx, sec ->
            val names = choices[idx] ?: return@mapIndexed sec
            val byName = sec.candidates.associate { it.file to it.t }
            sec.copy(images = names.filter { it in byName }.distinct().sortedBy { byName[it] })
        }
        return assemble(doc.copy(sections = sections), preset, out)
    }
}
