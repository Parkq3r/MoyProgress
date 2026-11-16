package ru.moyprogress.ui

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.moyprogress.TrainingViewModel
import ru.moyprogress.domain.Preferences
import ru.moyprogress.domain.ThemeChoice

@Composable
internal fun SettingsScreen(settings: Preferences, model: TrainingViewModel) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var backupUri by rememberSaveable { mutableStateOf<String?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        if (it != null) model.export(it)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) backupUri = it.toString()
    }

    Screen {
        Heading("Настройки")
        Panel {
            Text("Оформление", style = MaterialTheme.typography.titleLarge)
            val theme = ThemeChoice.entries.firstOrNull { it.name == settings.theme } ?: ThemeChoice.SYSTEM
            Choice("Тема", ThemeChoice.entries, theme, { it.title }) { choice ->
                model.updateSettings { it.copy(theme = choice.name) }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                Toggle("Цвета системы", settings.dynamic) { enabled ->
                    model.updateSettings { it.copy(dynamic = enabled) }
                }
            }
        }
        Panel {
            Text("Таймер отдыха", style = MaterialTheme.typography.titleLarge)
            Choice("Секунды", (15..600 step 15).toList(), settings.rest, { "$it с" }) { seconds ->
                model.updateSettings { it.copy(rest = seconds) }
            }
        }
        Panel {
            Toggle("Предлагать увеличение нагрузки", settings.progression) { enabled ->
                model.updateSettings { it.copy(progression = enabled) }
            }
            if (settings.progression) {
                Choice("Успешных тренировок подряд", (2..6).toList(), settings.streak, { it.toString() }) { n ->
                    model.updateSettings { it.copy(streak = n) }
                }
                Choice("Максимальная сложность (RPE)", (4..9).toList(), settings.maxEffort, { "$it из 10" }) { n ->
                    model.updateSettings { it.copy(maxEffort = n) }
                }
                Choice("Минимум дней между нагрузками", (1..4).toList(), settings.recoveryDays, { it.toString() }) { n ->
                    model.updateSettings { it.copy(recoveryDays = n) }
                }
                Text("Для подтягиваний нужно минимум 3 успешных тренировки, RPE до 7 и от 2 дней между нагрузками.")
            }
        }
        Panel {
            Text("Данные", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = { create.launch("MoyProgress-backup.json") },
                modifier = Modifier.fillMaxWidth()) { Text("Экспортировать данные") }
            OutlinedButton(onClick = { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth()) { Text("Импортировать данные") }
            TextButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Сбросить статистику", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (backupUri != null) {
        AlertDialog(
            onDismissRequest = { backupUri = null },
            title = { Text("Восстановить данные?") },
            text = { Text("Резервная копия заменит программы, упражнения, историю и черновик.") },
            confirmButton = {
                TextButton(onClick = {
                    backupUri?.let { model.import(Uri.parse(it)) }
                    backupUri = null
                }) { Text("Восстановить") }
            },
            dismissButton = { TextButton(onClick = { backupUri = null }) { Text("Отмена") } }
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить статистику?") },
            text = { Text("История, рекорды и черновик будут удалены. Программы и упражнения сохранятся.") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; model.reset() }) { Text("Сбросить") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } }
        )
    }
}
