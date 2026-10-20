@file:OptIn(ExperimentalMaterial3Api::class)

package com.transcriber.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.transcriber.app.app
import com.transcriber.app.data.Models
import com.transcriber.app.data.Presets
import com.transcriber.app.data.Settings
import kotlinx.coroutines.launch

@Composable
fun NewJobScreen(initialUri: Uri?, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = ctx.app.settings.value
    var uri by remember { mutableStateOf(initialUri) }
    var preset by remember { mutableStateOf(s.preset) }
    var model by remember { mutableStateOf(s.model) }
    var lang by remember { mutableStateOf(s.language) }
    var quality by remember { mutableStateOf(s.quality) }
    var diarize by remember { mutableStateOf(s.diarize) }
    var numSpeakers by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val name = remember(uri) { uri?.let { ctx.app.jobs.displayName(it) } }
    val presets = remember { Presets.all(ctx) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) uri = it }

    fun start() {
        val u = uri ?: return
        busy = true
        ctx.app.settings.update { it.copy(preset = preset, model = model, language = lang, quality = quality, diarize = diarize) }
        scope.launch {
            try {
                val id = ctx.app.jobs.create(u, model, preset, lang, diarize, numSpeakers, quality)
                onCreated(id)
            } catch (e: Exception) {
                toast(ctx, e.message ?: "Ошибка")
            } finally {
                busy = false
            }
        }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Новая расшифровка") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedButton(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AudioFile, null)
                Spacer(Modifier.size(8.dp))
                Text(name ?: "Выбрать аудио или видео")
            }
            Dropdown("Режим", presets.map { it.id to it.name }, preset, { preset = it }, Modifier.fillMaxWidth())
            Text(
                presets.firstOrNull { it.id == preset }?.description.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Dropdown(
                "Модель распознавания",
                Models.ALL.map { m ->
                    m.id to m.title + if (Models.isDownloaded(ctx, m)) " ✓" else " (скачать ${m.sizeMb} МБ)"
                },
                model, { model = it }, Modifier.fillMaxWidth(),
            )
            Dropdown("Язык записи", LANGUAGES, lang, { lang = it }, Modifier.fillMaxWidth())
            Dropdown("Качество распознавания", Settings.QUALITY, quality, { quality = it }, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Определить спикеров")
                    Text(
                        if (Models.speakerFile(ctx).exists()) "Кто что сказал: «Спикер 1», «Спикер 2»… — потом можно переименовать"
                        else "Кто что сказал. При первом запуске скачается модель голосов (${Models.SPEAKER_MB} МБ)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = diarize, onCheckedChange = { diarize = it })
            }
            if (diarize) {
                Dropdown(
                    "Сколько человек говорит",
                    listOf(0 to "Определить автоматически") + (2..8).map { it to "$it" },
                    numSpeakers, { numSpeakers = it }, Modifier.fillMaxWidth(),
                )
            }
            if (presets.firstOrNull { it.id == preset }?.usesClaude == true) {
                Text(
                    if (s.apiKey.isBlank()) "Документ: без API-ключа приложение подготовит промпт — его нужно отправить в Claude и вставить ответ. Ключ можно указать в настройках."
                    else "Документ будет собран автоматически через Claude API.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else start()
                    },
                    enabled = uri != null && !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (busy) "Копирование файла…" else "Начать") }
                if (busy) {
                    Spacer(Modifier.size(12.dp))
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
            Text(
                "Распознавание идёт на телефоне и продолжается в фоне. Длинные записи на больших моделях могут обрабатываться дольше самой записи.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
