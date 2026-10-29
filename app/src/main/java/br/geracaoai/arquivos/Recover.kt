package br.geracaoai.arquivos

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import java.io.File

enum class RCat(val label: String) { LIXEIRA("Lixeiras"), CACHE("Caches de apps"), MINIATURA("Miniaturas"), EXTRAIDO("Extraídos de blobs"),
    RENOMEADO("Extensão trocada"), OCULTO("Mídia oculta/.nomedia"), ORFAO("Pastas órfãs"), ANTIGO("Arquivos antigos") }

/** Um achado. Se carved != null, é um trecho dentro de [path]. Se priv, o caminho só é legível via Shizuku/root. */
data class RItem(val cat: RCat, val path: String, val size: Long, val modified: Long, val type: String?, val carved: Carver.Carved? = null, val priv: Boolean = false, val isDir: Boolean = false) {
    val key get() = path + "#" + (carved?.offset ?: -1)
    val name get() = path.substringAfterLast('/') + (carved?.let { " @${it.offset}" } ?: "")
    val isMedia get() = type in IMAGE || type in VIDEO || type in setOf("heic", "gif", "webp")
}

object RecoverScan {
    val root get() = primaryRoot.path
    private val MEDIA = IMAGE + VIDEO + setOf("heic")
    private val PRIV_TRASH = listOf("com.sec.android.gallery3d/files/.Trash", "com.sec.android.app.myfiles/files/trash", "com.sec.android.app.myfiles/files/.trash",
        "com.miui.gallery/files/trashBin", "com.coloros.gallery3d/files/.RecycleBin", "com.google.android.apps.photos/files/trash")
    private val PRIV_CACHE = listOf("com.sec.android.gallery3d/cache", "org.telegram.messenger/cache", "org.telegram.messenger/files/Telegram", "org.telegram.messenger.web/cache",
        "com.whatsapp/cache", "com.whatsapp.w4b/cache", "com.instagram.android/cache", "com.facebook.katana/cache", "com.facebook.orca/cache",
        "com.zhiliaoapp.musically/cache", "com.google.android.apps.photos/cache", "com.snapchat.android/cache", "com.twitter.android/cache")
    private val PUB_CACHE = listOf("Android/media/com.whatsapp/WhatsApp/Media/.Statuses", "Android/media/com.whatsapp/WhatsApp/.Shared", "Android/media/com.whatsapp/WhatsApp/.Thumbs",
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/Sent",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses", "Telegram", "Android/media/org.telegram.messenger", "Android/media/com.instagram.android")
    private val PUB_THUMBS = listOf("DCIM/.thumbnails", "Pictures/.thumbnails", "Movies/.thumbnails", "DCIM/.thumbs")

    fun isHiddenPath(f: File) = f.path.removePrefix(root).split('/').any { it.startsWith(".") } || generateSequence(f.parentFile) { it.parentFile }.take(6).any { File(it, ".nomedia").exists() }
    fun isGalleryMedia(f: File) = f.ext in MEDIA && !isHiddenPath(f) && !f.name.startsWith(".trashed")

    data class Opts(val trash: Boolean = true, val caches: Boolean = true, val thumbs: Boolean = true, val carve: Boolean = true, val magic: Boolean = true,
                    val hidden: Boolean = true, val orphans: Boolean = true, val old: Boolean = true, val oldDays: Int = 365, val includeGallery: Boolean = false)

    suspend fun scan(ctx: Context, o: Opts, phase: (String) -> Unit, found: (RItem) -> Unit) = coroutineScope {
        val seen = HashSet<String>()
        fun add(i: RItem) { if (seen.add(i.key)) found(i) }
        val tmp = File(ctx.externalCacheDir, "scan_tmp").apply { deleteRecursively(); mkdirs() }
        val priv = Priv.ok()
        val excl = listOf("$root/.LixeiraGerenciador", "$root/Recuperados", "$root/Android/data", "$root/Android/obb")
        fun publicFiles(dir: File): Sequence<File> = dir.walkTopDown().onEnter { d -> excl.none { d.path == it } }.filter { it.isFile }
        fun carveInto(f: File, cat: RCat, displayPath: String = f.path, isPriv: Boolean = false) {
            if (!o.carve) return
            runCatching { Carver.carveFile(f).filterNot { it.offset == 0L && it.length == f.length() }.forEach { c ->
                add(RItem(RCat.EXTRAIDO, f.path, c.length, f.lastModified(), c.type, c, false).copy(path = f.path)) } }
        }
        fun handlePublic(f: File, cat: RCat) {
            val t = Carver.sniff(f)
            if (t != null) add(RItem(cat, f.path, f.length(), f.lastModified(), t)) else if (f.length() in 2048..(512L shl 20)) carveInto(f, cat)
        }
        fun handlePriv(dir: String, cat: RCat) {
            var budget = 800L shl 20
            Priv.findFiles(dir, 1024).forEach { pf -> ensureActive()
                val t = runCatching { Carver.sniff(Priv.hexToBytes(pf.head)) }.getOrNull()
                if (t != null) add(RItem(cat, pf.path, pf.size, pf.modified, t, priv = true))
                else if (o.carve && pf.size in 4096..(128L shl 20) && budget > pf.size) { budget -= pf.size
                    val local = File(tmp, pf.path.hashCode().toString() + "_" + pf.path.substringAfterLast('/'))
                    if (Priv.copy(pf.path, local.path) && local.exists()) carveInto(local, cat) }
            }
        }
        if (o.trash) { phase("Lixeiras do sistema e da galeria…")
            runCatching { val b = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
                ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED), b, null)?.use { c ->
                    while (c.moveToNext()) { val p = c.getString(0) ?: continue; val f = File(p); if (f.exists()) add(RItem(RCat.LIXEIRA, p, c.getLong(1), c.getLong(2) * 1000, Carver.sniff(f) ?: f.ext)) } } }
            publicFiles(primaryRoot).filter { f -> f.name.startsWith(".trashed-") || f.path.split('/').any { s -> s.lowercase().let { it.startsWith(".trash") || it == ".recycle" || it == ".recyclebin" || it == "recycle bin" } } }
                .forEach { ensureActive(); add(RItem(RCat.LIXEIRA, it.path, it.length(), it.lastModified(), Carver.sniff(it) ?: it.ext)) }
            if (priv) PRIV_TRASH.forEach { handlePriv("$root/Android/data/$it", RCat.LIXEIRA) }
        }
        if (o.caches) { phase("Caches de WhatsApp, Telegram, Instagram, galeria…")
            PUB_CACHE.map { File(primaryRoot, it) }.filter { it.isDirectory }.forEach { d -> publicFiles(d).forEach { ensureActive(); if (it.ext in MEDIA) add(RItem(RCat.CACHE, it.path, it.length(), it.lastModified(), it.ext)) else handlePublic(it, RCat.CACHE) } }
            if (priv) PRIV_CACHE.forEach { phase("Cache (via ${Priv.name()}): ${it.substringBefore('/')}"); handlePriv("$root/Android/data/$it", RCat.CACHE) }
        }
        if (o.thumbs) { phase("Miniaturas e thumbdata…")
            PUB_THUMBS.map { File(primaryRoot, it) }.filter { it.isDirectory }.forEach { d -> publicFiles(d).forEach { ensureActive(); handlePublic(it, RCat.MINIATURA) } }
        }
        if (o.magic || o.hidden || o.old) { phase("Varredura completa (assinaturas, ocultos, antigos)…")
            val lim = System.currentTimeMillis() - o.oldDays * 86400000L
            volumes(ctx).forEach { v -> publicFiles(v.root).forEach { f -> ensureActive()
                val hidden = isHiddenPath(f)
                if (o.magic && f.ext !in MEDIA && f.length() >= 4096 && f.ext !in setOf("apk", "zip", "pdf", "mp3", "m4a", "ogg", "opus", "jar", "db", "json", "txt")) {
                    Carver.sniff(f)?.takeIf { it != "pdf" && it != "mp3" && it != "ogg" && it != "m4a" }?.let { add(RItem(RCat.RENOMEADO, f.path, f.length(), f.lastModified(), it)) } }
                if (o.hidden && hidden && f.ext in MEDIA) add(RItem(RCat.OCULTO, f.path, f.length(), f.lastModified(), f.ext))
                if (o.old && f.lastModified() < lim && f.length() >= 100 * 1024 && (o.includeGallery || !isGalleryMedia(f))) add(RItem(RCat.ANTIGO, f.path, f.length(), f.lastModified(), f.ext))
            } }
        }
        if (o.orphans) { phase("Pastas de apps desinstalados…")
            val pm = ctx.packageManager; val installed = pm.getInstalledApplications(0).map { it.packageName }.toSet()
            fun isPkg(n: String) = n.count { it == '.' } >= 1 && n !in installed && n.matches(Regex("[A-Za-z][\\w]*(\\.[\\w]+)+"))
            File(primaryRoot, "Android/media").listFiles().orEmpty().filter { it.isDirectory && isPkg(it.name) }.forEach { d ->
                add(RItem(RCat.ORFAO, d.path, d.walkTopDown().sumOf { it.length() }, d.lastModified(), null, isDir = true)) }
            if (priv) listOf("data", "obb").forEach { s -> Priv.listDirs("$root/Android/$s").filter { isPkg(it) }.forEach { n ->
                val p = "$root/Android/$s/$n"; add(RItem(RCat.ORFAO, p, Priv.du(p), 0, null, priv = true, isDir = true)) } }
        }
        phase("Concluído")
    }

    /** Recupera (copia) para /Recuperados/<categoria>. Nunca altera a origem. */
    fun recover(ctx: Context, i: RItem): File {
        val dir = File(primaryRoot, "Recuperados/${i.cat.label}").apply { mkdirs() }
        val base = i.path.substringAfterLast('/').removePrefix(".trashed-").let { if (i.carved != null) it.substringBeforeLast('.') + "_" + i.carved.offset else it }
        val nm = if (i.type != null && !base.lowercase().endsWith("." + i.type) && !i.isDir) "$base.${i.type}" else base
        val dst = FileOps.uniqueTarget(dir, nm)
        when {
            i.carved != null -> Carver.extract(File(i.path), i.carved, dst)
            i.priv -> { val tmp = File(dir, ".${dst.name}.part"); if (!Priv.exec("cp -rp ${Shz.q(i.path)} ${Shz.q(tmp.path)}").let { it.first == 0 } || !tmp.renameTo(dst)) { tmp.deleteRecursively(); error("falha") } }
            else -> SafeOps.copyVerified(File(i.path), dst)
        }
        return dst
    }
}

@Composable
fun RecoverScreen(back: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var opts by remember { mutableStateOf(RecoverScan.Opts()) }
    val items = remember { mutableStateListOf<RItem>() }; var phase by remember { mutableStateOf<String?>(null) }; var job by remember { mutableStateOf<Job?>(null) }
    var cat by remember { mutableStateOf<RCat?>(null) }; var type by remember { mutableIntStateOf(0) }; var minMb by remember { mutableIntStateOf(0) }; var age by remember { mutableIntStateOf(0) }
    val sel = remember { mutableStateListOf<String>() }; var ask by remember { mutableStateOf(false) }; var done by remember { mutableStateOf<String?>(null) }; var optsOpen by remember { mutableStateOf(false) }
    fun start() { items.clear(); sel.clear(); done = null; job = scope.launch(Dispatchers.IO) {
        try { RecoverScan.scan(ctx, opts, { phase = it }) { i -> scope.launch(Dispatchers.Main) { items.add(i) } } } catch (_: CancellationException) {} finally { phase = null } } }
    val ageLim = when (age) { 1 -> 30L; 2 -> 180L; 3 -> 365L; else -> 0L } * 86400000L
    val shown = items.filter { (cat == null || it.cat == cat) && (type == 0 || (type == 1 && (it.type in IMAGE || it.type in setOf("gif", "webp", "heic"))) || (type == 2 && it.type in VIDEO) || (type == 3 && !it.isMedia))
        && it.size >= minMb * 1048576L && (ageLim == 0L || (it.modified in 1..(System.currentTimeMillis() - ageLim))) }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { Bar("Busca intensa / Recuperar", phase ?: "${items.size} achados · acesso privilegiado: ${Priv.name()}", back) {
        if (phase != null) TextButton({ job?.cancel() }) { Text("Cancelar") } else IconButton({ optsOpen = true }) { Icon(Icons.Default.Tune, "Opções") } } },
        bottomBar = { AnimatedVisibility(sel.isNotEmpty(), enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            Button({ ask = true }, Modifier.fillMaxWidth().padding(12.dp).height(56.dp)) { Icon(Icons.Default.SettingsBackupRestore, null); Spacer(Modifier.width(8.dp)); Text("Recuperar ${sel.size} para /Recuperados") } } }) { pad ->
        Column(Modifier.padding(pad)) {
            if (phase != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (items.isEmpty() && phase == null) {
                Column(Modifier.fillMaxSize().verticalScrollCompat(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(Icons.Default.ManageSearch, "Busca intensa", "Procura em lixeiras, caches de apps (WhatsApp, Telegram, galeria Samsung), miniaturas e blobs, arquivos com extensão trocada, mídias ocultas, pastas de apps desinstalados e arquivos antigos. Só lê — nada é alterado. Mídia normal da galeria fica de fora.")
                    if (!Priv.ok()) Text("Dica: ative o Shizuku (Ferramentas > Android/data) para buscar também dentro de Android/data.", Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.tertiary)
                    Button({ start() }, Modifier.padding(16.dp).fillMaxWidth(0.8f).height(56.dp)) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("Iniciar busca intensa") }
                    if (done != null) Text(done!!, color = MaterialTheme.colorScheme.secondary)
                }
                return@Column
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(cat == null, { cat = null }, { Text("Tudo ${items.size}") }) }
                items(RCat.entries.filter { c -> items.any { it.cat == c } }) { c -> FilterChip(cat == c, { cat = c }, { Text("${c.label} ${items.count { it.cat == c }}") }) } }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { listOf("Todos", "Fotos", "Vídeos", "Outros").forEachIndexed { i, s -> FilterChip(type == i, { type = i }, { Text(s) }, Modifier.padding(end = 6.dp)) } }
                item { listOf(0, 1, 10, 100).forEach { m -> FilterChip(minMb == m, { minMb = m }, { Text(if (m == 0) "Qualquer tamanho" else "≥ $m MB") }, Modifier.padding(end = 6.dp)) } }
                item { listOf("Qualquer data", "> 30 dias", "> 6 meses", "> 1 ano").forEachIndexed { i, s -> FilterChip(age == i, { age = i }, { Text(s) }, Modifier.padding(end = 6.dp)) } } }
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${shown.size} itens · ${fmtSize(shown.sumOf { it.size })}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton({ sel.clear(); sel.addAll(shown.map { it.key }) }) { Text("Selecionar todos") }
                if (phase == null) TextButton({ start() }) { Text("Nova busca") } }
            done?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.secondary) }
            LazyColumn(contentPadding = PaddingValues(8.dp)) { items(shown, key = { it.key }) { i ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { if (i.key in sel) sel.remove(i.key) else sel.add(i.key) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(i.key in sel, { if (i.key in sel) sel.remove(i.key) else sel.add(i.key) })
                    RPreview(i); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Text(i.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${i.cat.label} · ${i.type ?: "pasta"} · ${fmtSize(i.size)}" + (if (i.modified > 0) " · " + fmtDate(i.modified) else "") + (if (i.priv) " · ${Priv.name()}" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Text(i.path.removePrefix(primaryRoot.path), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                } } }
        }
    }
    if (ask) Confirm("Recuperar ${sel.size} item(ns)?", "Serão COPIADOS para /Recuperados/<categoria>. Os originais não são alterados.", "Recuperar", onDismiss = { ask = false }) {
        val list = items.filter { it.key in sel }
        scope.launch { var ok = 0; var fail = 0; withContext(Dispatchers.IO) { list.forEach { runCatching { RecoverScan.recover(ctx, it) }.onSuccess { ok++ }.onFailure { fail++ } } }
            sel.clear(); done = "✓ $ok recuperado(s) em /Recuperados" + if (fail > 0) " · $fail falharam" else "" } }
    if (optsOpen) AlertDialog({ optsOpen = false }, confirmButton = { TextButton({ optsOpen = false }) { Text("OK") } }, title = { Text("Onde procurar") }, text = { Column(Modifier.verticalScrollCompat()) {
        @Composable fun sw(l: String, v: Boolean, f: (Boolean) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) { Text(l, Modifier.weight(1f)); Switch(v, f) }
        sw("Lixeiras", opts.trash) { opts = opts.copy(trash = it) }; sw("Caches de apps", opts.caches) { opts = opts.copy(caches = it) }
        sw("Miniaturas", opts.thumbs) { opts = opts.copy(thumbs = it) }; sw("Extrair de blobs (carving)", opts.carve) { opts = opts.copy(carve = it) }
        sw("Extensão trocada", opts.magic) { opts = opts.copy(magic = it) }; sw("Mídia oculta/.nomedia", opts.hidden) { opts = opts.copy(hidden = it) }
        sw("Pastas órfãs", opts.orphans) { opts = opts.copy(orphans = it) }; sw("Arquivos antigos (> 1 ano)", opts.old) { opts = opts.copy(old = it) }
        sw("Incluir mídia normal da galeria", opts.includeGallery) { opts = opts.copy(includeGallery = it) }
        sw("Modo root (se o aparelho tiver root)", Prefs.root) { Prefs.root = it; Prefs.save(ctx) }
    } })
}

@Composable
fun RPreview(i: RItem) {
    val ctx = LocalContext.current
    val model by produceState<Any?>(null, i.key) { value = withContext(Dispatchers.IO) { when {
        i.carved != null && i.carved.length < (8 shl 20) && i.type != "mp4" -> runCatching { Carver.readRange(File(i.path), i.carved) }.getOrNull()
        !i.priv && !i.isDir && i.isMedia -> File(i.path)
        else -> null } } }
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(if (i.isDir) Icons.Default.FolderDelete else if (i.type in VIDEO) Icons.Default.Movie else Icons.Default.Image, null, tint = MaterialTheme.colorScheme.primary)
        if (model != null) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
