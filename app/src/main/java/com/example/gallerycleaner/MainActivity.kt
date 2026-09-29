package com.example.gallerycleaner

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
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
    var phase by remember { mutableStateOf(0) }            // 0 بداية، 1 فحص، 2 نتايج
    var prog by remember { mutableStateOf(0 to 1) }
    var photos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var blurT by remember { mutableStateOf(80f) }
    var tab by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    val sel = remember { mutableStateListOf<Long>() }
    val res = remember(photos, blurT) { Analyzer.classify(photos, blurT.toDouble()) }

    fun startScan() {
        phase = 1
        scope.launch {
            photos = withContext(Dispatchers.Default) { Analyzer.scan(ctx) { a, b -> prog = a to b } }
            phase = 2
        }
    }

    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
               else Manifest.permission.READ_EXTERNAL_STORAGE
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startScan() }
    // نقل لسلة المحذوفات: أندرويد نفسه بيعرض شاشة تأكيد تانية
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { photos = photos.filter { it.id !in sel }; sel.clear() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("🧹 منظّف المعرض") }) },
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
                0 -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text("بيدوّر على المكرر والمهزوز وضعيف الجودة، ومش هيمسح حاجة غير بعد موافقتك.")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { permLauncher.launch(perm) }) { Text("ابدأ الفحص") }
                }
                1 -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text("بحلّل ${prog.first} من ${prog.second}…")
                    LinearProgressIndicator(progress = { prog.first / prog.second.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth().padding(16.dp))
                }
                else -> {
                    Text("حساسية كشف الهزّ: ${blurT.toInt()}", style = MaterialTheme.typography.labelMedium)
                    Slider(blurT, { blurT = it }, valueRange = 20f..200f)
                    TabRow(selectedTabIndex = tab) {
                        listOf("مكررة (${res.groups.sumOf { it.size - 1 }})", "مهزوزة (${res.blurry.size})", "ضعيفة (${res.low.size})")
                            .forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
                    }
                    // في المكررات: أول صورة في كل مجموعة هي الأفضل وبتتحفظ
                    val cells: List<Pair<Photo, Boolean>> = when (tab) {
                        0 -> res.groups.flatMap { g -> g.mapIndexed { i, p -> p to (i == 0) } }
                        1 -> res.blurry.map { it to false }
                        else -> res.low.map { it to false }
                    }
                    LazyVerticalGrid(GridCells.Fixed(3), Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(cells, key = { "$tab-${it.first.id}" }) { (p, keep) ->
                            val on = p.id in sel
                            Box(Modifier.aspectRatio(1f).clip(MaterialTheme.shapes.medium)
                                .border(BorderStroke(3.dp, if (keep) Color(0xFF188038) else if (on) Color(0xFFD93025) else Color.Transparent), MaterialTheme.shapes.medium)
                                .clickable(enabled = !keep) { if (on) sel.remove(p.id) else sel.add(p.id) }) {
                                AsyncImage(p.uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                if (keep) Text("الأفضل ✓", Modifier.align(Alignment.BottomCenter).background(Color(0xAA000000)).padding(2.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                else if (on) Text("✓", Modifier.align(Alignment.TopEnd).padding(4.dp), color = Color.White)
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
