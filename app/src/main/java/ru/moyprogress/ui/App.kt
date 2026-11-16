package ru.moyprogress.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import ru.moyprogress.TrainingViewModel
import ru.moyprogress.domain.*

private enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Filled.Home),
    TRAINING("Тренировки", Icons.Filled.DateRange),
    PROGRESS("Прогресс", Icons.Filled.Star),
    SETTINGS("Настройки", Icons.Filled.Settings)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MoyProgress(model: TrainingViewModel) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val data by model.data.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.theme) {
        ThemeChoice.LIGHT.name -> false
        ThemeChoice.DARK.name -> true
        else -> systemDark
    }
    val scheme = when {
        settings.dynamic && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(
            primary = Color(0xFFBDCAFF), onPrimary = Color(0xFF142974),
            primaryContainer = Color(0xFF263E93), onPrimaryContainer = Color(0xFFDCE2FF),
            tertiary = Color(0xFF8FDBCB), surface = Color(0xFF121521)
        )
        else -> lightColorScheme(
            primary = Color(0xFF324DB1), onPrimary = Color.White,
            primaryContainer = Color(0xFFDDE4FF), onPrimaryContainer = Color(0xFF101C52),
            tertiary = Color(0xFF16695B), surface = Color(0xFFF9F9FF),
            surfaceContainer = Color(0xFFEEF0FA)
        )
    }
    MaterialExpressiveTheme(colorScheme = scheme) {
        val bars = remember { SnackbarHostState() }
        LaunchedEffect(model) { model.notices.collect { bars.showSnackbar(it) } }
        var tab by rememberSaveable { mutableStateOf(Tab.HOME.name) }
        val today by produceState(LocalDate.now().toEpochDay()) {
            while (true) {
                value = LocalDate.now().toEpochDay()
                delay(15_000L)
            }
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(bars) },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { target ->
                        NavigationBarItem(
                            selected = tab == target.name,
                            onClick = { tab = target.name },
                            icon = { Icon(target.icon, contentDescription = null) },
                            label = { Text(target.title, maxLines = 1) }
                        )
                    }
                }
            }
        ) { padding ->
            val current = data
            if (current == null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                AnimatedContent(
                    targetState = tab,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    label = "Раздел"
                ) { selected ->
                    when (Tab.valueOf(selected)) {
                        Tab.HOME -> Home(current, today, model) { tab = Tab.TRAINING.name }
                        Tab.TRAINING -> current.draft?.let { SessionScreen(it, model) }
                            ?: Programs(current, today, model)
                        Tab.PROGRESS -> ProgressScreen(current, today)
                        Tab.SETTINGS -> SettingsScreen(settings, model)
                    }
                }
            }
        }
    }
}

@Composable
internal fun Screen(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 620.dp).fillMaxWidth().fillMaxHeight()
                .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
internal fun Heading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content)
    }
}

@Composable
internal fun Hero(content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(contentColor = colors.onPrimaryContainer)
    ) {
        Column(
            Modifier.fillMaxWidth().background(Brush.linearGradient(
                listOf(colors.primaryContainer, colors.primaryContainer, colors.secondaryContainer)
            )).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
            content = content
        )
    }
}

internal val weekdays = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
private val dateFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))
private val fullDateFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ru"))
internal fun dateLabel(day: Long) = LocalDate.ofEpochDay(day).format(dateFormat)
internal fun fullDate(day: Long) = LocalDate.ofEpochDay(day).format(fullDateFormat)

@Composable
private fun Home(data: TrainingData, today: Long, model: TrainingViewModel, openTraining: () -> Unit) {
    val program = data.active
    val completed = data.sessions.filter { it.finishedAt != null && it.programId == program?.id }
    val planned = remember(data) { schedule(data) }
    fun finished(slot: ScheduledWorkout) = completed.any { it.workoutId == slot.workout.id && it.slot == slot.day }
    val current = planned.filter { it.day == today }
    val todaySlot = current.firstOrNull { !finished(it) }
    val next = planned.firstOrNull { it.day > today && !finished(it) }
    val week = program?.week(today) ?: 0
    val total = planned.count { it.week == week }
    val count = planned.count { it.week == week && finished(it) }
    val draft = data.draft

    Screen {
        Heading("Мой прогресс", fullDate(today).replaceFirstChar { it.uppercase() })
        Hero {
            Text("Сегодня", style = MaterialTheme.typography.labelLarge)
            Text(when {
                draft != null -> draft.label
                todaySlot != null -> todaySlot.workout.name
                current.isNotEmpty() -> "Тренировки выполнены"
                else -> "День отдыха"
            }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            if (todaySlot != null) Text("${todaySlot.workout.exercises.size} упр. · Неделя ${todaySlot.week}")
            Button(
                onClick = {
                    if (draft == null && todaySlot != null && program != null)
                        model.start(program.id, todaySlot.workout.id, today, todaySlot.day)
                    openTraining()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
            ) {
                Text(when {
                    draft != null -> "Продолжить тренировку"
                    todaySlot != null -> "Начать тренировку"
                    else -> "Открыть программу"
                })
            }
        }
        if (program != null) Panel {
            Text(program.name, style = MaterialTheme.typography.titleLarge)
            val phase = when {
                week == 0 -> "Старт ${dateLabel(program.start)}"
                week > program.weeks -> "Программа завершена по календарю"
                week == 4 && program.starter -> "Неделя 4 из 8 · облегчённая"
                week == 8 && program.starter -> "Неделя 8 из 8 · контроль"
                else -> "Неделя $week из ${program.weeks}"
            }
            Text(phase)
            if (total > 0) {
                LinearProgressIndicator(progress = { count.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                Text("Выполнено $count из $total", style = MaterialTheme.typography.bodySmall)
            }
        }
        Section("Следующая тренировка")
        Panel {
            if (next == null) Text("В календаре больше нет тренировок.")
            else {
                Text(next.workout.name, style = MaterialTheme.typography.titleMedium)
                Text(fullDate(next.day).replaceFirstChar { it.uppercase() } + " · Неделя " + next.week)
            }
        }
        Section("Последние рекорды")
        val personal = remember(data) { records(data).take(3) }
        if (personal.isEmpty()) Panel { Text("Новые рекорды появятся после записи результатов.") }
        personal.forEach { record ->
            Panel { Text(record.exercise.name, style = MaterialTheme.typography.titleMedium)
                Text("${record.value} ${record.exercise.unit} · ${dateLabel(record.day)}") }
        }
    }
}
