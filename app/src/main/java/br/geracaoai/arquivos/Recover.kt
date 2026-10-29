package br.geracaoai.arquivos

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.ui.graphics.Color
import coil.request.ImageRequest
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
    RENOMEADO("Extensão trocada"), OCULTO("Mídia oculta/.nomedia"), ORFAO("Apps desinstalados"), ANTIGO("Arquivos antigos"),
    PENDENTE("Pendentes/incompletos"), APPDADOS("Pastas de apps"), BACKUP("Backups") }

/** Um achado. Se carved != null, é um trecho dentro de [path]. Se priv, o caminho só é legível via Shizuku/root. */
data class RItem(val cat: RCat, val path: String, val size: Long, val modified: Long, val type: String?, val carved: Carver.Carved? = null, val priv: Boolean = false, val isDir: Boolean = false, val tiny: Boolean = false) {
    val key get() = path + "#" + (carved?.offset ?: -1)
    val name get() = path.substringAfterLast('/') + (carved?.let { " @${it.offset}" } ?: "")
    val isMedia get() = MediaFilter.isMediaType(type)
    val isVideo get() = type in VIDEO
}

/** Regras puras (testáveis) do foco em fotos/vídeos. */
object MediaFilter {
    const val MIN_BYTES = 10 * 1024L; const val MIN_PX = 100
    fun isMediaType(t: String?) = t != null && (t in IMAGE || t in VIDEO || t in setOf("heic", "gif", "webp"))
    fun isTiny(size: Long, w: Int, h: Int, video: Boolean) = !video && (size < MIN_BYTES || (w > 0 && h > 0 && maxOf(w, h) < MIN_PX))
    /** Decide se um achado entra na lista. Pastas nunca entram. */
    fun keep(i: RItem, others: Boolean, tiny: Boolean) = !i.isDir && (if (i.isMedia) (tiny || !i.tiny) else others)
    /** Quanto maior, mais "esquecido": antigo, em local escondido, lixeira, app desinstalado, extraído. */
    fun forgottenScore(i: RItem, now: Long = System.currentTimeMillis()): Double {
        val years = if (i.modified > 0) ((now - i.modified) / 3.15576e10).coerceIn(0.0, 8.0) else 1.0
        val cat = when (i.cat) { RCat.LIXEIRA, RCat.ORFAO -> 4.0; RCat.EXTRAIDO, RCat.OCULTO, RCat.RENOMEADO, RCat.PENDENTE, RCat.BACKUP -> 3.0
            RCat.CACHE, RCat.APPDADOS, RCat.ANTIGO -> 2.0; RCat.MINIATURA -> 1.0 }
        val hidden = if (i.path.split('/').any { it.startsWith(".") && it.length > 1 }) 2.0 else 0.0
        return years * 1.5 + cat + hidden + (if (i.priv) 1.0 else 0.0) + (if (i.isVideo) 0.5 else 0.0)
    }
    fun sort(l: List<RItem>, s: Int): List<RItem> = when (s) { 1 -> l.sortedByDescending { it.modified }; 2 -> l.sortedBy { it.modified }; 3 -> l.sortedByDescending { it.size }; 4 -> l.sortedBy { it.size }
        else -> { val now = System.currentTimeMillis(); l.sortedByDescending { forgottenScore(it, now) } } }
    private val BLOB_EXT = setOf("db", "bak", "dat", "bin", "tmp", "cache", "idx", "0", "1", "2", "journal", "wal", "blob", "data", "pack", "thumb", "thumbs", "cnt", "obj", "img")
    /** Arquivo com cara de contêiner (SQLite, cache, thumbdata, backup) que pode ter mídias embutidas. */
    fun isBlobLike(path: String, size: Long): Boolean { if (size !in 4096..(256L shl 20)) return false
        val n = path.substringAfterLast('/').lowercase(); val e = n.substringAfterLast('.', "")
        if (e == "crypt" || e.startsWith("crypt1")) return false
        val lp = path.lowercase()
        return e in BLOB_EXT || n.startsWith("thumbdata") || n.startsWith("imgcache") || "backup" in n ||
            (e.isEmpty() && ("/cache/" in lp || "/.thumb" in lp || "smartswitch" in lp || "/files/" in lp)) }
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
    private val DEEP_ROOT = listOf("com.android.chrome", "com.sec.android.app.sbrowser", "org.mozilla.firefox", "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
        "com.instagram.android", "com.facebook.katana", "com.facebook.orca", "com.snapchat.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.google.android.gm", "com.discord", "com.twitter.android", "com.android.providers.media.module", "com.google.android.providers.media.module")
    private val PUB_THUMBS = listOf("DCIM/.thumbnails", "Pictures/.thumbnails", "Movies/.thumbnails", "DCIM/.thumbs")

    fun isHiddenPath(f: File) = f.path.removePrefix(root).split('/').any { it.startsWith(".") } || generateSequence(f.parentFile) { it.parentFile }.take(6).any { File(it, ".nomedia").exists() }
    fun isGalleryMedia(f: File) = f.ext in MEDIA && !isHiddenPath(f) && !f.name.startsWith(".trashed")

    data class Opts(val trash: Boolean = true, val caches: Boolean = true, val thumbs: Boolean = true, val carve: Boolean = true, val magic: Boolean = true,
                    val hidden: Boolean = true, val orphans: Boolean = true, val old: Boolean = true, val oldDays: Int = 365, val includeGallery: Boolean = false,
                    val others: Boolean = false, val mstore: Boolean = true, val deepApps: Boolean = true, val backups: Boolean = true)

    suspend fun scan(ctx: Context, o: Opts, phase: (String) -> Unit, found: (RItem) -> Unit) = coroutineScope {
        val seen = HashSet<String>()
        fun dims(i: RItem): RItem { if (i.isVideo || !i.isMedia) return i
            var w = 0; var h = 0
            if (i.size < 300 * 1024 && !i.priv) runCatching { val op = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                if (i.carved != null) Carver.readRange(File(i.path), i.carved).let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size, op) } else android.graphics.BitmapFactory.decodeFile(i.path, op)
                w = op.outWidth; h = op.outHeight }
            return i.copy(tiny = MediaFilter.isTiny(i.size, w, h, false)) }
        val hashes = HashSet<String>()
        fun fp(i: RItem): String? = if (i.priv) null else runCatching { java.io.RandomAccessFile(i.path, "r").use { r ->
            val off = i.carved?.offset ?: 0L; val len = i.carved?.length ?: r.length(); val n = minOf(len, 65536L).toInt(); val b = ByteArray(n); r.seek(off); r.readFully(b)
            if (n == 0) return@use null
            val md = java.security.MessageDigest.getInstance("MD5").digest(b); "$len:" + md.joinToString("") { "%02x".format(it) } } }.getOrNull()
        fun add(i0: RItem) { if (i0.isDir) return; if (!i0.isMedia && !o.others) return; if (!seen.add(i0.key)) return
            if (i0.isMedia) fp(i0)?.let { if (!hashes.add(it)) return }
            found(dims(i0)) }
        val tmp = File(ctx.externalCacheDir, "scan_tmp").apply { deleteRecursively(); mkdirs() }
        val priv = Priv.ok()
        val excl = listOf("$root/.LixeiraGerenciador", "$root/Recuperados", "$root/Android/data", "$root/Android/obb")
        fun publicFiles(dir: File): Sequence<File> = dir.walkTopDown().onEnter { d -> excl.none { d.path == it } }.filter { it.isFile }
        fun carveInto(f: File, cat: RCat, displayPath: String = f.path, isPriv: Boolean = false) {
            if (!o.carve) return
            runCatching { Carver.carveFile(f).filterNot { it.offset == 0L && it.length == f.length() }.forEach { c ->
                add(RItem(if (cat == RCat.BACKUP) RCat.BACKUP else RCat.EXTRAIDO, f.path, c.length, f.lastModified(), c.type, c, false)) } }
        }
        fun handlePublic(f: File, cat: RCat) {
            val t = Carver.sniff(f)
            if (t != null) add(RItem(cat, f.path, f.length(), f.lastModified(), t)) else if (f.length() in 2048..(512L shl 20)) carveInto(f, cat)
        }
        var budget = 1500L shl 20
        fun handlePriv(dir: String, cat: RCat) {
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
        if (o.mstore) { phase("MediaStore: itens na lixeira, pendentes e incompletos…")
            runCatching { val b = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE); putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.IS_TRASHED}=1 OR ${MediaStore.MediaColumns.IS_PENDING}=1") }
                ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.IS_TRASHED), b, null)?.use { c ->
                    while (c.moveToNext()) { ensureActive(); val p = c.getString(0) ?: continue; val f = File(p); if (f.exists() && f.length() > 0) { val t = Carver.sniff(f) ?: f.ext
                        add(RItem(if (c.getInt(3) == 1) RCat.LIXEIRA else RCat.PENDENTE, p, f.length(), (c.getLong(2) * 1000).takeIf { it > 0 } ?: f.lastModified(), t)) } } } }
            // Arquivos ".pending-…" deixados por gravações/downloads interrompidos
            publicFiles(primaryRoot).filter { it.name.startsWith(".pending-") }.forEach { ensureActive(); add(RItem(RCat.PENDENTE, it.path, it.length(), it.lastModified(), Carver.sniff(it) ?: it.ext)) }
        }
        if (o.caches) { phase("Caches de WhatsApp, Telegram, Instagram, galeria…")
            PUB_CACHE.map { File(primaryRoot, it) }.filter { it.isDirectory }.forEach { d -> publicFiles(d).forEach { ensureActive(); if (it.ext in MEDIA) add(RItem(RCat.CACHE, it.path, it.length(), it.lastModified(), it.ext)) else handlePublic(it, RCat.CACHE) } }
            if (priv) PRIV_CACHE.forEach { phase("Cache (via ${Priv.name()}): ${it.substringBefore('/')}"); handlePriv("$root/Android/data/$it", RCat.CACHE) }
        }
        if (o.deepApps && priv) {
            // Varredura recursiva de TODAS as pastas de apps (Android/data, obb, media) via Shizuku/root
            listOf("data", "obb").forEach { s -> Priv.listDirs("$root/Android/$s").filter { it != ctx.packageName }.forEach { n -> ensureActive(); phase("Apps (${Priv.name()}): Android/$s/$n")
                handlePriv("$root/Android/$s/$n", if ("cache" in n) RCat.CACHE else RCat.APPDADOS) } }
            // Só com root: caches internos (/data/data) de navegadores e mensageiros
            if (Prefs.root && Root.available()) DEEP_ROOT.forEach { pk -> ensureActive(); phase("Root: /data/data/$pk"); handlePriv("/data/data/$pk/cache", RCat.CACHE); handlePriv("/data/data/$pk/files", RCat.APPDADOS); handlePriv("/data/data/$pk/app_webview", RCat.CACHE) }
        }
        if (o.thumbs) { phase("Miniaturas e thumbdata…")
            PUB_THUMBS.map { File(primaryRoot, it) }.filter { it.isDirectory }.forEach { d -> publicFiles(d).forEach { ensureActive(); handlePublic(it, RCat.MINIATURA) } }
        }
        if (o.magic || o.hidden || o.old) { phase("Varredura profunda de todo o armazenamento e cartão SD…")
            val lim = System.currentTimeMillis() - o.oldDays * 86400000L
            volumes(ctx).forEach { v -> publicFiles(v.root).forEach { f -> ensureActive()
                val hidden = isHiddenPath(f)
                if (o.magic && f.ext !in MEDIA && f.length() >= 4096 && f.ext !in setOf("apk", "zip", "pdf", "mp3", "m4a", "ogg", "opus", "jar", "db", "json", "txt")) {
                    Carver.sniff(f)?.takeIf { it != "pdf" && it != "mp3" && it != "ogg" && it != "m4a" }?.let { add(RItem(RCat.RENOMEADO, f.path, f.length(), f.lastModified(), it)) } }
                if (o.hidden && hidden) { if (f.ext in MEDIA) add(RItem(RCat.OCULTO, f.path, f.length(), f.lastModified(), f.ext))
                    else if (f.length() >= 4096) Carver.sniff(f)?.let { t -> add(RItem(RCat.OCULTO, f.path, f.length(), f.lastModified(), t)) } }
                if (o.carve && f.ext !in MEDIA && MediaFilter.isBlobLike(f.path, f.length())) carveInto(f, if (f.path.lowercase().let { "backup" in it || "smartswitch" in it || it.endsWith(".bak") }) RCat.BACKUP else RCat.EXTRAIDO)
                if (o.old && f.lastModified() < lim && f.length() >= 100 * 1024 && (o.includeGallery || !isGalleryMedia(f))) add(RItem(RCat.ANTIGO, f.path, f.length(), f.lastModified(), f.ext))
            } }
        }
        if (o.orphans) { phase("Pastas de apps desinstalados…")
            val pm = ctx.packageManager; val installed = pm.getInstalledApplications(0).map { it.packageName }.toSet()
            fun isPkg(n: String) = n.count { it == '.' } >= 1 && n !in installed && n.matches(Regex("[A-Za-z][\\w]*(\\.[\\w]+)+"))
            // Nunca lista a pasta em si: achata e mostra os arquivos de mídia de dentro.
            File(primaryRoot, "Android/media").listFiles().orEmpty().filter { it.isDirectory && isPkg(it.name) }.forEach { d ->
                d.walkTopDown().filter { it.isFile }.forEach { f -> ensureActive(); val t = if (f.ext in MEDIA) f.ext else Carver.sniff(f) ?: f.ext
                    add(RItem(RCat.ORFAO, f.path, f.length(), f.lastModified(), t)) } }
            if (priv) listOf("data", "obb").forEach { s -> Priv.listDirs("$root/Android/$s").filter { isPkg(it) }.forEach { n ->
                Priv.findFiles("$root/Android/$s/$n", 1).forEach { pf -> ensureActive()
                    val t = runCatching { Carver.sniff(Priv.hexToBytes(pf.head)) }.getOrNull() ?: pf.path.substringAfterLast('.', "").lowercase().ifEmpty { null }
                    add(RItem(RCat.ORFAO, pf.path, pf.size, pf.modified, t, priv = true)) } } }
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


/** Arquivo local legível para visualizar (cópia em cache para itens privados/extraídos). Nunca altera a origem. */
suspend fun previewFile(ctx: Context, i: RItem): File? = withContext(Dispatchers.IO) { runCatching {
    when {
        i.carved == null && !i.priv -> File(i.path)
        else -> { val d = File(ctx.externalCacheDir, "preview").apply { mkdirs() }
            val f = File(d, i.key.hashCode().toUInt().toString() + "." + (i.type ?: "bin"))
            if (!f.exists() || f.length() == 0L) { if (i.carved != null) Carver.extract(File(i.path), i.carved, f) else if (!Priv.copy(i.path, f.path)) error("x") }
            f }
    } }.getOrNull() }

fun fmtDur(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

@Composable
fun MediaFinderScreen(title: String, intro: String, initial: RecoverScan.Opts, fullOpts: Boolean, back: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var opts by remember { mutableStateOf(initial) }; var tiny by remember { mutableStateOf(false) }
    val items = remember { mutableStateListOf<RItem>() }; var phase by remember { mutableStateOf<String?>(null) }; var job by remember { mutableStateOf<Job?>(null) }
    var type by remember { mutableIntStateOf(0) }; var sort by remember { mutableIntStateOf(0) }
    val sel = remember { mutableStateListOf<String>() }; var ask by remember { mutableStateOf(false) }; var done by remember { mutableStateOf<String?>(null) }; var optsOpen by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<RItem?>(null) }
    fun start() { items.clear(); sel.clear(); done = null; job = scope.launch(Dispatchers.IO) {
        try { RecoverScan.scan(ctx, opts, { phase = it }) { i -> scope.launch(Dispatchers.Main) { items.add(i) } } } catch (_: CancellationException) {} finally { phase = null } } }
    fun recover(list: List<RItem>) = scope.launch { var ok = 0; var fail = 0; withContext(Dispatchers.IO) { list.forEach { runCatching { RecoverScan.recover(ctx, it) }.onSuccess { ok++ }.onFailure { fail++ } } }
        sel.clear(); done = "✓ $ok recuperado(s) em /Recuperados" + if (fail > 0) " · $fail falharam" else ""; toast(ctx, done!!) }
    val base = items.filter { MediaFilter.keep(it, opts.others, tiny) }
    val shown = remember(base.size, type, sort, items.size) { MediaFilter.sort(base.filter { when (type) { 1 -> it.isMedia && !it.isVideo; 2 -> it.isVideo; 3 -> !it.isMedia; else -> true } }, sort) }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { Bar(title, phase ?: "${base.count { it.isMedia && !it.isVideo }} fotos · ${base.count { it.isVideo }} vídeos" + (if (fullOpts) " · acesso: ${Priv.name()}" else ""), back) {
        if (phase != null) TextButton({ job?.cancel() }) { Text("Cancelar") } else IconButton({ optsOpen = true }) { Icon(Icons.Default.Tune, "Opções") } } },
        bottomBar = { AnimatedVisibility(sel.isNotEmpty(), enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ sel.clear() }, Modifier.height(56.dp)) { Text("Limpar") }; Spacer(Modifier.width(8.dp))
                Button({ ask = true }, Modifier.weight(1f).height(56.dp)) { Icon(Icons.Default.SettingsBackupRestore, null); Spacer(Modifier.width(8.dp)); Text("Recuperar ${sel.size}") } } } }) { pad ->
        Column(Modifier.padding(pad)) {
            if (phase != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (items.isEmpty() && phase == null) {
                Column(Modifier.fillMaxSize().verticalScrollCompat(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(Icons.Default.PhotoLibrary, title, intro)
                    if (fullOpts) Text(ROOT_NOTE, Modifier.padding(horizontal = 24.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (fullOpts && !Priv.ok()) Text("Dica: ative o Shizuku (Ferramentas > Android/data) para buscar também dentro de Android/data.", Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.tertiary)
                    Button({ start() }, Modifier.padding(16.dp).fillMaxWidth(0.8f).height(56.dp)) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("Buscar fotos e vídeos") }
                    if (done != null) Text(done!!, color = MaterialTheme.colorScheme.secondary)
                }
                return@Column
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(type == 1, { type = 1 }, { Text("Fotos") }, leadingIcon = { Icon(Icons.Default.Image, null, Modifier.size(18.dp)) }) }
                item { FilterChip(type == 2, { type = 2 }, { Text("Vídeos") }, leadingIcon = { Icon(Icons.Default.Movie, null, Modifier.size(18.dp)) }) }
                item { FilterChip(type == 0, { type = 0 }, { Text("Tudo") }) }
                if (opts.others) item { FilterChip(type == 3, { type = 3 }, { Text("Outros") }) }
                item { VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp)) }
                items(listOf("Esquecidos", "Recentes", "Antigos", "Maiores", "Menores").withIndex().toList()) { (i, s) -> FilterChip(sort == i, { sort = i }, { Text(s) }) } }
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${shown.size} itens · ${fmtSize(shown.sumOf { it.size })}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton({ sel.clear(); sel.addAll(shown.map { it.key }) }) { Text("Selecionar todos") }
                if (phase == null) TextButton({ start() }) { Text("Nova busca") } }
            done?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.secondary) }
            LazyVerticalGrid(GridCells.Adaptive(108.dp), contentPadding = PaddingValues(6.dp)) { items(shown, key = { it.key }) { i ->
                MediaCell(i, i.key in sel, onClick = { if (sel.isNotEmpty()) { if (i.key in sel) sel.remove(i.key) else sel.add(i.key) } else view = i }) { if (i.key in sel) sel.remove(i.key) else sel.add(i.key) } } }
        }
    }
    if (ask) Confirm("Recuperar ${sel.size} item(ns)?", "Serão COPIADOS para /Recuperados/<categoria>. Os originais não são alterados.", "Recuperar", onDismiss = { ask = false }) { recover(items.filter { it.key in sel }) }
    view?.let { v -> MediaViewer(v, { view = null }) { recover(listOf(v)); view = null } }
    if (optsOpen) AlertDialog({ optsOpen = false }, confirmButton = { TextButton({ optsOpen = false }) { Text("OK") } }, title = { Text("Opções") }, text = { Column(Modifier.verticalScrollCompat()) {
        @Composable fun sw(l: String, v: Boolean, f: (Boolean) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) { Text(l, Modifier.weight(1f)); Switch(v, f) }
        sw("Mostrar imagens minúsculas (< 10 KB / ícones)", tiny) { tiny = it }
        sw("Incluir outros arquivos (não mídia) — requer nova busca", opts.others) { opts = opts.copy(others = it) }
        if (fullOpts) { HorizontalDivider(Modifier.padding(vertical = 6.dp)); Text("Onde procurar", style = MaterialTheme.typography.titleSmall)
            sw("Lixeiras", opts.trash) { opts = opts.copy(trash = it) }; sw("Caches de apps", opts.caches) { opts = opts.copy(caches = it) }
            sw("Miniaturas", opts.thumbs) { opts = opts.copy(thumbs = it) }; sw("Extrair de blobs (carving)", opts.carve) { opts = opts.copy(carve = it) }
            sw("Extensão trocada", opts.magic) { opts = opts.copy(magic = it) }; sw("Mídia oculta/.nomedia", opts.hidden) { opts = opts.copy(hidden = it) }
            sw("MediaStore (lixeira/pendentes)", opts.mstore) { opts = opts.copy(mstore = it) }; sw("Todas as pastas de apps (Shizuku/root)", opts.deepApps) { opts = opts.copy(deepApps = it) }
            sw("Apps desinstalados", opts.orphans) { opts = opts.copy(orphans = it) }; sw("Arquivos antigos (> 1 ano)", opts.old) { opts = opts.copy(old = it) }
            sw("Incluir mídia normal da galeria", opts.includeGallery) { opts = opts.copy(includeGallery = it) }
            sw("Modo root (se o aparelho tiver root)", Prefs.root) { Prefs.root = it; Prefs.save(ctx) } }
    } })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCell(i: RItem, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val ctx = LocalContext.current
    val model by produceState<Any?>(null, i.key) { value = when {
        !i.isMedia -> null
        i.carved != null && !i.isVideo && i.carved.length < (8 shl 20) -> withContext(Dispatchers.IO) { runCatching { Carver.readRange(File(i.path), i.carved) }.getOrNull() }
        !i.priv && i.carved == null -> File(i.path)
        i.size < (60L shl 20) -> previewFile(ctx, i)
        else -> null } }
    val dur by produceState(0L, i.key) { if (i.isVideo && !i.priv && i.carved == null) value = withContext(Dispatchers.IO) { runCatching {
        android.media.MediaMetadataRetriever().run { try { setDataSource(i.path); extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L } finally { release() } } }.getOrDefault(0L) } }
    Column(Modifier.padding(3.dp).clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onClick, onLongClick = onLong)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(if (i.isVideo) Icons.Default.Movie else if (i.isMedia) Icons.Default.Image else Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
            if (model != null) AsyncImage(ImageRequest.Builder(ctx).data(model).size(256).memoryCacheKey(i.key).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (i.isVideo) Row(Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(12.dp), tint = Color.White); Text(if (dur > 0) fmtDur(dur) else "vídeo", color = Color.White, style = MaterialTheme.typography.labelSmall) }
            if (selected) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)))
                Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary) }
        }
        Text(i.cat.label + (if (i.priv) " · ${Priv.name()}" else ""), Modifier.padding(horizontal = 2.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MediaViewer(i: RItem, close: () -> Unit, onRecover: () -> Unit) {
    val ctx = LocalContext.current
    val file by produceState<File?>(null, i.key) { value = previewFile(ctx, i) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(i.key) { kotlinx.coroutines.delay(15000); if (file == null) failed = true }
    androidx.compose.ui.window.Dialog(close, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val f = file
            when {
                f == null -> if (failed) Text("Não foi possível abrir a prévia", Modifier.align(Alignment.Center), color = Color.White) else CircularProgressIndicator(Modifier.align(Alignment.Center))
                i.isVideo -> androidx.compose.ui.viewinterop.AndroidView({ c -> android.widget.VideoView(c).apply {
                    setMediaController(android.widget.MediaController(c).also { it.setAnchorView(this) }); setVideoPath(f.path); setOnPreparedListener { start() }
                    setOnErrorListener { _, _, _ -> toast(c, "Não deu para tocar aqui — use Abrir com"); true } } }, Modifier.align(Alignment.Center).fillMaxWidth(),
                    onRelease = { it.stopPlayback() })
                i.isMedia -> AsyncImage(f, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else -> Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, Modifier.align(Alignment.Center).size(96.dp), tint = Color.White)
            }
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(close) { Icon(Icons.Default.Close, "Fechar", tint = Color.White) }
                    Column(Modifier.weight(1f)) { Text(i.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${i.cat.label} · ${fmtSize(i.size)}" + (if (i.modified > 0) " · " + fmtDate(i.modified) else ""), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                        Text(i.path.removePrefix(primaryRoot.path), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (f != null) IconButton({ openWith(ctx, f, true) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Abrir com", tint = Color.White) }
                    IconButton(onRecover) { Icon(Icons.Default.SettingsBackupRestore, "Recuperar", tint = Color.White) }
                }
            }
        }
    }
}

@Composable
fun RecoverScreen(back: () -> Unit) = MediaFinderScreen("Busca intensa / Recuperar",
    "Mostra só FOTOS e VÍDEOS encontrados em lixeiras, caches de apps (WhatsApp, Telegram, galeria Samsung), miniaturas, blobs, extensão trocada, pastas ocultas/.nomedia, apps desinstalados e arquivos antigos. Só lê — nada é alterado.",
    RecoverScan.Opts(), true, back)

@Composable
fun HiddenMediaScreen(back: () -> Unit) = MediaFinderScreen("Fotos e vídeos ocultos",
    "Encontra fotos e vídeos escondidos (pastas com ponto, .nomedia, .thumbnails, status do WhatsApp) em todo o armazenamento. Só leitura.",
    RecoverScan.Opts(trash = false, caches = false, thumbs = false, carve = false, magic = false, hidden = true, orphans = false, old = false), false, back)

const val ROOT_NOTE = "Sinceridade: sem root, nenhum app (nem UltData, DiskDigger ou EaseUS) lê o espaço apagado da memória interna — o Android criptografa e limpa (TRIM) o que é excluído. O que dá para achar, e este app procura: lixeiras, caches e miniaturas de apps e galeria, mídias ocultas, arquivos com extensão trocada, mídias embutidas em bancos/caches/backups, apps desinstalados e itens pendentes. Com Shizuku ele entra em Android/data e obb de todos os apps; com root, também nos caches internos de navegadores e mensageiros."
