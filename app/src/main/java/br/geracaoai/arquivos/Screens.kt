package br.geracaoai.arquivos

import android.content.Context
import android.os.Environment
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

val primaryRoot: File get() = Environment.getExternalStorageDirectory()

fun loadCategory(ctx: Context, c: Cat): List<File> =
    if (c == Cat.DOWNLOADS) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).listFiles().orEmpty().toList().let { sortFiles(it, 2) }
    else volumes(ctx).flatMap { v -> walk(v.root, false) { it.ext in c.exts!! } }.sortedByDescending { it.lastModified() }

fun loadRecent(ctx: Context): List<File> { val lim = System.currentTimeMillis() - 7L * 86400000
    return volumes(ctx).flatMap { v -> walk(v.root, false) { it.lastModified() > lim } }.sortedByDescending { it.lastModified() }.take(300) }

@Composable
fun HomeScreen(go: (Scr) -> Unit) {
    val ctx = LocalContext.current
    val vols = remember { volumes(ctx) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Arquivos", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold) }
        items(vols) { v -> val (used, total) = remember(v) { stat(v.root) }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF1E3A66), Color(0xFF2B1E4F))))
                .clickable { go(Scr.Browse(v.root.path)) }.padding(20.dp)) {
                Column { Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (v.root == primaryRoot) Icons.Default.PhoneAndroid else Icons.Default.SdCard, null, Modifier.shared("dir:" + v.root.path), tint = Color.White)
                    Spacer(Modifier.width(10.dp)); Text(v.name, style = MaterialTheme.typography.titleMedium, color = Color.White) }
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator({ if (total > 0) used.toFloat() / total else 0f }, Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), color = Color(0xFF8AB4F8), trackColor = Color(0x33FFFFFF))
                    Spacer(Modifier.height(8.dp)); Text("${fmtSize(used)} usados de ${fmtSize(total)} · ${fmtSize(total - used)} livres", color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall) }
            } }
        item { DriveCard(go) }
        item { Text("Categorias", style = MaterialTheme.typography.titleMedium) }
        item { val cats = listOf(Triple(Cat.VIDEOS, Icons.Default.Movie, Color(0xFFEF9A9A)), Triple(Cat.IMAGENS, Icons.Default.Image, Color(0xFFA5D6A7)),
                Triple(Cat.AUDIO, Icons.Default.MusicNote, Color(0xFFCE93D8)), Triple(Cat.DOCS, Icons.Default.Description, Color(0xFF90CAF9)),
                Triple(Cat.APKS, Icons.Default.Android, Color(0xFF80CBC4)), Triple(Cat.DOWNLOADS, Icons.Default.Download, Color(0xFFFFCC80)))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { cats.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (c, ic, col) -> CatTile(c.label, ic, col, Modifier.weight(1f)) { go(Scr.Category(c)) } } } } } }
        item { Text("Atalhos", style = MaterialTheme.typography.titleMedium) }
        item { ToolTile(Icons.Default.ManageSearch, "Busca intensa / Recuperar", "Ache fotos e vídeos em lixeiras, caches e miniaturas") { go(Scr.Recover) } }
        item { ToolTile(Icons.Default.History, "Recentes", "Modificados nos últimos 7 dias") { go(Scr.Recent) } }
        item { ToolTile(Icons.Default.VideoLibrary, "Vídeos duplicados", "Libere espaço apagando cópias") { go(Scr.Dups) } }
        item { ToolTile(Icons.Default.VisibilityOff, "Ocultos", "Fotos e vídeos escondidos") { go(Scr.Hidden) } }
        item { ToolTile(Icons.Default.Android, "Android/data e obb", "Dados dos aplicativos") { go(Scr.Data) } }
        item { ToolTile(Icons.Default.Lock, "Cofre", "Arquivos privados criptografados") { go(Scr.Vault) } }
        item { ToolTile(Icons.Default.Delete, "Lixeira", "Itens apagados recentemente") { go(Scr.Trash) } }
    }
}

@Composable
fun CatTile(label: String, ic: ImageVector, col: Color, m: Modifier, onClick: () -> Unit) {
    Column(m.clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick).padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(col.copy(alpha = .18f)), contentAlignment = Alignment.Center) { Icon(ic, null, tint = col) }
        Spacer(Modifier.height(8.dp)); Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(dir: String, onNavigate: (String) -> Unit, go: (Scr) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Prefs.load(ctx) }
    val d = File(dir)
    var refresh by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<File>?>(null) }
    var query by remember { mutableStateOf("") }; var searching by remember { mutableStateOf(false) }
    val sel = remember(dir) { mutableStateListOf<File>() }
    var sortMenu by remember { mutableStateOf(false) }; var newFolder by remember { mutableStateOf(false) }; var pasting by remember { mutableStateOf(false) }
    var zipAsk by remember { mutableStateOf<File?>(null) }
    fun openF(f: File) { if (f.ext == "zip") zipAsk = f else openWith(ctx, f) }
    val restricted = d.path.endsWith("/Android/data") || d.path.endsWith("/Android/obb")
    LaunchedEffect(dir, refresh, Prefs.showHidden, Prefs.sort, query) {
        files = withContext(Dispatchers.IO) {
            if (query.length >= 2) walk(d, Prefs.showHidden) { it.name.contains(query, true) }.let { sortFiles(it, Prefs.sort) }.take(1000)
            else sortFiles(d.listFiles().orEmpty().filter { Prefs.showHidden || !it.name.startsWith(".") }, Prefs.sort)
        }
    }
    BackHandlerCompat(sel.isNotEmpty()) { sel.clear() }
    BackHandlerCompat(sel.isEmpty() && d.parentFile != null && volumes(ctx).none { it.root == d }) { onNavigate(d.parent!!) }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        if (searching) TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), title = { OutlinedTextField(query, { query = it }, placeholder = { Text("Buscar em ${d.name}") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            navigationIcon = { IconButton({ searching = false; query = "" }) { Icon(Icons.Default.Close, null) } })
        else Bar(if (volumes(ctx).any { it.root == d }) "Armazenamento" else d.name, d.path, back, sharedKey = "dir:" + d.path) {
            IconButton({ searching = true }) { Icon(Icons.Default.Search, "Buscar") }
            IconButton({ Prefs.grid = !Prefs.grid; Prefs.save(ctx) }) { Icon(if (Prefs.grid) Icons.Default.ViewList else Icons.Default.GridView, "Visualização") }
            Box { IconButton({ sortMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    SORTS.forEachIndexed { i, s -> DropdownMenuItem({ Text(s) }, { Prefs.sort = i; Prefs.save(ctx); sortMenu = false }, trailingIcon = { if (Prefs.sort == i) Icon(Icons.Default.Check, null) }) }
                    HorizontalDivider()
                    DropdownMenuItem({ Text(if (Prefs.showHidden) "Esconder ocultos" else "Mostrar ocultos") }, { Prefs.showHidden = !Prefs.showHidden; Prefs.save(ctx); sortMenu = false }, leadingIcon = { Icon(Icons.Default.Visibility, null) })
                    DropdownMenuItem({ Text("Nova pasta") }, { newFolder = true; sortMenu = false }, leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) })
                    DropdownMenuItem({ Text("Selecionar tudo") }, { sel.clear(); sel.addAll(files.orEmpty()); sortMenu = false }, leadingIcon = { Icon(Icons.Default.SelectAll, null) })
                } }
        }
    }, bottomBar = { AnimatedVisibility(sel.isNotEmpty(), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) { SelectionActions(sel, { refresh++ }) } },
        floatingActionButton = { AnimatedVisibility(Clip.files.isNotEmpty() && sel.isEmpty(), enter = scaleIn(), exit = scaleOut()) {
            ExtendedFloatingActionButton(onClick = { pasting = true }, icon = { Icon(Icons.Default.ContentPaste, null) }, text = { Text((if (Clip.cut) "Mover" else "Colar") + " ${Clip.files.size} aqui") }) } }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val l = files
            when {
                l == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                restricted && l.isEmpty() -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(Icons.Default.Lock, "Pasta protegida pelo Android", "Use Shizuku ou permissão SAF para abrir Android/data e obb.")
                    Button({ go(Scr.Data) }) { Text("Abrir com acesso especial") } }
                l.isEmpty() -> EmptyState(Icons.Default.FolderOpen, if (query.isNotEmpty()) "Nada encontrado" else "Pasta vazia")
                Prefs.grid -> LazyVerticalGrid(GridCells.Adaptive(108.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                    items(l, key = { it.path }) { f -> FileCell(f, f in sel, { if (sel.isNotEmpty()) toggle(sel, f) else if (f.isDirectory) onNavigate(f.path) else openF(f) }) { toggle(sel, f) } } }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                    items(l, key = { it.path }) { f -> FileRow(f, f in sel, if (query.isNotEmpty()) f.parent else null, { if (sel.isNotEmpty()) toggle(sel, f) else if (f.isDirectory) onNavigate(f.path) else openF(f) }) { toggle(sel, f) } } }
            }
        }
    }
    zipAsk?.let { z -> AlertDialog({ zipAsk = null }, confirmButton = { TextButton({ zipAsk = null; scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { val t = FileOps.uniqueTarget(d, z.nameWithoutExtension); Zip.extract(z, t); t } }
        toast(ctx, r.map { "Extraído em ${it.name}" }.getOrElse { "Falha ao extrair: ${it.message}" }); refresh++ } }) { Text("Extrair aqui") } },
        dismissButton = { TextButton({ zipAsk = null; openWith(ctx, z, true) }) { Text("Abrir com…") } }, title = { Text(z.name) }, text = { Text(fmtSize(z.length())) }) }
    if (newFolder) InputDialog("Nova pasta", "Nova pasta", { newFolder = false }) { n -> if (!File(d, n).mkdirs()) toast(ctx, "Não foi possível criar"); refresh++ }
    if (pasting) Confirm(if (Clip.cut) "Mover aqui?" else "Copiar aqui?", "${Clip.files.size} item(ns) para ${d.path}. Cada arquivo é verificado (SHA-256) antes de apagar a origem.",
        onDismiss = { pasting = false }) {
        val list = Clip.files.toList(); val cut = Clip.cut
        scope.launch { var fail = 0
            withContext(Dispatchers.IO) { list.forEach { f -> if (d.path.startsWith(f.path + "/") || d.path == f.path) fail++ else runCatching { if (cut) FileOps.move(f, d) else FileOps.copy(f, d) }.onFailure { fail++ } } }
            Clip.files.clear(); refresh++; toast(ctx, if (fail == 0) "Concluído" else "$fail item(ns) falharam — origem preservada") }
    }
}
fun toggle(sel: MutableList<File>, f: File) { if (f in sel) sel.remove(f) else sel.add(f) }

@Composable
fun BackHandlerCompat(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.PredictiveBackHandler(enabled) { flow ->
    try { flow.collect { Nav.progress = it.progress }; Nav.progress = 0f; onBack() } catch (e: kotlin.coroutines.cancellation.CancellationException) { Nav.progress = 0f; throw e } }

@Composable
fun FileListScreen(title: String, loader: suspend () -> List<File>, go: (Scr) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf<List<File>?>(null) }; var refresh by remember { mutableIntStateOf(0) }
    val sel = remember { mutableStateListOf<File>() }
    LaunchedEffect(refresh) { files = withContext(Dispatchers.IO) { loader() } }
    BackHandlerCompat(sel.isNotEmpty()) { sel.clear() }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { Bar(title, files?.let { "${it.size} itens · ${fmtSize(it.sumOf { f -> f.length() })}" } ?: "Carregando…", back) },
        bottomBar = { AnimatedVisibility(sel.isNotEmpty()) { SelectionActions(sel, { refresh++ }) } }) { pad ->
        Box(Modifier.padding(pad)) { val l = files
            when { l == null -> LinearProgressIndicator(Modifier.fillMaxWidth()); l.isEmpty() -> EmptyState(Icons.Default.Inbox, "Nada por aqui")
                else -> LazyColumn(contentPadding = PaddingValues(8.dp)) { items(l, key = { it.path }) { f ->
                    FileRow(f, f in sel, null, { if (sel.isNotEmpty()) toggle(sel, f) else if (f.isDirectory) go(Scr.Browse(f.path)) else openWith(ctx, f) }) { toggle(sel, f) } } } } }
    }
}

@Composable
fun HiddenScreen(go: (Scr) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var res by remember { mutableStateOf<List<File>?>(null) }; var scanning by remember { mutableStateOf(false) }; var count by remember { mutableIntStateOf(0) }
    val sel = remember { mutableStateListOf<File>() }; var refresh by remember { mutableIntStateOf(0) }
    fun scan() { scanning = true; scope.launch { res = withContext(Dispatchers.IO) { val out = ArrayList<File>()
        volumes(ctx).forEach { v -> v.root.walkTopDown().onEnter { it.parentFile?.name != "Android" || it.name !in setOf("data", "obb") }.forEach { f -> count++
            if (f != v.root && (f.name.startsWith(".") && f.parentFile?.name?.startsWith(".") != true) && !f.path.contains("/.LixeiraGerenciador")) out += f } }
        out.sortedWith(compareBy({ !it.isDirectory }, { it.path })) }; scanning = false } }
    LaunchedEffect(refresh) { if (refresh > 0) scan() }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { Bar("Ocultos", res?.let { "${it.count { f -> f.isDirectory }} pastas · ${it.count { f -> f.isFile }} arquivos" }, back) {
        IconButton({ scan() }) { Icon(Icons.Default.Refresh, "Escanear") } } },
        bottomBar = { AnimatedVisibility(sel.isNotEmpty()) { SelectionActions(sel, { refresh++ }) } }) { pad ->
        Box(Modifier.padding(pad)) { val l = res
            when {
                scanning -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text("Analisados: $count", Modifier.padding(12.dp)) }
                l == null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(Icons.Default.VisibilityOff, "Scanner de ocultos", "Encontra pastas/arquivos que começam com ponto e .nomedia em todo o armazenamento. Só leitura.")
                    Button({ scan() }) { Text("Escanear agora") } }
                l.isEmpty() -> EmptyState(Icons.Default.Verified, "Nenhum item oculto")
                else -> LazyColumn(contentPadding = PaddingValues(8.dp)) { items(l, key = { it.path }) { f ->
                    FileRow(f, f in sel, f.parent?.removePrefix(primaryRoot.path)?.ifEmpty { "/" }, { if (sel.isNotEmpty()) toggle(sel, f) else go(Scr.Browse(if (f.isDirectory) f.path else f.parent!!)).also { Prefs.showHidden = true } }) { toggle(sel, f) } } }
            } }
    }
}

@Composable
fun TrashScreen(back: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Trash.Item>?>(null) }; var refresh by remember { mutableIntStateOf(0) }
    var ask by remember { mutableStateOf<Trash.Item?>(null) }; var empty by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) { items = withContext(Dispatchers.IO) { Trash.list(ctx) } }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { Bar("Lixeira", items?.let { "${it.size} itens · ${fmtSize(it.sumOf { i -> if (i.data.isFile) i.data.length() else i.data.walkTopDown().sumOf { f -> f.length() } })}" }, back) {
        if (!items.isNullOrEmpty()) TextButton({ empty = true }) { Text("Esvaziar") } } }) { pad ->
        Box(Modifier.padding(pad)) { val l = items
            when { l == null -> LinearProgressIndicator(Modifier.fillMaxWidth()); l.isEmpty() -> EmptyState(Icons.Default.DeleteOutline, "Lixeira vazia", "Itens apagados aparecem aqui e podem ser restaurados.")
                else -> LazyColumn(contentPadding = PaddingValues(8.dp)) { items(l, key = { it.data.path }) { it ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { FileRow(it.data, false, it.original, {}, it.name, {}) }
                        IconButton({ scope.launch { val ok = withContext(Dispatchers.IO) { Trash.restore(ctx, it) }; toast(ctx, if (ok) "Restaurado" else "Falha ao restaurar"); refresh++ } }) { Icon(Icons.Default.Restore, "Restaurar") }
                        IconButton({ ask = it }) { Icon(Icons.Default.DeleteForever, "Excluir") }
                    } } } } }
    }
    ask?.let { a -> Confirm("Excluir para sempre?", File(a.original).name + " não poderá ser recuperado.", "Excluir", true, { ask = null }) { Trash.purge(a); refresh++ } }
    if (empty) Confirm("Esvaziar lixeira?", "Todos os ${items?.size} itens serão apagados permanentemente.", "Esvaziar", true, { empty = false }) {
        scope.launch { withContext(Dispatchers.IO) { items?.forEach { Trash.purge(it) } }; refresh++ } }
}

val DRIVE_COLORS = listOf(listOf(Color(0xFF1E3A66), Color(0xFF2B1E4F)), listOf(Color(0xFF0F4C45), Color(0xFF123A5C)),
    listOf(Color(0xFF5A1E3A), Color(0xFF2B1E4F)), listOf(Color(0xFF5C3A0F), Color(0xFF4F1E2B)), listOf(Color(0xFF263238), Color(0xFF101418)))
val DRIVE_ACCENT = listOf(Color(0xFF8AB4F8), Color(0xFF80CBC4), Color(0xFFF48FB1), Color(0xFFFFCC80), Color(0xFFB0BEC5))

/** Pasta fixa do "drive" pessoal. Renomear o cartão muda só o rótulo, nunca a pasta (nenhum arquivo é movido). */
fun driveDir(): File = File(Environment.getExternalStorageDirectory(), "MeuDrive")

fun scanDrive(d: File): Pair<Long, Int> { var b = 0L; var n = 0
    d.walkTopDown().onFail { _, _ -> }.forEach { if (it.isFile) { b += it.length(); n++ } }; return b to n }

@Composable
fun DriveColorPicker(m: Modifier = Modifier) {
    val ctx = LocalContext.current
    Row(m, horizontalArrangement = Arrangement.spacedBy(12.dp)) { DRIVE_COLORS.forEachIndexed { i, c ->
        val sel = Prefs.driveColor == i
        val sz by androidx.compose.animation.core.animateDpAsState(if (sel) 40.dp else 34.dp, label = "sz")
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(sz).clip(androidx.compose.foundation.shape.CircleShape).background(Brush.linearGradient(c)).clickable { Prefs.driveColor = i; Prefs.save(ctx) }, contentAlignment = Alignment.Center) {
                if (sel) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) } } } }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DriveCard(go: (Scr) -> Unit) {
    val ctx = LocalContext.current
    val d = remember { driveDir().apply { mkdirs() } }
    val tick = rememberResumeTick()
    val total = remember { stat(Environment.getExternalStorageDirectory()).second }
    var scanning by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }; var colors by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { scanning = true
        val (b, n) = withContext(Dispatchers.IO) { scanDrive(d) }
        Prefs.driveBytes = b; Prefs.driveCount = n; Prefs.save(ctx); scanning = false }
    val ci = Prefs.driveColor.coerceIn(0, DRIVE_COLORS.lastIndex)
    val bytes = Prefs.driveBytes.coerceAtLeast(0)
    val prog by androidx.compose.animation.core.animateFloatAsState(if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f, label = "p")
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(DRIVE_COLORS[ci]))
        .combinedClickable(onLongClick = { rename = true }) { go(Scr.Browse(d.path)) }.padding(20.dp)) {
        Column { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.FolderSpecial, null, Modifier.shared("dir:" + d.path), tint = Color.White)
            Spacer(Modifier.width(10.dp)); Text(Prefs.driveName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Icon(Icons.Default.Palette, "Cor", Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).clickable { colors = true }.padding(6.dp), tint = Color(0xCCFFFFFF))
            Icon(Icons.Default.Edit, "Renomear", Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).clickable { rename = true }.padding(6.dp), tint = Color(0xCCFFFFFF)) }
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator({ prog }, Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), color = DRIVE_ACCENT[ci], trackColor = Color(0x33FFFFFF))
            Spacer(Modifier.height(8.dp))
            val cnt = java.text.NumberFormat.getIntegerInstance(java.util.Locale("pt", "BR")).format(Prefs.driveCount)
            Text(if (Prefs.driveBytes < 0) "Calculando…" else "${fmtSize(bytes)} · $cnt ${if (Prefs.driveCount == 1) "arquivo" else "arquivos"}" + (if (scanning) " · atualizando" else ""),
                color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall) }
    }
    if (rename) InputDialog("Renomear cartão", Prefs.driveName, { rename = false }) { n -> val t = n.trim(); if (t.isNotEmpty()) { Prefs.driveName = t.take(40); Prefs.save(ctx) } }
    if (colors) AlertDialog({ colors = false }, confirmButton = { TextButton({ colors = false }) { Text("Pronto") } }, title = { Text("Cor do cartão") }, text = { DriveColorPicker() })
}
