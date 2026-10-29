package br.geracaoai.arquivos

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------------- DUPLICADOS ----------------
@Composable
fun DupScreen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var type by remember { mutableIntStateOf(0) } // 0 vídeos, 1 imagens, 2 todos
    var groups by remember { mutableStateOf<List<List<File>>?>(null) }
    var phase by remember { mutableStateOf<String?>(null) }; var prog by remember { mutableFloatStateOf(0f) }
    val sel = remember { mutableStateListOf<File>() }
    var ask by remember { mutableStateOf(false) }; var freed by remember { mutableStateOf<String?>(null) }
    fun scan() { sel.clear(); groups = null; phase = "Listando arquivos…"; prog = 0f; scope.launch {
        groups = withContext(Dispatchers.IO) {
            val minSize = if (type == 2) 100 * 1024L else 1L
            val files = volumes(ctx).flatMap { v -> walk(v.root, true) { f -> !f.path.contains("/.LixeiraGerenciador") && f.length() >= minSize && when (type) { 0 -> f.ext in VIDEO; 1 -> f.ext in IMAGE; else -> true } } }
            phase = "Comparando ${files.size} arquivos (tamanho → hash parcial → hash completo)…"
            DupCore.findDuplicates(files) { d, t -> prog = if (t == 0) 1f else d.toFloat() / t }
        }; phase = null } }
    val total = remember(sel.size) { sel.sumOf { it.length() } }
    Scaffold(topBar = { Bar("Duplicados", groups?.let { g -> "${g.size} grupos · ${fmtSize(g.sumOf { it[0].length() * (it.size - 1) })} recuperáveis" }, null) },
        bottomBar = { AnimatedVisibility(sel.isNotEmpty(), enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            Button({ ask = true }, Modifier.fillMaxWidth().padding(12.dp).height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("Mover ${sel.size} para lixeira · libera ${fmtSize(total)}") } } }) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Vídeos", "Imagens", "Todos").forEachIndexed { i, s -> FilterChip(type == i, { type = i }, { Text(s) }, enabled = phase == null) }
                Spacer(Modifier.weight(1f)); FilledTonalButton({ scan() }, enabled = phase == null) { Text(if (groups == null) "Escanear" else "Reescanear") }
            }
            val g = groups
            if (g != null && g.isNotEmpty()) Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip({ sel.clear(); g.forEach { sel.addAll(DupCore.autoSelect(it, true)) } }, { Text("Manter mais novo") }, leadingIcon = { Icon(Icons.Default.NewReleases, null) })
                AssistChip({ sel.clear(); g.forEach { sel.addAll(DupCore.autoSelect(it, false)) } }, { Text("Manter mais antigo") }, leadingIcon = { Icon(Icons.Default.History, null) })
                if (sel.isNotEmpty()) AssistChip({ sel.clear() }, { Text("Limpar") })
            }
            freed?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold) }
            when {
                phase != null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator({ prog }, Modifier.size(72.dp)); Spacer(Modifier.height(16.dp)); Text(phase!!); Text("${(prog * 100).toInt()}%") }
                g == null -> EmptyState(Icons.Default.VideoLibrary, "Encontre vídeos duplicados", "Compara tamanho, depois hash de início/meio/fim e confirma com SHA-256 completo. O escaneamento não altera nada.")
                g.isEmpty() -> EmptyState(Icons.Default.Verified, "Nenhum duplicado encontrado")
                else -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(g, key = { it[0].path }) { grp ->
                        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(12.dp)) {
                            Text("${grp.size} cópias · ${fmtSize(grp[0].length())} cada", style = MaterialTheme.typography.titleSmall)
                            grp.forEach { f -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { toggle(sel, f) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(f in sel, { toggle(sel, f) })
                                Box(Modifier.clickable { openWith(ctx, f) }) { Thumb(f, 64) }; Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(f.parent!!.removePrefix(primaryRoot.path).ifEmpty { "/" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(fmtDate(f.lastModified()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            } }
                            if (grp.all { it in sel }) Text("⚠ Todas as cópias marcadas — este grupo será ignorado para não perder o arquivo.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        } }
                    } }
            }
        }
    }
    if (ask) Confirm("Mover duplicados para a lixeira?", "${sel.size} arquivo(s), ${fmtSize(total)}. Sempre fica pelo menos 1 cópia de cada grupo. Você pode restaurar pela Lixeira.", "Mover", onDismiss = { ask = false }) {
        val g = groups.orEmpty(); val chosen = g.flatMap { grp -> if (grp.all { it in sel }) emptyList() else grp.filter { it in sel } }
        scope.launch { var bytes = 0L; var n = 0
            withContext(Dispatchers.IO) { chosen.forEach { f -> val s = f.length(); if (Trash.moveToTrash(ctx, f)) { bytes += s; n++ } } }
            freed = "✓ $n arquivo(s) na lixeira · ${fmtSize(bytes)} liberados"
            groups = g.map { grp -> grp.filter { it.exists() } }.filter { it.size > 1 }; sel.clear() }
    }
}

// ---------------- APPS ----------------
@Composable
fun AppsScreen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppItem>?>(null) }; var filter by remember { mutableIntStateOf(0) }; var q by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<AppItem?>(null) }; val tick = rememberResumeTick()
    LaunchedEffect(tick) { apps = withContext(Dispatchers.IO) { Apps.load(ctx) } }
    val shown = remember(apps, filter, q) { apps.orEmpty().filter { a -> when (filter) { 1 -> !a.system; 2 -> a.system; 3 -> a.hidden; else -> true } && (q.isEmpty() || a.label.contains(q, true) || a.pkg.contains(q, true)) } }
    Column {
        Bar("Aplicativos", apps?.let { "${shown.size} de ${it.size} · ${it.count { a -> a.hidden }} sem ícone (ocultos)" } ?: "Carregando…", null)
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), placeholder = { Text("Buscar app ou pacote") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(28.dp))
        LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { listOf("Todos", "Usuário", "Sistema", "Ocultos").forEachIndexed { i, s -> FilterChip(filter == i, { filter = i }, { Text(s) }, Modifier.padding(end = 8.dp)) } } }
        if (!Apps.usageGranted(ctx)) TextButton({ ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, Modifier.padding(horizontal = 4.dp)) {
            Text("Permitir 'Acesso ao uso' para ver tamanho total (app+dados)") }
        if (apps == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(contentPadding = PaddingValues(8.dp)) { items(shown, key = { it.pkg }) { a ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { open = a }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(a, 44); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${a.pkg} · ${fmtSize(a.size)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (a.hidden) Badge(containerColor = MaterialTheme.colorScheme.tertiary) { Text("oculto") }
                if (a.system) Badge(Modifier.padding(start = 4.dp)) { Text("sistema") }
            } } }
    }
    open?.let { a -> AlertDialog({ open = null }, confirmButton = { TextButton({ open = null }) { Text("Fechar") } }, icon = { AppIcon(a, 56) }, title = { Text(a.label) }, text = {
        Column { Text("Pacote: ${a.pkg}"); Text("Versão: ${a.version}"); Text("Tamanho: ${fmtSize(a.size)}"); Text("APK: ${a.apk}", style = MaterialTheme.typography.bodySmall)
            Text("Dados: ${a.dataDir}", style = MaterialTheme.typography.bodySmall); Text("Android/data: ${primaryRoot.path}/Android/data/${a.pkg}", style = MaterialTheme.typography.bodySmall)
            Text(if (a.hidden) "Sem ícone na tela inicial (app oculto)" else "Com ícone na tela inicial", color = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(10.dp))
            FlowRowCompat { 
                FilledTonalButton({ if (!Apps.open(ctx, a.pkg)) toast(ctx, "Este app não tem tela inicial") }) { Text("Abrir") }
                FilledTonalButton({ Apps.info(ctx, a.pkg) }) { Text("Info do app") }
                FilledTonalButton({ scope.launch { val r = withContext(Dispatchers.IO) { runCatching { Apps.extract(a) } }; toast(ctx, r.map { "APK salvo em Download/${it.name}" }.getOrElse { "Falha ao extrair" }) } }) { Text("Extrair APK") }
                FilledTonalButton({ Apps.uninstall(ctx, a.pkg) }, colors = ButtonDefaults.filledTonalButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Desinstalar") }
            } } })
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun FlowRowCompat(content: @Composable () -> Unit) = FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
@Composable fun AppIcon(a: AppItem, size: Int) { val bmp = remember(a.pkg) { a.icon?.let { runCatching { it.toBitmap(128, 128).asImageBitmap() }.getOrNull() } }
    if (bmp != null) Image(bmp, null, Modifier.size(size.dp)) else Icon(Icons.Default.Android, null, Modifier.size(size.dp)) }

// ---------------- ANDROID/DATA ----------------
@Composable
fun DataScreen(back: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    val shzInstalled = remember(tick) { Shz.installed(ctx) }; val shzRun = remember(tick) { Shz.running() }; val shzOk = remember(tick) { Shz.granted() }
    var safUris by remember { mutableStateOf(Saf.granted(ctx)) }
    var mode by remember { mutableStateOf<String?>(null) } // "shz" ou uri da árvore
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.data?.let { u -> if (Saf.persist(ctx, u)) { safUris = Saf.granted(ctx); toast(ctx, "Acesso concedido: " + DocumentsContract.getTreeDocumentId(u)) } else toast(ctx, "O Android bloqueou esta pasta") } }
    var pkgPick by remember { mutableStateOf(false) }; var guide by remember { mutableStateOf(false) }
    if (mode != null) { DataBrowser(mode!!) { mode = null }; return }
    Column(Modifier.fillMaxSize().verticalScrollCompat()) {
        Bar("Android/data e obb", "Android ${android.os.Build.VERSION.RELEASE} (API ${sdkInt()})", back)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val best = when { shzOk -> "Shizuku (recomendado no Android 13+)"; safUris.isNotEmpty() -> "SAF (permissão de pasta)"; else -> "Nenhum ainda" }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(16.dp)) {
                Text("Método detectado", style = MaterialTheme.typography.labelLarge); Text(best, style = MaterialTheme.typography.titleLarge) } }
            // Shizuku
            ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Método 1 · Shizuku", style = MaterialTheme.typography.titleMedium)
                Text("Instalado: ${if (shzInstalled) "sim" else "não"} · Rodando: ${if (shzRun) "sim" else "não"} · Permissão: ${if (shzOk) "concedida" else "não"}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when { shzOk -> Button({ mode = "shz:/storage/emulated/0/Android/data" }) { Text("Abrir data") }
                        shzRun -> Button({ Shz.request() }) { Text("Conceder permissão") }
                        shzInstalled -> Button({ ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { ctx.startActivity(it) } }) { Text("Abrir Shizuku") }
                        else -> Button({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Instalar Shizuku") } }
                    if (shzOk) OutlinedButton({ mode = "shz:/storage/emulated/0/Android/obb" }) { Text("Abrir obb") }
                    TextButton({ guide = !guide }) { Text(if (guide) "Fechar guia" else "Como configurar") } }
                AnimatedVisibility(guide) { Text(SHIZUKU_GUIDE, style = MaterialTheme.typography.bodySmall) }
            } }
            // SAF
            ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Método 2 · Permissão de pasta (SAF)", style = MaterialTheme.typography.titleMedium)
                Text(if (sdkInt() >= 33) "No Android 13+ o Google bloqueou a pasta Android/data inteira; tentamos por pacote (Android/data/<app>). Pode funcionar ou não dependendo da versão/patch." else "No Android 11/12: toque em Conceder e depois em \"Usar esta pasta\".", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ picker.launch(Saf.pickIntent("data")) }) { Text("data") }; OutlinedButton({ picker.launch(Saf.pickIntent("obb")) }) { Text("obb") }
                    OutlinedButton({ pkgPick = true }) { Text("Por app…") } }
                safUris.forEach { u -> val id = DocumentsContract.getTreeDocumentId(u)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { mode = u.toString() }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(id.removePrefix("primary:"), Modifier.weight(1f))
                        IconButton({ ctx.contentResolver.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); safUris = Saf.granted(ctx) }) { Icon(Icons.Default.LinkOff, "Remover") } } }
            } }
        }
    }
    if (pkgPick) { val pkgs = remember { (File(primaryRoot, "Android/data").list()?.toList().takeUnless { it.isNullOrEmpty() }
            ?: ctx.packageManager.getInstalledApplications(0).filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }.map { it.packageName }).sorted() }
        var q by remember { mutableStateOf("") }
        AlertDialog({ pkgPick = false }, confirmButton = { TextButton({ pkgPick = false }) { Text("Fechar") } }, title = { Text("Escolha o app") }, text = { Column {
            OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Filtrar") })
            LazyColumn(Modifier.heightIn(max = 380.dp)) { items(pkgs.filter { it.contains(q, true) }) { p ->
                Text(p, Modifier.fillMaxWidth().clickable { pkgPick = false; picker.launch(Saf.pickIntent("data/$p")) }.padding(10.dp)) } } } })
    }
}

const val SHIZUKU_GUIDE = """1. Instale o Shizuku pela Play Store.
2. Ative Opções do desenvolvedor: Configurações > Sobre o telefone > Informações do software > toque 7x em "Número de compilação".
3. Conecte-se a uma rede Wi-Fi. Em Configurações > Opções do desenvolvedor, ative "Depuração sem fio".
4. Abra o Shizuku > "Iniciar via depuração sem fio" > "Emparelhamento". Abra Depuração sem fio > "Parear dispositivo com código" e digite o código na notificação do Shizuku.
5. Volte ao Shizuku e toque em "Iniciar". Deve aparecer "Shizuku está em execução".
6. Volte aqui e toque em "Conceder permissão" > Permitir.
Obs.: após reiniciar o celular, repita o passo 5 (Iniciar)."""

data class DEntry(val name: String, val isDir: Boolean, val size: Long, val modified: Long, val path: String, val doc: Saf.Doc?)

@Composable
fun DataBrowser(mode: String, close: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val isShz = mode.startsWith("shz:")
    val tree = if (isShz) null else Uri.parse(mode)
    val stack = remember { mutableStateListOf(if (isShz) mode.removePrefix("shz:") else DocumentsContract.getTreeDocumentId(tree)) }
    var list by remember { mutableStateOf<List<DEntry>?>(null) }; var refresh by remember { mutableIntStateOf(0) }
    var act by remember { mutableStateOf<DEntry?>(null) }; var del by remember { mutableStateOf<DEntry?>(null) }
    val cur = stack.last()
    LaunchedEffect(cur, refresh) { list = null; list = withContext(Dispatchers.IO) {
        if (isShz) Shz.list(cur).map { DEntry(it.name, it.isDir, it.size, it.modified, it.path, null) }
        else Saf.list(ctx, tree!!, cur).map { DEntry(it.name, it.isDir, it.size, it.modified, it.id, it) } } }
    BackHandlerCompat(true) { if (stack.size > 1) stack.removeAt(stack.lastIndex) else close() }
    val exportDir = File(primaryRoot, "Download/Exportado do Android-data")
    Scaffold(topBar = { Bar(cur.substringAfterLast('/').substringAfterLast(':'), (if (isShz) "Shizuku · " else "SAF · ") + cur, { if (stack.size > 1) stack.removeAt(stack.lastIndex) else close() }) {
        if (isShz && Clip.files.isNotEmpty()) IconButton({ scope.launch { val n = withContext(Dispatchers.IO) { Clip.files.count { Shz.copyIn(it.path, cur) } }; toast(ctx, "$n copiado(s) para cá"); Clip.files.clear(); refresh++ } }) { Icon(Icons.Default.ContentPaste, "Colar") } } }) { pad ->
        Box(Modifier.padding(pad)) { val l = list
            when { l == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                l.isEmpty() -> EmptyState(Icons.Default.FolderOff, "Vazio ou sem acesso", if (isShz) "Verifique se o Shizuku continua rodando." else "O Android pode ter bloqueado esta pasta. Tente o Shizuku.")
                else -> LazyColumn(contentPadding = PaddingValues(8.dp)) { items(l, key = { it.path }) { e ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { if (e.isDir) stack.add(e.path) else act = e }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (e.isDir) Icons.Default.Folder else Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((if (e.isDir) "Pasta" else fmtSize(e.size)) + " · " + fmtDate(e.modified), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton({ act = e }) { Icon(Icons.Default.MoreVert, null) } } } } } }
    }
    act?.let { e -> AlertDialog({ act = null }, confirmButton = { TextButton({ act = null }) { Text("Fechar") } }, title = { Text(e.name) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton({ act = null; scope.launch { val ok = withContext(Dispatchers.IO) { exportDir.mkdirs()
            if (isShz) Shz.copy(e.path, exportDir.path) else if (!e.isDir) Saf.copyOut(ctx, e.doc!!, FileOps.uniqueTarget(exportDir, e.name)) else false }
            toast(ctx, if (ok) "Copiado para Download/Exportado do Android-data" else "Falha ao copiar") } }, Modifier.fillMaxWidth()) { Text("Copiar para Download") }
        FilledTonalButton({ act = null; del = e }, Modifier.fillMaxWidth(), colors = ButtonDefaults.filledTonalButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Excluir") } } }) }
    del?.let { e -> Confirm("Excluir ${e.name}?", "Uma cópia de segurança vai para a Lixeira do app antes de excluir (para poder restaurar).", "Excluir", true, { del = null }) {
        scope.launch { val ok = withContext(Dispatchers.IO) {
            val backup = File(Trash.dir(ctx), "${System.currentTimeMillis()}_${e.name}")
            val saved = if (isShz) Shz.copy(e.path, backup.path) && File(backup, e.name).exists() else (!e.isDir && Saf.copyOut(ctx, e.doc!!, backup))
            if (saved) { val data = if (isShz) File(backup, e.name) else backup
                val id = data.name + "_b"; val tgt = File(Trash.dir(ctx), id); data.renameTo(tgt); File(Trash.dir(ctx), "$id.meta").writeText(if (isShz) e.path else "${primaryRoot.path}/${e.path.substringAfter(':')}"); if (isShz) backup.delete()
                if (isShz) Shz.delete(e.path) else Saf.delete(ctx, e.doc!!) } else false }
            toast(ctx, if (ok) "Excluído (cópia na Lixeira)" else "Não excluído: não foi possível fazer cópia de segurança"); refresh++ } } }
}

// ---------------- COFRE ----------------
@Composable
fun VaultScreen(act: FragmentActivity, back: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var unlocked by remember { mutableStateOf(false) }
    if (!unlocked) { PinLock(act, { unlocked = true }, back); return }
    var items by remember { mutableStateOf<List<Vault.Item>?>(null) }; var refresh by remember { mutableIntStateOf(0) }; var del by remember { mutableStateOf<Vault.Item?>(null) }
    LaunchedEffect(refresh) { items = withContext(Dispatchers.IO) { Vault.list(ctx) } }
    Scaffold(topBar = { Bar("Cofre", items?.let { "${it.size} arquivos · ${fmtSize(it.sumOf { i -> i.size })} · AES-256" }, back) { IconButton({ unlocked = false }) { Icon(Icons.Default.Lock, "Trancar") } } }) { pad ->
        Box(Modifier.padding(pad)) { val l = items
            when { l == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                l.isEmpty() -> EmptyState(Icons.Default.Shield, "Cofre vazio", "No navegador, segure um arquivo > ⋮ > \"Mover para o cofre\".")
                else -> LazyColumn(contentPadding = PaddingValues(8.dp)) { items(l, key = { it.id }) { it ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { scope.launch { val f = withContext(Dispatchers.IO) { runCatching { Vault.peek(ctx, it) }.getOrNull() }; f?.let { openWith(ctx, it) } } }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumb(File(it.name), 44); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(fmtSize(it.size) + " · " + it.original, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton({ scope.launch { val r = withContext(Dispatchers.IO) { Vault.restore(ctx, it) }; toast(ctx, r?.let { "Restaurado em ${it.parent}" } ?: "Falha ao restaurar"); refresh++ } }) { Icon(Icons.Default.Restore, "Restaurar") }
                        IconButton({ del = it }) { Icon(Icons.Default.DeleteForever, "Excluir") } } } } } }
    }
    del?.let { d -> Confirm("Excluir do cofre?", "${d.name} será apagado para sempre.", "Excluir", true, { del = null }) { Vault.remove(ctx, d); refresh++ } }
}

@Composable
fun PinLock(act: FragmentActivity, ok: () -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val has = remember { Vault.hasPin(ctx) }
    var pin by remember { mutableStateOf("") }; var pin2 by remember { mutableStateOf("") }; var err by remember { mutableStateOf<String?>(null) }
    val bioOk = remember { BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS }
    fun bio() { BiometricPrompt(act, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = ok() }).authenticate(
        BiometricPrompt.PromptInfo.Builder().setTitle("Abrir cofre").setNegativeButtonText("Usar PIN").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK).build()) }
    LaunchedEffect(Unit) { if (has && bioOk) bio() }
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Default.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(20.dp)); Text(if (has) "Digite o PIN do cofre" else "Crie um PIN (4+ dígitos)", style = MaterialTheme.typography.titleLarge)
        val kb = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        OutlinedTextField(pin, { if (it.all(Char::isDigit) && it.length <= 12) pin = it }, Modifier.padding(top = 16.dp), label = { Text("PIN") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = kb, singleLine = true)
        if (!has) OutlinedTextField(pin2, { if (it.all(Char::isDigit) && it.length <= 12) pin2 = it }, Modifier.padding(top = 8.dp), label = { Text("Confirme o PIN") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = kb, singleLine = true)
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Button({ if (has) { if (Vault.check(ctx, pin)) ok() else { err = "PIN incorreto"; pin = "" } }
            else if (pin.length < 4) err = "Mínimo 4 dígitos" else if (pin != pin2) err = "Os PINs não conferem" else { Vault.setPin(ctx, pin); ok() } }, Modifier.padding(top = 16.dp).fillMaxWidth()) { Text(if (has) "Desbloquear" else "Criar PIN") }
        if (has && bioOk) TextButton({ bio() }) { Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(6.dp)); Text("Usar biometria") }
        TextButton(back) { Text("Voltar") }
    }
}
