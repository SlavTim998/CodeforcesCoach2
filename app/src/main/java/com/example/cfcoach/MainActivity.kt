package com.example.cfcoach

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class CoachViewModel : ViewModel() {
    var snapshot by mutableStateOf<Snapshot?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    fun load(handle: String, peers: String) {
        if (handle.isBlank()) { error = "Укажи ник Codeforces"; return }
        viewModelScope.launch {
            loading = true; error = ""
            try {
                val p = async { CfApi.profile(handle.trim()) }
                val c = async { CfApi.contests() }
                val s = async { CfApi.submissions(handle.trim()) }
                val r = async { CfApi.ratings(handle.trim()) }
                val t = async { CfApi.problems() }
                val handles = (peers.split(',', ';', '\n', ' ').map(String::trim).filter(String::isNotBlank) + handle.trim()).distinctBy(String::lowercase)
                // An invalid peer should not break the main profile dashboard.
                val m = async { runCatching { CfApi.members(handles) }.getOrDefault(emptyList()) }
                val profile = p.await(); val submissions = s.await(); val tasks = t.await()
                snapshot = Snapshot(profile, c.await(), m.await(), submissions, r.await(), tasks, Coach.analyze(profile, submissions, tasks))
            } catch (e: Exception) { error = e.message ?: "Ошибка сети" }
            finally { loading = false }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfcoach", MODE_PRIVATE)
        val request = PeriodicWorkRequestBuilder<ContestWatchWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("contest-watch", ExistingPeriodicWorkPolicy.KEEP, request)
        setContent {
            val vm: CoachViewModel = viewModel()
            var handle by remember { mutableStateOf(prefs.getString("handle", "") ?: "") }
            var peers by remember { mutableStateOf(prefs.getString("peers", "") ?: "") }
            var tab by remember { mutableIntStateOf(0) }
            val ctx = LocalContext.current
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                if (handle.isNotBlank()) vm.load(handle, peers)
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF78B8FF), secondary = Color(0xFF8BDEB1))) {
                Scaffold(topBar = { Surface(tonalElevation = 2.dp) { Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("CF Coach", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Codeforces • раунды, рейтинг, аналитика", style = MaterialTheme.typography.bodySmall)
                } } }) { inset ->
                    Column(Modifier.fillMaxSize().padding(inset)) {
                        OutlinedTextField(value = handle, onValueChange = { handle = it }, label = { Text("Твой handle") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                        OutlinedTextField(value = peers, onValueChange = { peers = it }, label = { Text("Ники группы через запятую") }, maxLines = 2,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                        Button(onClick = {
                            prefs.edit().putString("handle", handle.trim()).putString("peers", peers).apply()
                            vm.load(handle, peers)
                        }, enabled = !vm.loading, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Text(if (vm.loading) "Загружаю…" else "Обновить статистику")
                        }
                        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (vm.error.isNotBlank()) Text(vm.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                        PrimaryTabRow(selectedTabIndex = tab) {
                            listOf("Обзор", "Контесты", "Группа", "Задачи").forEachIndexed { index, name ->
                                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(name) })
                            }
                        }
                        val s = vm.snapshot
                        if (s == null && !vm.loading) Text("Введи ник и нажми «Обновить статистику».", Modifier.padding(20.dp))
                        if (s != null) LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (tab) {
                                0 -> {
                                    item { Info("${s.profile.handle}: ${s.profile.rating} (${s.profile.rank})", "Максимум: ${s.profile.maxRating}. Изучено последних посылок: ${s.submissions.size}.") }
                                    item { Info("Решено ${s.analysis.solved} из ${s.analysis.attempts}", "Доля задач хотя бы с одной AC: ${s.analysis.successRate}%") }
                                    item { RatingGraph(s.changes.map { it.new }) }
                                    item { Text("Тренировочный план", style = MaterialTheme.typography.titleLarge) }
                                    items(s.analysis.advice) { Info("Совет", it) }
                                    item { Text("Темы с высокой долей ошибок", style = MaterialTheme.typography.titleMedium) }
                                    items(s.analysis.weakTags) { (name, percent) -> Info(name, "$percent% задач по теме остались нерешёнными") }
                                }
                                1 -> {
                                    item { Text("Новые и предстоящие контесты", style = MaterialTheme.typography.titleLarge) }
                                    items(s.contests.filter { it.phase == "BEFORE" || it.phase == "CODING" }.sortedBy { it.start }.take(35)) { contest ->
                                        Info(contest.name, "${contest.phase} • ${date(contest.start)}", "Открыть") { open(ctx, "https://codeforces.com/contest/${contest.id}") }
                                    }
                                    item { Text("Последние изменения рейтинга", style = MaterialTheme.typography.titleLarge) }
                                    items(s.changes.takeLast(15).reversed()) { r ->
                                        val delta = r.new - r.old
                                        Info(r.name, "${r.old} → ${r.new} (${if (delta > 0) "+" else ""}$delta)", "Контест") { open(ctx, "https://codeforces.com/contest/${r.contestId}") }
                                    }
                                }
                                2 -> {
                                    item { Info("Сравнение с твоей группой", "Это рейтинг по указанным никам (официальный глобальный CF rating), а не standings приватной группы. Для закрытых контестов нужна авторизация Codeforces.") }
                                    items(s.members) { m -> Info("#${s.members.indexOf(m) + 1} ${m.handle}", "${m.rating} • ${m.rank} • максимум ${m.maxRating}", "Профиль") { open(ctx, "https://codeforces.com/profile/${m.handle}") } }
                                    if (s.members.isEmpty()) item { Text("Не удалось загрузить список участников. Проверь ники.") }
                                }
                                else -> {
                                    item { Info("Персональная подборка", "Целевая сложность около ${s.analysis.targetRating}. Задачи не имеют AC среди последних 1000 посылок.") }
                                    items(s.analysis.recommendations) { task ->
                                        Info("${task.contestId}${task.index} — ${task.name}", "${task.rating} • ${task.tags.take(3).joinToString()} • решали ${task.solvedCount}", "Решать") { open(ctx, task.url) }
                                    }
                                }
                            }
                            item { Text("Обновление в фоне: примерно раз в 15 минут, зависит от Android. Нажми кнопку для моментального обновления.", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}
private fun open(ctx: android.content.Context, url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
private fun date(seconds: Long): String = if (seconds <= 0) "Дата не указана" else SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(seconds * 1000))
@Composable private fun Info(title: String, description: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}
@Composable private fun RatingGraph(values: List<Int>) {
    Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("История рейтинга (${values.size} раундов)", style = MaterialTheme.typography.titleMedium)
        if (values.size < 2) Text("График появится после двух рейтинговых раундов") else {
            val min = values.minOrNull()!!.toFloat() - 40
            val span = ((values.maxOrNull()!!.toFloat() + 40) - min).coerceAtLeast(1f)
            val line = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(150.dp).padding(top = 12.dp)) {
                val path = Path()
                values.forEachIndexed { i, value ->
                    val x = i.toFloat() / (values.size - 1) * size.width
                    val y = size.height - (value.toFloat() - min) / span * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
                drawCircle(line, radius = 4.dp.toPx(), center = Offset(size.width, size.height - (values.last() - min) / span * size.height))
            }
        }
    } }
}
