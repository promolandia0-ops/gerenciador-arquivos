package br.geracaoai.arquivos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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

/** Regras puras de "arquivos inúteis" (testáveis). Caminhos relativos ao armazenamento (ex.: "Android/media/..."). */
object JunkRules {
    const val ICON_MAX_BYTES = 40 * 1024L; const val ICON_MAX_PX = 256
    private val IMG = setOf("png", "webp", "gif", "jpg", "jpeg", "ico", "bmp")
    private val STATUS = setOf(".statuses", "statuses", "status", "whatsapp status", ".stories", "stories")
    private fun segs(rel: String) = rel.trim('/').lowercase().split('/')
    /** Galeria do usuário: nunca mexer (exceto .thumbnails). */
    fun isProtected(rel: String): Boolean { val s = segs(rel); return s.first() in setOf("dcim", "pictures", "movies") && ".thumbnails" !in s && ".thumbs" !in s }
    fun isStatus(rel: String): Boolean { val s = segs(rel); val app = rel.lowercase().let { "whatsapp" in it || "telegram" in it || "instagram" in it || it.startsWith("android/media/") }
        return app && s.dropLast(1).any { it in STATUS } && !s.last().equals(".nomedia") }
    fun isIcon(rel: String, size: Long, w: Int = 0, h: Int = 0): Boolean { if (isProtected(rel) || isStatus(rel)) return false
        val e = rel.substringAfterLast('.', "").lowercase(); if (e !in IMG || size <= 0 || size > ICON_MAX_BYTES) return false
        return w <= 0 || h <= 0 || maxOf(w, h) <= ICON_MAX_PX }
    fun isMediaCache(rel: String): Boolean { val s = segs(rel); if (isProtected(rel) || isStatus(rel)) return false
        return (s.first() == "android" && s.getOrNull(1) == "media" && s.dropLast(1).drop(3).any { it == "cache" || it == ".cache" || it == "temp" || it == ".tmp" }) ||
            s.dropLast(1).any { it == ".thumbnails" || it == ".thumbs" } }
}

object Junk {
    data class Group(val label: String, val desc: String, val files: List<File>, val media: Boolean = false) { val size by lazy { files.sumOf { if (it.isFile) it.length() else it.walkTopDown().sumOf { f -> f.length() } } } }
    fun scan(ctx: Context): List<Group> {
        val r = primaryRoot; val pm = ctx.packageManager
        val all = walk(r, true) { !it.path.contains("/.LixeiraGerenciador") }
        fun rel(f: File) = f.path.removePrefix(r.path).trimStart('/')
        val status = all.filter { JunkRules.isStatus(rel(it)) }
        val icons = all.filter { f -> f.length() <= JunkRules.ICON_MAX_BYTES && JunkRules.isIcon(rel(f), f.length()) && run {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; runCatching { BitmapFactory.decodeFile(f.path, o) }
            JunkRules.isIcon(rel(f), f.length(), o.outWidth, o.outHeight) } }
        val mcache = all.filter { JunkRules.isMediaCache(rel(it)) && it.name != ".nomedia" }
        val tmp = all.filter { it.ext in setOf("tmp", "temp", "bak") || it.name.endsWith("~") || it.name == "Thumbs.db" || it.name == ".DS_Store" }
        val logs = all.filter { it.ext == "log" || it.ext == "trace" }
        val apks = all.filter { it.ext == "apk" }.filter { f -> runCatching { val pi = pm.getPackageArchiveInfo(f.path, 0)!!; val inst = pm.getPackageInfo(pi.packageName, 0); inst.longVersionCode >= pi.longVersionCode }.getOrDefault(false) }
        val empty = r.walkBottomUp().filter { it.isDirectory && it != r && it.listFiles()?.isEmpty() == true && !it.path.contains("/Android/") && !it.path.contains(".LixeiraGerenciador") }.toList()
        val installed = pm.getInstalledApplications(0).map { it.packageName }.toSet()
        val orphans = File(r, "Android/media").listFiles().orEmpty().filter { it.isDirectory && it.name.contains('.') && it.name !in installed }
        val sv = status.filter { (it.ext.ifEmpty { Carver.sniff(it) ?: "" }) in VIDEO }
        return listOf(Group("Vídeos de status", "Status/stories de WhatsApp, Telegram e outros salvos sem você pedir", sv.sortedByDescending { it.length() }, true),
            Group("Fotos de status", "Fotos de status/stories salvas automaticamente", (status - sv.toSet()).sortedByDescending { it.length() }, true),
            Group("Ícones (fotos-ícone pequenas)", "Imagens minúsculas (até 40 KB / 256 px) fora da galeria", icons, true),
            Group("Miniaturas e caches de mídia", ".thumbnails e caches em Android/media — o Android recria", mcache.sortedByDescending { it.length() }, true),
            Group("Arquivos temporários", ".tmp, .bak, Thumbs.db…", tmp),
            Group("Logs", "Arquivos .log", logs), Group("APKs já instalados", "Instaladores de apps que você já tem", apks),
            Group("Pastas vazias", "Sem nenhum arquivo", empty), Group("Mídia de apps desinstalados", "Android/media órfão", orphans)).filter { it.files.isNotEmpty() }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun JunkScreen(back: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<Junk.Group>?>(null) }; var busy by remember { mutableStateOf(false) }
    val sel = remember { mutableStateMapOf<String, Boolean>() }; var open by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun scan() { busy = true; job = scope.launch { try { groups = withContext(Dispatchers.IO) { Junk.scan(ctx) }; sel.clear()
        groups!!.filter { it.label in setOf("Vídeos de status", "Fotos de status", "Miniaturas e caches de mídia", "Arquivos temporários") }.forEach { g -> g.files.forEach { sel[it.path] = true } } } finally { busy = false } } }
    val chosen = groups.orEmpty().flatMap { it.files }.filter { sel[it.path] == true }
    val total = chosen.sumOf { if (it.isFile) it.length() else it.walkTopDown().sumOf { f -> f.length() } }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { Bar("Buscar lixos inúteis", groups?.let { "${fmtSize(it.sumOf { g -> g.size })} encontrados" }, back) {
            if (busy) TextButton({ job?.cancel() }) { Text("Cancelar") } else if (groups != null) TextButton({ scan() }) { Text("Analisar de novo") } } },
        bottomBar = { if (chosen.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(12.dp)) {
            OutlinedButton({ sel.clear() }, Modifier.height(56.dp)) { Text("Limpar seleção") }; Spacer(Modifier.width(8.dp))
            Button({ ask = true }, Modifier.weight(1f).height(56.dp)) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text("Apagar ${chosen.size} · ${fmtSize(total)}") } } }) { pad ->
        Column(Modifier.padding(pad)) {
            msg?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.secondary) }
            when { busy -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text("Analisando o armazenamento…", Modifier.padding(12.dp)) }
                groups == null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    EmptyState(Icons.Default.CleaningServices, "Arquivos inúteis", "Status dos outros, ícones, figurinhas, miniaturas e caches. Fotos e vídeos da Câmera/DCIM/Pictures nunca entram. Tudo vai para a Lixeira do app e pode ser restaurado."); Button({ scan() }) { Text("Analisar") } }
                groups!!.isEmpty() -> EmptyState(Icons.Default.Verified, "Nada para limpar")
                else -> LazyColumn(contentPadding = PaddingValues(12.dp)) { groups!!.forEach { g ->
                    val n = g.files.count { sel[it.path] == true }
                    item(key = "h" + g.label) { ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        ListItem({ Text(g.label) }, Modifier.clickable { open = if (open == g.label) null else g.label },
                            supportingContent = { Text("${g.files.size} itens · ${fmtSize(g.size)}" + (if (n > 0) " · $n marcados" else "") + "\n${g.desc}") },
                            leadingContent = { Icon(when (g.label) { "Vídeos de status" -> Icons.Default.Movie; "Fotos de status" -> Icons.Default.AutoAwesome; "Ícones (fotos-ícone pequenas)" -> Icons.Default.EmojiEmotions; else -> Icons.Default.CleaningServices }, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { TriStateCheckbox(when (n) { 0 -> androidx.compose.ui.state.ToggleableState.Off; g.files.size -> androidx.compose.ui.state.ToggleableState.On; else -> androidx.compose.ui.state.ToggleableState.Indeterminate }, {
                                val on = n < g.files.size; g.files.forEach { if (on) sel[it.path] = true else sel.remove(it.path) } }) }) } }
                    if (open == g.label) {
                        item(key = "a" + g.label) { Row { TextButton({ g.files.forEach { sel[it.path] = true } }) { Text("Marcar todos") }; TextButton({ g.files.forEach { sel.remove(it.path) } }) { Text("Desmarcar") } } }
                        if (g.media) items(g.files.take(600).chunked(4), key = { "r" + it.first().path }) { row -> Row(Modifier.fillMaxWidth()) { row.forEach { f ->
                            Box(Modifier.weight(1f)) { MediaCell(RItem(RCat.CACHE, f.path, f.length(), f.lastModified(), f.ext.ifEmpty { Carver.sniff(f) }), sel[f.path] == true,
                                { if (sel[f.path] == true) sel.remove(f.path) else sel[f.path] = true }) { if (sel[f.path] == true) sel.remove(f.path) else sel[f.path] = true } } }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) } } }
                        else items(g.files.take(600), key = { "f" + it.path }) { f -> ListItem({ Text(f.name, maxLines = 1) }, supportingContent = { Text(f.parent?.removePrefix(primaryRoot.path).orEmpty() + " · " + fmtSize(if (f.isFile) f.length() else 0), maxLines = 1) },
                            trailingContent = { Checkbox(sel[f.path] == true, { if (it) sel[f.path] = true else sel.remove(f.path) }) }) }
                        if (g.files.size > 600) item(key = "m" + g.label) { Text("+${g.files.size - 600} itens (incluídos em Marcar todos)", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall) }
                    } } } }
        }
    }
    if (ask) Confirm("Apagar ${chosen.size} itens?", "${fmtSize(total)} serão movidos para a Lixeira do app (dá para restaurar em Ferramentas > Lixeira).", "Apagar", danger = true, onDismiss = { ask = false }) {
        ask = false; val fs = chosen.toList()
        scope.launch { var ok = 0; withContext(Dispatchers.IO) { fs.forEach { if (it.exists() && !JunkRules.isProtected(it.path.removePrefix(primaryRoot.path).trimStart('/')) && Trash.moveToTrash(ctx, it)) ok++ } }
            msg = "✓ $ok itens movidos para a lixeira · ${fmtSize(total)}"; scan() } }
}


@Composable
fun AppFoldersScreen(go: (Scr) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    data class AF(val dir: File, val label: String, val where: String)
    var list by remember { mutableStateOf<List<AF>?>(null) }; var blocked by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { val pm = ctx.packageManager; val bl = ArrayList<String>(); val out = ArrayList<AF>()
        listOf("media", "data", "obb").forEach { s -> val kids = File(primaryRoot, "Android/$s").listFiles()
            if (kids == null || (s != "media" && kids.isEmpty())) bl += "Android/$s"
            kids.orEmpty().filter { it.isDirectory }.forEach { k -> out += AF(k, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(k.name, 0)).toString() }.getOrDefault(k.name), "Android/$s") } }
        list = out.sortedBy { it.label.lowercase() }; blocked = bl } }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { Bar("Pastas de apps", list?.let { "${it.size} pastas" }, back) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp)) {
            if (blocked.isNotEmpty()) item { Text("🔒 " + blocked.joinToString() + ": o Android bloqueia essas pastas sem root. Tente em Ferramentas > Android/data e obb (permissão de pasta, quando o Android permitir).",
                Modifier.padding(bottom = 8.dp).clickable { go(Scr.Data) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            if (list == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(list.orEmpty(), key = { it.dir.path }) { a -> ListItem({ Text(a.label) }, Modifier.clickable { Nav.openFolder(a.dir.path) },
                supportingContent = { Text(a.where + "/" + a.dir.name, maxLines = 1) }, leadingContent = { Icon(Icons.Default.FolderSpecial, null, tint = MaterialTheme.colorScheme.primary) }) }
        }
    }
}

/** "Abrir pasta" de um achado; avisa quando a pasta é inacessível. */
fun openParentFolder(ctx: Context, path: String, protectedReason: String? = null) {
    val parent = File(path).parentFile
    when {
        protectedReason != null -> toast(ctx, protectedReason)
        parent == null || parent.listFiles() == null -> toast(ctx, "A pasta ${parent?.path?.removePrefix(primaryRoot.path) ?: ""} é protegida pelo Android (sem root não dá para abrir). Use Recuperar para copiar o arquivo.")
        else -> Nav.openFolder(parent.path)
    }
}
