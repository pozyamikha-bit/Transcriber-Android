@file:OptIn(ExperimentalMaterial3Api::class)

package com.transcriber.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.transcriber.app.app
import com.transcriber.app.data.Models
import com.transcriber.app.data.Settings
import com.transcriber.app.whisper.WhisperLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val settings = ctx.app.settings
    val s by settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(s.apiKey) }
    var model by remember { mutableStateOf(s.claudeModel) }
    var showKey by remember { mutableStateOf(false) }
    var threads by remember { mutableStateOf(s.threads.toFloat()) }
    val downloads = remember { mutableStateMapOf<String, Float>() }
    var refresh by remember { mutableIntStateOf(0) }

    fun saveClaude() = settings.update { it.copy(apiKey = apiKey, claudeModel = model) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Настройки") },
            navigationIcon = { IconButton(onClick = { saveClaude(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Claude", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                label = { Text("API-ключ (необязательно)") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it },
                label = { Text("Модель Claude") },
                supportingText = { Text("По умолчанию ${Settings.DEFAULT_CLAUDE_MODEL}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "С ключом документы собираются автоматически. Без ключа приложение готовит промпт: его нужно отправить в Claude и вставить ответ. Ключ хранится только на этом телефоне.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { saveClaude(); toast(ctx, "Сохранено") }) { Text("Сохранить") }

            HorizontalDivider()
            Text("Распознавание", style = MaterialTheme.typography.titleMedium)
            Text("Потоков процессора: ${threads.toInt()}")
            Slider(
                value = threads,
                onValueChange = { threads = it },
                onValueChangeFinished = { settings.update { it.copy(threads = threads.toInt()) } },
                valueRange = 1f..Settings.maxThreads().toFloat(),
                steps = (Settings.maxThreads() - 2).coerceAtLeast(0),
            )
            Text(
                "По умолчанию — число производительных ядер (${Settings.defaultThreads()}). Если поставить больше, медленные энергоэффективные ядра обычно тормозят работу.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var vocab by remember { mutableStateOf(s.vocabulary) }
            OutlinedTextField(
                value = vocab, onValueChange = { vocab = it },
                label = { Text("Словарь: имена, термины, названия") },
                placeholder = { Text("Например: Битрикс24, Иван Петров, CRM") },
                supportingText = { Text("Через запятую. Помогает правильно распознать редкие слова.") },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { settings.update { it.copy(vocabulary = vocab) }; toast(ctx, "Словарь сохранён") }) { Text("Сохранить словарь") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Пропускать тишину (VAD)")
                    Text(
                        "Меньше «галлюцинаций» на паузах и музыке",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.useVad, onCheckedChange = { v -> settings.update { it.copy(useVad = v) } })
            }

            HorizontalDivider()
            Text("Модели Whisper", style = MaterialTheme.typography.titleMedium)
            Text(
                "Модель скачивается один раз (нужен интернет), дальше распознавание работает офлайн.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            key(refresh) {
                Models.ALL.forEach { m ->
                    val downloaded = Models.isDownloaded(ctx, m)
                    val p = downloads[m.id]
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.title)
                                    Text(
                                        if (downloaded) "скачана · ${Models.file(ctx, m).length() / 1_000_000} МБ" else "${m.sizeMb} МБ",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                when {
                                    p != null -> {}
                                    downloaded -> TextButton(onClick = { Models.delete(ctx, m); refresh++ }) { Text("Удалить") }
                                    else -> TextButton(onClick = {
                                        downloads[m.id] = 0f
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    Models.ensure(ctx, m, { v -> downloads[m.id] = v }, { false })
                                                }
                                            } catch (e: Exception) {
                                                toast(ctx, e.message ?: "Ошибка загрузки")
                                            } finally {
                                                downloads.remove(m.id); refresh++
                                            }
                                        }
                                    }) { Text("Скачать") }
                                }
                            }
                            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Модель голосов (спикеры)")
                        Text(
                            if (Models.speakerFile(ctx).exists()) "скачана · ${Models.speakerFile(ctx).length() / 1_000_000} МБ"
                            else "${Models.SPEAKER_MB} МБ — скачается при первом определении спикеров",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    key(refresh) {
                        if (Models.speakerFile(ctx).exists()) {
                            TextButton(onClick = { Models.speakerFile(ctx).delete(); refresh++ }) { Text("Удалить") }
                        }
                    }
                }
            }

            HorizontalDivider()
            val info = remember {
                try {
                    WhisperLib.loadBackends(ctx.applicationInfo.nativeLibraryDir)
                    WhisperLib.systemInfo()
                } catch (e: Throwable) {
                    "недоступно: ${e.message}"
                }
            }
            Text("Процессор: $info", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
