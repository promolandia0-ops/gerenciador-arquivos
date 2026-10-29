package br.geracaoai.arquivos

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object Clip { val files = mutableStateListOf<File>(); var cut by mutableStateOf(false) }

object Prefs {
    var showHidden by mutableStateOf(false); var grid by mutableStateOf(false); var sort by mutableIntStateOf(0)
    var theme by mutableIntStateOf(0); var root by mutableStateOf(false)
    private var loaded = false
    fun load(c: Context) { if (loaded) return; loaded = true; val p = c.getSharedPreferences("prefs", 0)
        showHidden = p.getBoolean("h", false); grid = p.getBoolean("g", false); sort = p.getInt("s", 0); theme = p.getInt("t", 0); root = p.getBoolean("r", false) }
    fun save(c: Context) = c.getSharedPreferences("prefs", 0).edit().putBoolean("h", showHidden).putBoolean("g", grid).putInt("s", sort).putInt("t", theme).putBoolean("r", root).apply()
}
val SORTS = listOf("Nome (A-Z)", "Nome (Z-A)", "Mais recentes", "Mais antigos", "Maiores", "Menores", "Tipo")
fun sortFiles(l: List<File>, s: Int): List<File> {
    val c: Comparator<File> = when (s) {
        1 -> compareByDescending { it.name.lowercase() }; 2 -> compareByDescending { it.lastModified() }; 3 -> compareBy { it.lastModified() }
        4 -> compareByDescending { it.length() }; 5 -> compareBy { it.length() }; 6 -> compareBy({ it.ext }, { it.name.lowercase() })
        else -> compareBy { it.name.lowercase() }
    }
    return l.sortedWith(compareBy<File> { !it.isDirectory }.then(c))
}

fun iconFor(f: File): Pair<ImageVector, Color> = when {
    f.isDirectory -> (if (f.name.startsWith(".")) Icons.Default.FolderSpecial else Icons.Default.Folder) to Color(0xFF8AB4F8)
    f.ext in VIDEO -> Icons.Default.Movie to Color(0xFFEF9A9A)
    f.ext in IMAGE -> Icons.Default.Image to Color(0xFFA5D6A7)
    f.ext in AUDIO -> Icons.Default.MusicNote to Color(0xFFCE93D8)
    f.ext == "apk" -> Icons.Default.Android to Color(0xFF80CBC4)
    f.ext in setOf("zip", "rar", "7z") -> Icons.Default.FolderZip to Color(0xFFFFCC80)
    f.ext in DOCS -> Icons.Default.Description to Color(0xFF90CAF9)
    else -> Icons.AutoMirrored.Filled.InsertDriveFile to Color(0xFFB0BEC5)
}

@Composable
fun Thumb(f: File, size: Int = 44) {
    val (ic, col) = remember(f) { iconFor(f) }
    val ctx = LocalContext.current
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(col.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(ic, null, tint = col, modifier = Modifier.size((size * 0.55).dp))
        if (f.isFile && (f.ext in IMAGE || f.ext in VIDEO))
            AsyncImage(ImageRequest.Builder(ctx).data(f).size(256).crossfade(true).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(f: File, selected: Boolean, info: String? = null, onClick: () -> Unit, title: String? = null, onLong: () -> Unit) {
    val hap = LocalHapticFeedback.current
    val bg by animateColorAsStateCompat(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
        .combinedClickable(onClick = onClick, onLongClick = { hap.performHapticFeedback(HapticFeedbackType.LongPress); onLong() })
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(if (f.isDirectory) Modifier.shared("dir:" + f.path) else Modifier) { Thumb(f); if (selected) Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.BottomEnd).size(18.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title ?: f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(info ?: (if (f.isDirectory) "Pasta · " else fmtSize(f.length()) + " · ") + fmtDate(f.lastModified()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCell(f: File, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val hap = LocalHapticFeedback.current
    Column(Modifier.padding(4.dp).clip(RoundedCornerShape(16.dp))
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
        .combinedClickable(onClick = onClick, onLongClick = { hap.performHapticFeedback(HapticFeedbackType.LongPress); onLong() }).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(if (f.isDirectory) Modifier.shared("dir:" + f.path) else Modifier) { Thumb(f, 84) }; Spacer(Modifier.height(6.dp))
        Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun animateColorAsStateCompat(c: Color) = androidx.compose.animation.animateColorAsState(c, label = "c")

@Composable
fun EmptyState(icon: ImageVector, title: String, sub: String = "") {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(112.dp).clip(RoundedCornerShape(36.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(20.dp)); Text(title, style = MaterialTheme.typography.titleLarge)
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(title: String, sub: String? = null, back: (() -> Unit)?, sharedKey: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), title = { Row(verticalAlignment = Alignment.CenterVertically) { if (sharedKey != null) { Icon(Icons.Default.Folder, null, Modifier.shared(sharedKey).padding(end = 10.dp).size(28.dp), tint = Color(0xFF8AB4F8)) }
 Column { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis); if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) } } },
        navigationIcon = { if (back != null) IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } }, actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}

@Composable
fun Confirm(title: String, text: String, ok: String = "Confirmar", danger: Boolean = false, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(onDismiss, confirmButton = { TextButton({ onDismiss(); onOk() }) { Text(ok, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } }, title = { Text(title) }, text = { Text(text) })
}

@Composable
fun InputDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(onDismiss, confirmButton = { TextButton({ if (t.isNotBlank() && !t.contains('/')) { onDismiss(); onOk(t.trim()) } }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } }, title = { Text(title) }, text = { OutlinedTextField(t, { t = it }, singleLine = true) })
}

/** Barra de ações para seleção múltipla, comum a todas as listas. */
@Composable
fun SelectionActions(sel: SnapshotStateList<File>, refresh: () -> Unit, extraRename: Boolean = true) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf<String?>(null) }; var rename by remember { mutableStateOf(false) }; var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }; var zip by remember { mutableStateOf(false) }
    fun run(label: String, op: (File) -> Unit) { val l = sel.toList(); busy = label; scope.launch {
        var ok = 0; var fail = 0
        withContext(Dispatchers.IO) { l.forEach { runCatching { op(it) }.onSuccess { ok++ }.onFailure { fail++ } } }
        busy = null; sel.clear(); refresh(); toast(ctx, "$label: $ok ok" + if (fail > 0) ", $fail falharam (nada foi perdido)" else "") } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp, shape = RoundedCornerShape(24.dp), modifier = Modifier.padding(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Text("${sel.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
            IconButton({ Clip.files.clear(); Clip.files.addAll(sel); Clip.cut = false; toast(ctx, "Copiado — vá até a pasta e toque em Colar"); sel.clear() }) { Icon(Icons.Default.ContentCopy, "Copiar") }
            IconButton({ Clip.files.clear(); Clip.files.addAll(sel); Clip.cut = true; toast(ctx, "Recortado — vá até a pasta e toque em Colar"); sel.clear() }) { Icon(Icons.Default.ContentCut, "Mover") }
            IconButton({ share(ctx, sel.toList()) }) { Icon(Icons.Default.Share, "Compartilhar") }
            IconButton({ ask = "trash" }) { Icon(Icons.Default.Delete, "Lixeira") }
            Box { IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Mais") }
                DropdownMenu(menu, { menu = false }) {
                    if (sel.size == 1 && extraRename) DropdownMenuItem({ Text("Renomear") }, { menu = false; rename = true }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                    if (sel.size == 1 && sel[0].isFile) DropdownMenuItem({ Text("Abrir com…") }, { menu = false; openWith(ctx, sel[0], true) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) })
                    DropdownMenuItem({ Text("Compactar (ZIP)") }, { menu = false; zip = true }, leadingIcon = { Icon(Icons.Default.FolderZip, null) })
                    DropdownMenuItem({ Text("Mover para o cofre") }, { menu = false; ask = "vault" }, leadingIcon = { Icon(Icons.Default.Lock, null) })
                    DropdownMenuItem({ Text("Excluir permanentemente") }, { menu = false; ask = "perm" }, leadingIcon = { Icon(Icons.Default.DeleteForever, null) })
                    DropdownMenuItem({ Text("Selecionar nenhum") }, { menu = false; sel.clear() }, leadingIcon = { Icon(Icons.Default.Close, null) })
                } }
        }
    }
    val total = remember(sel.size) { sel.sumOf { if (it.isFile) it.length() else 0L } }
    when (ask) {
        "trash" -> Confirm("Mover para a lixeira?", "${sel.size} item(ns) (${fmtSize(total)}) vão para a lixeira do app e podem ser restaurados.", "Mover", onDismiss = { ask = null }) {
            run("Lixeira") { if (!Trash.moveToTrash(ctx, it)) error("x") } }
        "perm" -> Confirm("Excluir PERMANENTEMENTE?", "${sel.size} item(ns) serão apagados sem possibilidade de restaurar.", "Excluir para sempre", true, { ask = null }) {
            run("Excluídos") { if (!it.deleteRecursively()) error("x") } }
        "vault" -> Confirm("Mover para o cofre?", "Os arquivos serão criptografados e somem da galeria. Pastas são ignoradas.", "Mover", onDismiss = { ask = null }) {
            run("Cofre") { if (it.isDirectory || !Vault.add(ctx, it)) error("x") } }
    }
    if (rename && sel.size == 1) InputDialog("Renomear", sel[0].name, { rename = false }) { n ->
        val f = sel[0]; val t = File(f.parentFile, n)
        if (t.exists()) toast(ctx, "Já existe um item com esse nome") else if (!f.renameTo(t)) toast(ctx, "Não foi possível renomear")
        sel.clear(); refresh() }
    if (zip && sel.isNotEmpty()) InputDialog("Nome do ZIP", (if (sel.size == 1) sel[0].nameWithoutExtension else "Arquivos") + ".zip", { zip = false }) { n ->
        val l = sel.toList(); val dst = FileOps.uniqueTarget(l[0].parentFile!!, if (n.endsWith(".zip")) n else "$n.zip"); busy = "Compactando"
        scope.launch { val r = withContext(Dispatchers.IO) { runCatching { Zip.compress(l, dst) } }; busy = null; sel.clear(); refresh(); toast(ctx, if (r.isSuccess) "Criado ${dst.name}" else "Falha ao compactar") } }
    busy?.let { AlertDialog({}, confirmButton = {}, title = { Text(it) }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }) }
}
typealias SnapshotStateList<T> = androidx.compose.runtime.snapshots.SnapshotStateList<T>
