package com.example.gallerycleaner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { App() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf<List<Folder>?>(null) }
    var denied by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(0) }            // 0 فولدرات، 1 فحص، 2 نتايج
    var current by remember { mutableStateOf<Folder?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var prog by remember { mutableStateOf(0 to 1) }
    var photos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var blurT by remember { mutableStateOf(50f) }
    var dupBits by remember { mutableStateOf(16f) }
    var lowMP by remember { mutableStateOf(0.5f) }
    var showSet by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    val sel = remember { mutableStateListOf<Long>() }
    val res = remember(photos, blurT, dupBits, lowMP) {
        Analyzer.classify(photos, Params(blurT.toDouble(), dupBits.toInt(), lowMP.toDouble()))
    }

    fun loadFolders() { scope.launch { folders = withContext(Dispatchers.IO) { Analyzer.folders(ctx) } } }
    fun scan(f: Folder) {
        current = f; phase = 1; sel.clear(); tab = 0; prog = 0 to 1
        job = scope.launch {
            photos = withContext(Dispatchers.Default) { Analyzer.scan(ctx, f.id) { a, b -> prog = a to b } }
            phase = 2
        }
    }
    fun goBack() { job?.cancel(); phase = 0; sel.clear(); photos = emptyList(); loadFolders() }

    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
               else Manifest.permission.READ_EXTERNAL_STORAGE
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { denied = false; loadFolders() } else denied = true
    }
    // نقل لسلة المحذوفات: أندرويد نفسه بيعرض شاشة تأكيد تانية
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { photos = photos.filter { it.id !in sel }; sel.clear() }
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) loadFolders()
        else permLauncher.launch(perm)
    }
    BackHandler(enabled = phase != 0) { goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (phase == 0) "اختار الفولدر" else current?.name ?: "") },
                navigationIcon = { if (phase != 0) TextButton(onClick = { goBack() }) { Text("رجوع") } }
            )
        },
        bottomBar = {
            if (phase == 2) Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val cand = when (tab) { 0 -> res.groups.flatMap { it.drop(1) }; 1 -> res.blurry; else -> res.low }.map { it.id }
                    if (cand.all { it in sel }) sel.removeAll(cand.toSet()) else cand.forEach { if (it !in sel) sel.add(it) }
                }, Modifier.weight(1f)) { Text("تحديد الكل") }
                Button(onClick = { confirm = true }, enabled = sel.isNotEmpty(), modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD93025))) { Text("مسح (${sel.size})") }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            when (phase) {
                0 -> {
                    val fl = folders
                    when {
                        denied -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                            Text("محتاج إذن الوصول للصور عشان أعرض الفولدرات.")
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { permLauncher.launch(perm) }) { Text("اسمح بالوصول") }
                        }
                        fl == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                        fl.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("مفيش صور على الجهاز") }
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                            item { Text("اضغط على الفولدر اللي عايز تفحصه. مفيش حاجة هتتمسح غير بعد موافقتك.", style = MaterialTheme.typography.bodySmall) }
                            items(fl, key = { it.id }) { f ->
                                Card(Modifier.fillMaxWidth().clickable { scan(f) }) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        AsyncImage(f.cover, null, Modifier.size(64.dp).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop)
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(f.name, style = MaterialTheme.typography.titleMedium)
                                            Text("${f.count} صورة", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text("بفحص \"${current?.name}\": ${prog.first} من ${prog.second}")
                    LinearProgressIndicator(progress = { prog.first / prog.second.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth().padding(16.dp))
                }
                else -> {
                    TextButton(onClick = { showSet = !showSet }) { Text(if (showSet) "إخفاء إعدادات الحساسية ▴" else "إعدادات الحساسية ▾") }
                    if (showSet) Column {
                        Text("حساسية الهزّ: ${blurT.toInt()}  (أعلى = صور أكتر تتحسب مهزوزة)", style = MaterialTheme.typography.labelMedium)
                        Slider(blurT, { blurT = it }, valueRange = 5f..300f)
                        Text("تساهل التكرار: ${dupBits.toInt()}  (أقل = مطابقة أدق)", style = MaterialTheme.typography.labelMedium)
                        Slider(dupBits, { dupBits = it }, valueRange = 0f..48f)
                        Text("حد الجودة الضعيفة: ${"%.1f".format(lowMP)} ميجابكسل", style = MaterialTheme.typography.labelMedium)
                        Slider(lowMP, { lowMP = it }, valueRange = 0.1f..3f)
                    }
                    TabRow(selectedTabIndex = tab) {
                        listOf("مكررة (${res.groups.sumOf { it.size - 1 }})", "مهزوزة (${res.blurry.size})", "ضعيفة (${res.low.size})")
                            .forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
                    }
                    val cells: List<Pair<Photo, Boolean>> = when (tab) {
                        0 -> res.groups.flatMap { g -> g.mapIndexed { i, p -> p to (i == 0) } }
                        1 -> res.blurry.map { it to false }
                        else -> res.low.map { it to false }
                    }
                    LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f).padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(cells, key = { "$tab-${it.first.id}" }) { (p, keep) ->
                            val on = p.id in sel
                            Box(Modifier.aspectRatio(1f).clip(MaterialTheme.shapes.medium)
                                .border(BorderStroke(3.dp, if (keep) Color(0xFF188038) else if (on) Color(0xFFD93025) else Color.Transparent), MaterialTheme.shapes.medium)
                                .clickable(enabled = !keep) { if (on) sel.remove(p.id) else sel.add(p.id) }) {
                                AsyncImage(p.uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                val label = when {
                                    keep -> "الأفضل ✓"
                                    tab == 0 -> "مكررة"
                                    tab == 1 -> "وضوح ${p.sharp.toInt()}"
                                    else -> "${"%.2f".format(p.w.toLong() * p.h / 1e6)} MP"
                                }
                                Text(label, Modifier.align(Alignment.BottomCenter).background(Color(0xAA000000)).padding(horizontal = 4.dp, vertical = 1.dp),
                                    color = Color.White, style = MaterialTheme.typography.labelSmall)
                                if (on) Text("✓", Modifier.align(Alignment.TopEnd).padding(4.dp), color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("تأكيد المسح") },
        text = { Text("هتنقل ${sel.size} صورة لسلة المحذوفات (بتفضل 30 يوم وتقدر ترجعها). أندرويد هيطلب تأكيد كمان مرة.") },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                val uris = photos.filter { it.id in sel }.map { it.uri }
                val pi = MediaStore.createTrashRequest(ctx.contentResolver, uris, true)
                trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            }) { Text("أكيد، انقل") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("إلغاء") } }
    )
}
