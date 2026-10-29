package br.geracaoai.arquivos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object Similar {
    fun hashOf(f: File): Long? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
        var s = 1; while (o.outWidth / (s * 2) >= 64 && o.outHeight / (s * 2) >= 64) s *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val sm = Bitmap.createScaledBitmap(b, 9, 8, true); b.recycle()
        val g = IntArray(72) { val p = sm.getPixel(it % 9, it / 9); ((p shr 16 and 255) * 299 + (p shr 8 and 255) * 587 + (p and 255) * 114) / 1000 }
        sm.recycle(); PHash.dHash(g)
    }.getOrNull()
    fun find(ctx: Context, prog: (Int, Int) -> Unit): List<List<File>> {
        val imgs = volumes(ctx).flatMap { v -> walk(v.root, false) { it.ext in setOf("jpg", "jpeg", "png", "webp") && it.length() > 20 * 1024 } }
        val hs = imgs.mapIndexedNotNull { i, f -> prog(i + 1, imgs.size); hashOf(f)?.let { f to it } }
        return PHash.cluster(hs, 5).sortedByDescending { it.size }
    }
}

object Junk {
    data class Group(val label: String, val desc: String, val files: List<File>) { val size get() = files.sumOf { if (it.isFile) it.length() else it.walkTopDown().sumOf { f -> f.length() } } }
    fun scan(ctx: Context): List<Group> {
        val r = primaryRoot; val pm = ctx.packageManager
        val all = walk(r, true) { true }
        val thumbs = listOf("DCIM/.thumbnails", "Pictures/.thumbnails", "Movies/.thumbnails").map { File(r, it) }.filter { it.isDirectory }.flatMap { d -> d.listFiles().orEmpty().toList() }
        val tmp = all.filter { it.ext in setOf("tmp", "temp", "bak") || it.name.endsWith("~") || it.name == "Thumbs.db" || it.name == ".DS_Store" }
        val logs = all.filter { it.ext == "log" || it.ext == "trace" }
        val apks = all.filter { it.ext == "apk" }.filter { f -> runCatching { val pi = pm.getPackageArchiveInfo(f.path, 0)!!; val inst = pm.getPackageInfo(pi.packageName, 0); inst.longVersionCode >= pi.longVersionCode }.getOrDefault(false) }
        val empty = r.walkBottomUp().filter { it.isDirectory && it != r && it.listFiles()?.isEmpty() == true && !it.path.contains("/Android/") && !it.path.contains(".LixeiraGerenciador") }.toList()
        val installed = pm.getInstalledApplications(0).map { it.packageName }.toSet()
        val orphans = File(r, "Android/media").listFiles().orEmpty().filter { it.isDirectory && it.name.contains('.') && it.name !in installed }
        return listOf(Group("Miniaturas em cache", "O Android recria quando precisar", thumbs), Group("Arquivos temporários", ".tmp, .bak, Thumbs.db…", tmp),
            Group("Logs", "Arquivos .log", logs), Group("APKs já instalados", "Instaladores de apps que você já tem", apks),
            Group("Pastas vazias", "Sem nenhum arquivo", empty), Group("Mídia de apps desinstalados", "Android/media órfão", orphans)).filter { it.files.isNotEmpty() }
    }
}

@Composable
fun JunkScreen(back: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<Junk.Group>?>(null) }; var busy by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf<String>() }; var ask by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf<String?>(null) }
    fun scan() { busy = true; scope.launch { groups = withContext(Dispatchers.IO) { Junk.scan(ctx) }; chosen.clear(); chosen.addAll(groups!!.filter { it.label != "APKs já instalados" }.map { it.label }); busy = false } }
    val total = groups.orEmpty().filter { it.label in chosen }.sumOf { it.size }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { Bar("Limpeza", groups?.let { "${fmtSize(it.sumOf { g -> g.size })} encontrados" }, back) },
        bottomBar = { if (chosen.isNotEmpty() && groups != null) Button({ ask = true }, Modifier.fillMaxWidth().padding(12.dp).height(56.dp)) { Text("Limpar ${fmtSize(total)} (vai para a lixeira)") } }) { pad ->
        Column(Modifier.padding(pad)) {
            msg?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.secondary) }
            when { busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                groups == null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    EmptyState(Icons.Default.CleaningServices, "Limpeza segura", "Tudo que for limpo vai para a Lixeira primeiro e pode ser restaurado."); Button({ scan() }) { Text("Analisar") } }
                groups!!.isEmpty() -> EmptyState(Icons.Default.Verified, "Nada para limpar")
                else -> LazyColumn(contentPadding = PaddingValues(12.dp)) { items(groups!!) { g ->
                    ListItem({ Text(g.label) }, supportingContent = { Text("${g.files.size} itens · ${fmtSize(g.size)} · ${g.desc}") },
                        trailingContent = { Checkbox(g.label in chosen, { if (it) chosen.add(g.label) else chosen.remove(g.label) }) }) } } }
        }
    }
    if (ask) Confirm("Limpar?", "${fmtSize(total)} serão movidos para a Lixeira do app (restauráveis).", "Limpar", onDismiss = { ask = false }) {
        val fs = groups.orEmpty().filter { it.label in chosen }.flatMap { it.files }
        scope.launch { var n = 0; withContext(Dispatchers.IO) { fs.forEach { if (it.exists() && Trash.moveToTrash(ctx, it)) n++ } }; msg = "✓ $n itens movidos para a lixeira"; scan() } }
}
