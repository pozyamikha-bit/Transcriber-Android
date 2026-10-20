@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.transcriber.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transcriber.app.app
import com.transcriber.app.data.Presets
import com.transcriber.app.data.Status
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun JobScreen(id: String, onBack: () -> Unit, onDocument: () -> Unit) {
    val ctx = LocalContext.current
    val mgr = ctx.app.jobs
    val jobs by mgr.jobs.collectAsState()
    val settings by ctx.app.settings.state.collectAsState()
    val job = jobs.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Int?>(null) }

    if (job == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val preset = Presets.get(ctx, job.preset)
    val dir = mgr.dir(id)

    fun import(text: String) {
        if (text.isBlank()) { toast(ctx, "Ответ пустой — скопируйте ответ Claude целиком"); return }
        importing = true
        scope.launch {
            try {
                val n = mgr.importReply(id, text)
                reply = ""
                toast(ctx, "Документ собран: разделов — $n")
            } catch (e: Exception) {
                toast(ctx, e.message ?: "Ошибка")
            } finally {
                importing = false
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(job.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            actions = {
                if (!job.isActive) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Удалить") }
            },
        )
    }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(preset.name, style = MaterialTheme.typography.titleSmall)
                        val info = buildList {
                            add("модель ${job.model}")
                            add(if (job.language.isEmpty()) "язык: авто" + (job.detectedLanguage?.let { " ($it)" } ?: "") else "язык: ${job.language}")
                            job.duration?.let { add("длительность ${fmtTime(it)}") }
                            if (job.segments.isNotEmpty()) add("фрагментов ${job.segments.size}")
                            if (job.quality == "accurate") add("точный режим")
                            job.speed?.let { add("обработано в %.1f× быстрее записи".format(it)) }
                        }
                        Text(info.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            job.message,
                            color = if (job.status == Status.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        job.speakerError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        job.postprocessError?.let {
                            Text("Ошибка обработки: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (job.isActive) {
                            if (job.status == Status.QUEUED) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                            OutlinedButton(onClick = { mgr.cancel(id) }) { Text("Отменить") }
                        }
                    }
                }
            }

            if (File(dir, "result.txt").exists()) {
                item {
                    Column {
                        Text("Транскрипт", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ExportChip("TXT", File(dir, "result.txt"), "${job.stem}.txt")
                            ExportChip("SRT", File(dir, "result.srt"), "${job.stem}.srt")
                            ExportChip("VTT", File(dir, "result.vtt"), "${job.stem}.vtt")
                            ExportChip("JSON", File(dir, "result.json"), "${job.stem}.json")
                        }
                    }
                }
            }

            if (job.speakers.isNotEmpty() && !job.isActive) {
                item {
                    Column {
                        Text("Спикеры", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Нажмите, чтобы указать имя — оно попадёт в файлы и в промпт для Claude.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            job.speakers.forEach { sp ->
                                val n = job.segments.count { it.speaker == sp }
                                AssistChip(
                                    onClick = { renaming = sp },
                                    label = { Text("${job.speakerName(sp)} · $n") },
                                    leadingIcon = { Box(Modifier.size(10.dp).background(speakerColor(sp), CircleShape)) },
                                )
                            }
                        }
                    }
                }
            }

            if (job.hasDocument) {
                item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Документ", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onDocument, modifier = Modifier.fillMaxWidth()) {
                        Text(if (preset.screenshots == "sections") "Открыть документ и выбрать скриншоты" else "Открыть документ")
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExportChip("Word", File(dir, "document.docx"), "${job.stem}.docx", !job.isActive)
                        ExportChip("Markdown", File(dir, "document.md"), "${job.stem}.md", !job.isActive)
                        ExportChip("HTML", File(dir, "document.html"), "${job.stem}.html", !job.isActive)
                        ExportChip("ZIP", File(dir, "document.zip"), "${job.stem}.zip", !job.isActive)
                    }
                } }
            }

            if (preset.usesClaude && job.segments.isNotEmpty() && job.status in setOf(Status.AWAITING, Status.DONE)) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (job.hasDocument) "Пересобрать документ через Claude" else "Собрать документ через Claude",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (preset.screenshots == "sections" && !mgr.videoAvailable(id)) {
                                Text("Исходное видео удалено — скриншоты недоступны.", color = MaterialTheme.colorScheme.error)
                            }
                            if (settings.apiKey.isNotBlank()) {
                                Button(onClick = { mgr.runClaude(id) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Обработать через Claude API")
                                }
                                Text("или вручную:", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "1. Скопируйте промпт и отправьте его в Claude.\n2. Скопируйте ответ Claude целиком.\n3. Нажмите «Вставить ответ из буфера».",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = {
                                    copyToClipboard(ctx, "Промпт", mgr.prompt(id)); toast(ctx, "Промпт скопирован")
                                }) { Text("Скопировать промпт") }
                                OutlinedButton(onClick = {
                                    val f = File(ctx.cacheDir, "prompt.txt").apply { writeText(mgr.prompt(id)) }
                                    shareFile(ctx, f, "${job.stem}-prompt.txt")
                                }) { Text("Промпт файлом") }
                                OutlinedButton(onClick = {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/new")))
                                }) { Text("Открыть Claude") }
                            }
                            Button(onClick = { import(readClipboard(ctx)) }, enabled = !importing, modifier = Modifier.fillMaxWidth()) {
                                Text(if (importing) "Обработка…" else "Вставить ответ из буфера")
                            }
                            OutlinedTextField(
                                value = reply, onValueChange = { reply = it },
                                label = { Text("…или вставьте ответ сюда") },
                                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                            )
                            if (reply.isNotBlank()) {
                                Button(onClick = { import(reply) }, enabled = !importing) { Text("Импортировать ответ") }
                            }
                        }
                    }
                }
            }

            if (job.segments.isNotEmpty()) {
                item { HorizontalDivider() }
                items(job.segments) { s ->
                    Column {
                    if (s.speaker >= 0) {
                        Text(
                            job.speakerName(s.speaker),
                            style = MaterialTheme.typography.labelMedium,
                            color = speakerColor(s.speaker),
                            modifier = Modifier.padding(start = 64.dp),
                        )
                    }
                    Row {
                        Text(
                            fmtTime(s.start),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(64.dp).padding(top = 2.dp),
                        )
                        SelectionContainer { Text(s.text, style = MaterialTheme.typography.bodyMedium) }
                    }
                    }
                }
            }
        }
    }

    val sp = renaming
    if (sp != null) {
        var name by remember(sp) { mutableStateOf(job.speakerNames[sp].orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Спикер ${sp + 1}") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Имя") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { mgr.renameSpeaker(id, sp, name); renaming = null }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Отмена") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить задачу?") },
            text = { Text("Будут удалены транскрипт, документ и сохранённое видео.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; mgr.delete(id) }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

private val SPEAKER_COLORS = listOf(
    androidx.compose.ui.graphics.Color(0xFF2F6FDE), androidx.compose.ui.graphics.Color(0xFFD9480F),
    androidx.compose.ui.graphics.Color(0xFF2B8A3E), androidx.compose.ui.graphics.Color(0xFF9C36B5),
    androidx.compose.ui.graphics.Color(0xFFC92A2A), androidx.compose.ui.graphics.Color(0xFF0B7285),
    androidx.compose.ui.graphics.Color(0xFF8F6B00), androidx.compose.ui.graphics.Color(0xFF5F3DC4),
)

fun speakerColor(n: Int) = SPEAKER_COLORS[n % SPEAKER_COLORS.size]
