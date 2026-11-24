package uz.moidengi.app

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Tx(val id: Long, val kind: String, val amount: Long, val category: String, val note: String, val date: String)

class TxDb(context: Context) : SQLiteOpenHelper(context, "moi_dengi.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tx(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, amount INTEGER NOT NULL, category TEXT NOT NULL, note TEXT, date TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun all(): List<Tx> {
        val out = mutableListOf<Tx>()
        readableDatabase.rawQuery("SELECT id,kind,amount,category,note,date FROM tx ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) out += Tx(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4) ?: "", c.getString(5))
        }
        return out
    }
    fun add(kind: String, amount: Long, category: String, note: String, date: String) {
        val v = ContentValues().apply {
            put("kind", kind); put("amount", amount); put("category", category); put("note", note); put("date", date)
        }
        writableDatabase.insert("tx", null, v)
    }
    fun delete(id: Long) { writableDatabase.delete("tx", "id=?", arrayOf(id.toString())) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = TxDb(this)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent { MaterialTheme { FinanceApp(db, prefs) } }
    }
}

private fun money(n: Long) = "%,d".format(Locale("ru"), n).replace(',', ' ')
private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
private fun monthNow() = today().take(7)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceApp(db: TxDb, prefs: android.content.SharedPreferences) {
    val context = LocalContext.current
    var txs by remember { mutableStateOf(db.all()) }
    var showAdd by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("Расход") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Еда") }
    var note by remember { mutableStateOf("") }
    var dayLimit by remember { mutableLongStateOf(prefs.getLong("day_limit", 100000L)) }
    var monthLimit by remember { mutableLongStateOf(prefs.getLong("month_limit", 3000000L)) }
    var dayInput by remember { mutableStateOf(dayLimit.toString()) }
    var monthInput by remember { mutableStateOf(monthLimit.toString()) }
    var voiceLanguage by remember { mutableStateOf(prefs.getString("voice_language", "ru-RU") ?: "ru-RU") }
    var recognized by remember { mutableStateOf("") }
    var pendingVoice by remember { mutableStateOf(false) }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            recognized = spoken
            val parsed = parseSpeech(spoken)
            if (parsed != null) {
                kind = parsed.first
                amount = parsed.second.toString()
                category = parsed.third
                note = spoken
                showAdd = true
            }
        }
        pendingVoice = false
    }

    val daySpent = txs.filter { it.kind == "Расход" && it.date == today() }.sumOf { it.amount }
    val monthSpent = txs.filter { it.kind == "Расход" && it.date.startsWith(monthNow()) }.sumOf { it.amount }
    val income = txs.filter { it.kind == "Доход" }.sumOf { it.amount }
    val expenses = txs.filter { it.kind == "Расход" }.sumOf { it.amount }
    val balance = income - expenses
    val dayExceeded = dayLimit > 0 && daySpent > dayLimit
    val monthExceeded = monthLimit > 0 && monthSpent > monthLimit
    var dayWasExceeded by remember { mutableStateOf(dayExceeded) }
    var monthWasExceeded by remember { mutableStateOf(monthExceeded) }
    LaunchedEffect(dayExceeded, monthExceeded) {
        if ((dayExceeded && !dayWasExceeded) || (monthExceeded && !monthWasExceeded)) {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vibrator.vibrate(100)
        }
        dayWasExceeded = dayExceeded
        monthWasExceeded = monthExceeded
    }

    Scaffold(
        topBar = {
            TopAppBarCompat(
                title = "Мои деньги",
                onSettings = { showSettings = true }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(onClick = {
                    pendingVoice = true
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLanguage)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Например: потратил 35 тысяч на обед")
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    }
                    try { speech.launch(i) } catch (_: Exception) { pendingVoice = false; recognized = "На телефоне нет службы распознавания речи" }
                }, containerColor = MaterialTheme.colorScheme.secondary) {
                    Icon(Icons.Default.Mic, contentDescription = "Голосовой ввод")
                }
                FloatingActionButton(onClick = { kind = "Расход"; amount = ""; note = ""; showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Добавить")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("ТЕКУЩИЙ БАЛАНС", style = MaterialTheme.typography.labelMedium)
                        Text("${money(balance)} сум", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("Доходы"); Text("${money(income)} сум", color = Color(0xFF16834A), fontWeight = FontWeight.Bold) }
                            Column { Text("Расходы"); Text("${money(expenses)} сум", color = Color(0xFFC62828), fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            item { LimitCard("Расходы сегодня", daySpent, dayLimit, dayExceeded) }
            item { LimitCard("Расходы за месяц", monthSpent, monthLimit, monthExceeded) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("История операций", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("${txs.size} записей", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (txs.isEmpty()) item { Text("Пока нет операций. Нажми + или микрофон, чтобы добавить первую запись.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(txs, key = { it.id }) { tx ->
                Card {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tx.note.ifBlank { tx.category }, fontWeight = FontWeight.SemiBold)
                            Text("${tx.category} • ${tx.date}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${if (tx.kind == "Доход") "+" else "−"}${money(tx.amount)} сум",
                            color = if (tx.kind == "Доход") Color(0xFF16834A) else Color(0xFFC62828), fontWeight = FontWeight.Bold)
                        TextButton(onClick = { db.delete(tx.id); txs = db.all() }) { Text("Удалить") }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Новая операция") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = kind == "Расход", onClick = { kind = "Расход" }, label = { Text("Расход") })
                        FilterChip(selected = kind == "Доход", onClick = { kind = "Доход" }, label = { Text("Доход") })
                    }
                    OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Сумма, сум") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    val cats = if (kind == "Доход") listOf("Карманные деньги", "Зарплата", "Подарок", "Возврат долга", "Прочее")
                        else listOf("Еда", "Транспорт", "Покупки", "Развлечения", "Одежда", "Связь и интернет", "Здоровье", "Топливо", "Прочее")
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                        OutlinedTextField(category, {}, readOnly = true, label = { Text("Категория") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            cats.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { category = c; expanded = false }) }
                        }
                    }
                    OutlinedTextField(note, { note = it }, label = { Text("Описание") })
                    if (recognized.isNotBlank()) Text("Распознано: $recognized", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = amount.toLongOrNull()
                    if (n != null && n > 0) {
                        db.add(kind, n, category, note, today())
                        txs = db.all()
                        showAdd = false
                        recognized = ""
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Отмена") } }
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Лимиты расходов") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(dayInput, { dayInput = it.filter(Char::isDigit) }, label = { Text("Дневной лимит, сум") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(monthInput, { monthInput = it.filter(Char::isDigit) }, label = { Text("Месячный лимит, сум") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("Укажи 0, чтобы отключить соответствующий лимит.", style = MaterialTheme.typography.bodySmall)
                    Text("Язык голосового ввода", fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = voiceLanguage == "ru-RU", onClick = {
                            voiceLanguage = "ru-RU"
                            prefs.edit().putString("voice_language", voiceLanguage).apply()
                        })
                        Text("Русский")
                        Spacer(Modifier.width(12.dp))
                        RadioButton(selected = voiceLanguage == "uz-UZ", onClick = {
                            voiceLanguage = "uz-UZ"
                            prefs.edit().putString("voice_language", voiceLanguage).apply()
                        })
                        Text("O‘zbekcha")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dayLimit = dayInput.toLongOrNull() ?: 0L
                    monthLimit = monthInput.toLongOrNull() ?: 0L
                    prefs.edit().putLong("day_limit", dayLimit).putLong("month_limit", monthLimit).apply()
                    showSettings = false
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Отмена") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopAppBarCompat(title: String, onSettings: () -> Unit) {
    TopAppBar(title = { Text(title, fontWeight = FontWeight.Bold) }, actions = {
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Настройки") }
    })
}

@Composable
private fun LimitCard(title: String, spent: Long, limit: Long, exceeded: Boolean) {
    val fraction = if (limit <= 0) 0f else (spent.toFloat() / limit).coerceIn(0f, 1f)
    val red = Color(0xFFC62828)
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text("${money(spent)} / ${if (limit <= 0) "лимит не задан" else money(limit) + " сум"}",
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (exceeded) red else MaterialTheme.colorScheme.onSurface)
            if (limit > 0) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(),
                color = if (exceeded) red else MaterialTheme.colorScheme.primary)
            if (exceeded) Text("Лимит превышен на ${money(spent - limit)} сум", color = red, fontWeight = FontWeight.Bold)
        }
    }
}

private fun parseSpeech(raw: String): Triple<String, Long, String>? {
    val s = raw.lowercase(Locale("ru"))
    val kind = if (listOf("получил", "получила", "заработал", "заработала", "дали", "подарили", "пришло").any { s.contains(it) } ||
        listOf("oldim", "ish haqi", "maosh", "tushdi", "berishdi").any { s.contains(it) }) "Доход" else "Расход"
    val category = when {
        listOf("такси", "автобус", "метро", "транспорт", "yo'l", "taksi").any { s.contains(it) } -> "Транспорт"
        listOf("обед", "еда", "продукт", "магазин", "кофе", "завтрак", "ужин", "ovqat", "non", "bozor").any { s.contains(it) } -> "Еда"
        listOf("бензин", "топливо", "заправ", "benzin", "gaz").any { s.contains(it) } -> "Топливо"
        listOf("одежд", "обув", "куртк", "kiyim").any { s.contains(it) } -> "Одежда"
        listOf("интернет", "телефон", "связь", "internet", "telefon").any { s.contains(it) } -> "Связь и интернет"
        listOf("кино", "игра", "развлеч", "kino").any { s.contains(it) } -> "Развлечения"
        listOf("лекар", "врач", "аптек", "dori", "shifokor").any { s.contains(it) } -> "Здоровье"
        kind == "Доход" -> when {
            s.contains("зарплат") || s.contains("maosh") -> "Зарплата"
            s.contains("подар") -> "Подарок"
            else -> "Карманные деньги"
        }
        else -> "Покупки"
    }
    val num = Regex("""\d[\d\s.,]*""").find(s)?.value?.replace(Regex("""[\s.,]"""), "")?.toLongOrNull()
    val multiplier = when {
        s.contains("миллион") || s.contains("млн") || s.contains("million") -> 1_000_000L
        s.contains("тысяч") || s.contains("минг") || s.contains("ming") -> 1_000L
        else -> 1L
    }
    val amount = (num?.let { it * multiplier } ?: parseWordNumber(s)) ?: return null
    return Triple(kind, amount, category)
}

private fun parseWordNumber(s: String): Long? {
    val units = mapOf("один" to 1L, "одна" to 1L, "два" to 2L, "две" to 2L, "три" to 3L, "четыре" to 4L, "пять" to 5L, "олти" to 6L, "семь" to 7L, "восемь" to 8L, "девять" to 9L, "десять" to 10L, "ўн" to 10L, "он" to 10L, "йигирма" to 20L, "yigirma" to 20L, "ўттиз" to 30L, "оттиз" to 30L, "qirq" to 40L, "қирқ" to 40L, "эллик" to 50L, "ellik" to 50L, "юз" to 100L, "yuz" to 100L)
    val words = s.split(Regex("""[\s,]+"""))
    var total = 0L
    for (w in words) {
        units[w]?.let { total += it }
    }
    val thousand = s.contains("тысяч") || s.contains("минг") || s.contains("ming")
    return if (total > 0) total * if (thousand) 1000 else 1 else null
}
