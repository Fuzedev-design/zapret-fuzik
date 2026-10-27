package com.fuzik.zapret

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --- Палитра, как в Windows-версии ---------------------------------------
private val Bg = Color(0xFF131318)
private val CardBg = Color(0xFF1E1E26)
private val CardHi = Color(0xFF262630)
private val TextMain = Color(0xFFECECF1)
private val Muted = Color(0xFF8E8E9C)
private val Accent = Color(0xFFD97757)
private val AccentSoft = Color(0xFF3A2620)
private val Green = Color(0xFF3ECF7F)
private val GreenSoft = Color(0xFF173527)
private val Red = Color(0xFFF0616D)
private val RedSoft = Color(0xFF3A1D22)

private val colors = darkColorScheme(
    primary = Accent, onPrimary = Color.White, background = Bg, surface = CardBg,
    onSurface = TextMain, onBackground = TextMain, surfaceVariant = CardHi, outline = CardHi,
)

@Composable
fun ZapretApp(onStart: () -> Unit, onStop: () -> Unit) {
    MaterialTheme(colorScheme = colors) {
        val ctx = LocalContext.current
        val prefs = remember { Prefs(ctx) }
        var tab by remember { mutableIntStateOf(0) }
        var update by remember { mutableStateOf<Updater.Update?>(null) }
        var showUpdateDialog by remember { mutableStateOf(false) }
        var crash by remember { mutableStateOf(CrashLog.take(ctx)) }

        LaunchedEffect(Unit) {
            if (prefs.checkUpdates) {
                update = runCatching { Updater.check() }.getOrNull()
                showUpdateDialog = update != null
            }
        }

        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF18181F)) {
                    listOf(
                        Triple("Главная", Icons.Rounded.Home, 0),
                        Triple("Проверка", Icons.Rounded.NetworkCheck, 1),
                        Triple("Настройки", Icons.Rounded.Settings, 2),
                    ).forEach { (label, icon, i) ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { Icon(icon, null) }, label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Accent, selectedTextColor = TextMain,
                                indicatorColor = AccentSoft, unselectedIconColor = Muted, unselectedTextColor = Muted,
                            ),
                        )
                    }
                }
            },
        ) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Header()
                when (tab) {
                    0 -> HomeScreen(prefs, onStart, onStop, update) { showUpdateDialog = true }
                    1 -> CheckScreen(prefs, onStart)
                    2 -> SettingsScreen(prefs, onUpdateFound = { update = it; showUpdateDialog = it != null })
                }
            }
        }

        val u = update
        if (showUpdateDialog && u != null && crash == null) UpdateDialog(u) { showUpdateDialog = false }
        crash?.let { CrashDialog(it) { crash = null } }
    }
}

// --- Общие элементы --------------------------------------------------------

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Запрет", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("от Фузика", color = Accent, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ZCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) Text(title.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun Pill(text: String, fg: Color, bg: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.width(7.dp))
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BigButton(text: String, primary: Boolean = true, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Accent else CardHi, contentColor = if (primary) Color.White else TextMain,
            disabledContainerColor = CardHi, disabledContentColor = Color(0xFF5C5C68),
        ),
        modifier = modifier.height(50.dp),
    ) { Text(text, fontSize = 15.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal) }
}

@Composable
private fun SettingRow(title: String, desc: String?, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontWeight = FontWeight.SemiBold)
            if (desc != null) Text(desc, color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

@Composable
private fun ZSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked = checked, onCheckedChange = onChange,
    colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.White,
        uncheckedTrackColor = Color(0xFF3A3A46), uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(value: String, options: List<String>, enabled: Boolean = true, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, enabled = enabled, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CardHi, unfocusedContainerColor = CardHi, disabledContainerColor = CardHi,
                focusedBorderColor = Accent, unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent,
                focusedTextColor = TextMain, unfocusedTextColor = TextMain,
            ),
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = CardHi) {
            options.forEach {
                DropdownMenuItem(text = { Text(it, color = TextMain) }, onClick = { open = false; onPick(it) })
            }
        }
    }
}

// --- Главная ---------------------------------------------------------------

@Composable
private fun HomeScreen(prefs: Prefs, onStart: () -> Unit, onStop: () -> Unit, update: Updater.Update?, onUpdate: () -> Unit) {
    val ctx = LocalContext.current
    val running by VpnState.running.collectAsState()
    val error by VpnState.error.collectAsState()
    var strategy by remember { mutableStateOf(prefs.strategy) }
    var autostart by remember { mutableStateOf(prefs.autostart) }

    if (update != null) {
        ZCard {
            SettingRow("Доступно обновление", "Версия ${update.tag.trimStart('v')}") {
                TextButton(onClick = onUpdate) { Text("Обновить", color = Accent) }
            }
        }
    }

    ZCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (running) "Обход включён" else "Обход выключен", color = if (running) Green else TextMain,
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (running) Pill("Работает", Green, GreenSoft) else Pill("Выключен", Red, RedSoft)
        }
        Text(
            if (running) "Discord, YouTube и другие сайты открываются через обход. Работает и в фоне."
            else "Выберите стратегию и нажмите «Запустить». Если сайты не открываются — попробуйте другую или подберите на вкладке «Проверка».",
            color = Muted, fontSize = 13.sp,
        )
        if (error != null) Text(error!!, color = Red, fontSize = 13.sp)
        Text("Стратегия", color = Muted, fontSize = 12.sp)
        Picker(strategy, STRATEGIES.map { it.name } + CUSTOM_STRATEGY) {
            strategy = it
            prefs.strategy = it
            if (running) ZapretVpnService.start(ctx)  // применить сразу
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(if (running) "Перезапустить" else "Запустить", modifier = Modifier.weight(1f), onClick = onStart)
            BigButton("Остановить", primary = false, enabled = running, modifier = Modifier.weight(0.7f), onClick = onStop)
        }
    }

    ZCard("Автозапуск") {
        SettingRow("После перезагрузки", "Включать обход при включении телефона") {
            ZSwitch(autostart) { autostart = it; prefs.autostart = it }
        }
        Text("Надёжнее всего — включить «Постоянную VPN» для этого приложения в настройках Android.",
            color = Muted, fontSize = 12.sp)
        TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }) {
            Text("Открыть настройки VPN", color = Accent)
        }
    }
}

// --- Проверка --------------------------------------------------------------

@Composable
private fun CheckScreen(prefs: Prefs, onStart: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by VpnState.running.collectAsState()
    var checking by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Map<String, List<ProbeResult>>?>(null) }
    var checkedAt by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var progressText by remember { mutableStateOf("") }
    var best by remember { mutableStateOf<List<StrategyScore>?>(null) }

    ZCard {
        Text("Доступность сайтов", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(checkedAt ?: "Проверьте, что открывается прямо сейчас", color = Muted, fontSize = 12.sp)
        CHECK_TARGETS.keys.forEach { name ->
            val rows = results?.get(name)
            val ok = rows?.filter { it.status == Probe.OK }?.mapNotNull { it.ms }
            val (color, text) = when {
                rows == null -> CardHi to if (checking) "проверяю..." else "—"
                ok!!.size == rows.size -> Green to "работает · ${ok.sum() / ok.size} мс"
                ok.isNotEmpty() -> Accent to "частично: ${ok.size} из ${rows.size}"
                else -> Red to "не открывается — ${rows.first().status.text}"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(10.dp))
                Text(name, color = TextMain, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(text, color = if (rows == null) Muted else color, fontSize = 13.sp)
            }
        }
        BigButton("Проверить сейчас", enabled = !checking && !searching, modifier = Modifier.fillMaxWidth()) {
            checking = true
            results = null
            scope.launch {
                // наше приложение идёт мимо VPN, поэтому при включённом обходе проверяем через byedpi
                results = Checker.checkServices(socksPort = if (running) MAIN_PORT else null)
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                checkedAt = "Проверено в $time · ${if (running) "с обходом" else "без обхода"}"
                checking = false
            }
        }
    }

    ZCard {
        Text("Подбор стратегии", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(if (searching) progressText else "Перепробует все стратегии (около минуты) и найдёт лучшую. Обход при этом не прерывается.",
            color = Muted, fontSize = 12.sp)
        if (searching) {
            LinearProgressIndicator(progress = { progress }, color = Accent, trackColor = CardHi,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
        }
        BigButton(if (searching) "Остановить подбор" else "Подобрать автоматически", primary = searching.not(),
            enabled = !checking, modifier = Modifier.fillMaxWidth()) {
            if (searching) { cancel = true; return@BigButton }
            searching = true
            cancel = false
            best = null
            scope.launch {
                best = Checker.findBest(ctx, { i, n, name ->
                    progress = i.toFloat() / n
                    progressText = "Проверяю ${i + 1} из $n: $name"
                }, { cancel })
                searching = false
            }
        }
        best?.let { list ->
            val working = list.filter { it.ok > 0 }
            if (working.isEmpty()) {
                Text("Ни одна стратегия не помогла. Проверьте интернет и включите «Частный DNS» (dns.google) в настройках Android.",
                    color = Red, fontSize = 13.sp)
            }
            working.take(4).forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.  ${s.strategy.name}", color = TextMain,
                        fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    Text("${s.ok}/${s.total}" + (s.ms?.let { " · $it мс" } ?: ""),
                        color = if (s.ok == s.total) Green else Accent, fontSize = 13.sp)
                }
            }
            if (working.isNotEmpty()) {
                BigButton("Применить «${working.first().strategy.name}»", modifier = Modifier.fillMaxWidth()) {
                    prefs.strategy = working.first().strategy.name
                    onStart()
                }
            }
        }
    }
}

// --- Настройки -------------------------------------------------------------

@Composable
private fun SettingsScreen(prefs: Prefs, onUpdateFound: (Updater.Update?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by VpnState.running.collectAsState()
    var custom by remember { mutableStateOf(prefs.customArgs) }
    var dns by remember { mutableStateOf(prefs.dns) }
    var checkUpdates by remember { mutableStateOf(prefs.checkUpdates) }
    var updateText by remember { mutableStateOf<String?>(null) }

    ZCard("Своя стратегия") {
        Text("Параметры byedpi для стратегии «$CUSTOM_STRATEGY»", color = Muted, fontSize = 12.sp)
        OutlinedTextField(
            value = custom, onValueChange = { custom = it }, singleLine = false, shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = CardHi, unfocusedContainerColor = CardHi,
                focusedBorderColor = Accent, unfocusedBorderColor = Color.Transparent,
                focusedTextColor = TextMain, unfocusedTextColor = TextMain),
            modifier = Modifier.fillMaxWidth(),
        )
        BigButton("Сохранить и включить", modifier = Modifier.fillMaxWidth()) {
            prefs.customArgs = custom.trim()
            prefs.strategy = CUSTOM_STRATEGY
            if (running) ZapretVpnService.start(ctx)
        }
    }

    ZCard("Сеть") {
        Text("DNS-сервер внутри обхода", color = Muted, fontSize = 12.sp)
        val options = listOf("8.8.8.8", "1.1.1.1", "77.88.8.8", "9.9.9.9")
        Picker(dns, options) {
            dns = it
            prefs.dns = it
            if (running) ZapretVpnService.start(ctx)
        }
    }

    ZCard("Программа") {
        SettingRow("Проверять обновления", "При открытии приложения, с GitHub") {
            ZSwitch(checkUpdates) { checkUpdates = it; prefs.checkUpdates = it }
        }
        SettingRow("Версия ${BuildConfig.VERSION_NAME}", updateText) {
            TextButton(onClick = {
                updateText = "Проверяю..."
                scope.launch {
                    val u = runCatching { Updater.check() }
                    updateText = when {
                        u.isFailure -> "Не удалось проверить"
                        u.getOrNull() == null -> "Установлена последняя версия"
                        else -> "Доступна ${u.getOrNull()!!.tag.trimStart('v')}"
                    }
                    onUpdateFound(u.getOrNull())
                }
            }) { Text("Проверить", color = Accent) }
        }
    }

    ZCard("О программе") {
        Text("Оболочка для byedpi (hufrea) и hev-socks5-tunnel (heiher). Весь трафик обрабатывается на телефоне, " +
            "сторонних серверов нет. Telegram работает через обход автоматически.", color = Muted, fontSize = 12.sp)
        TextButton(onClick = { openUrl(ctx, "https://github.com/Fuzedev-design/zapret-fuzik") }) {
            Text("Страница проекта на GitHub", color = Accent)
        }
    }
}

private fun openUrl(ctx: Context, url: String) =
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

@Composable
private fun CrashDialog(text: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = CardBg,
        title = { Text("Приложение закрылось с ошибкой", color = TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Скопируйте текст и пришлите разработчику — так ошибку быстрее исправят.", color = Muted)
                Text(text.take(1500), color = Muted, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(CardHi).padding(10.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                onClose()
            }) { Text("Скопировать", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Закрыть", color = Muted) } },
    )
}

@Composable
private fun UpdateDialog(u: Updater.Update, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (progress == null) onClose() },
        containerColor = CardBg,
        title = { Text("Вышла версия ${u.tag.trimStart('v')}", color = TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("У вас ${BuildConfig.VERSION_NAME}. Настройки сохранятся.", color = Muted)
                progress?.let {
                    LinearProgressIndicator(progress = { it }, color = Accent, trackColor = CardHi, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = Red) }
            }
        },
        confirmButton = {
            TextButton(enabled = progress == null, onClick = {
                progress = 0f
                error = null
                scope.launch {
                    runCatching { Updater.download(ctx, u) { p -> progress = p } }
                        .onSuccess { apk ->
                            progress = null
                            if (!Updater.install(ctx, apk)) {
                                error = "Разрешите установку из этого приложения и нажмите «Обновить» ещё раз"
                            } else onClose()
                        }
                        .onFailure { progress = null; error = it.message }
                }
            }) { Text("Обновить", color = Accent) }
        },
        dismissButton = {
            TextButton(enabled = progress == null, onClick = onClose) { Text("Позже", color = Muted) }
        },
    )
}
