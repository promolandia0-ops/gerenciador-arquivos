package br.geracaoai.arquivos

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import java.io.File
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner

val Dark = darkColorScheme(primary = Color(0xFF8AB4F8), secondary = Color(0xFF80CBC4), tertiary = Color(0xFFFFB74D),
    background = Color(0xFF0F1115), surface = Color(0xFF15181E), surfaceVariant = Color(0xFF1F232B), surfaceContainer = Color(0xFF1A1D24),
    primaryContainer = Color(0xFF1E3A66), onPrimaryContainer = Color(0xFFD6E3FF))

val Light = lightColorScheme(primary = Color(0xFF2F5FA8), secondary = Color(0xFF00796B), tertiary = Color(0xFFB26A00),
    background = Color(0xFFF7F8FB), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE9EDF5), surfaceContainer = Color(0xFFEFF2F8),
    primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF0B2A5B))

object Nav { var progress by mutableFloatStateOf(0f)
    /** Posição de rolagem por pasta (índice, offset). */
    val scroll = HashMap<String, Pair<Int, Int>>()
    /** Até 8 abas de pastas recentes (mais recente primeiro), persistidas. */
    val tabs = mutableStateListOf<String>()
    val listCache = HashMap<String, List<java.io.File>>()
    var swipeDx by mutableFloatStateOf(0f)
    fun load(ctx: android.content.Context) { if (tabs.isEmpty()) tabs.addAll(Tabs.decode(ctx.getSharedPreferences("prefs", 0).getString("tabs", null) ?: ctx.getSharedPreferences("prefs", 0).getString("lastDir", null)).filter { java.io.File(it).isDirectory }) }
    fun save(ctx: android.content.Context) = ctx.getSharedPreferences("prefs", 0).edit().putString("tabs", Tabs.encode(tabs)).apply()
    fun set(ctx: android.content.Context, n: List<String>) { if (n != tabs.toList()) { tabs.clear(); tabs.addAll(n) }; save(ctx) }
    /** Definido pela tela raiz: abre uma pasta numa aba (usado por "Abrir pasta"). */
    var openFolder: (String) -> Unit = {}
}

/** Lógica pura das abas (testável). */
object Tabs {
    const val MAX = 8
    fun push(l: List<String>, dir: String, max: Int = MAX) = (listOf(dir) + l.filter { it != dir }).take(max)
    fun next(l: List<String>, cur: String?): String? = if (l.isEmpty()) null else l[(l.indexOf(cur) + 1).mod(l.size)]
    /** Abre [dir] como aba: reaproveita a aba igual; senão adiciona no fim (descarta a mais antiga se passar de [max]). Retorna lista e índice. */
    fun open(l: List<String>, dir: String, max: Int = MAX): Pair<List<String>, Int> { val i = l.indexOf(dir); if (i >= 0) return l to i
        val n = (l + dir).takeLast(max); return n to n.lastIndex }
    fun update(l: List<String>, i: Int, dir: String) = if (i in l.indices) l.toMutableList().also { it[i] = dir } else l
    fun close(l: List<String>, i: Int) = l.filterIndexed { j, _ -> j != i }
    /** Página do pager a mostrar após fechar a aba i estando na página [page] (0 = Início). */
    fun pageAfterClose(page: Int, i: Int, newSize: Int) = (if (page - 1 > i) page - 1 else if (page - 1 == i) minOf(page, newSize) else page).coerceIn(0, newSize)
    fun encode(l: List<String>) = l.joinToString("\n")
    fun decode(s: String?) = s.orEmpty().split('\n').filter { it.isNotBlank() }.take(MAX)
}
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSTS = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalAVS = compositionLocalOf<AnimatedVisibilityScope?> { null }
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable fun Modifier.shared(key: String): Modifier { val s = LocalSTS.current ?: return this; val a = LocalAVS.current ?: return this
    return with(s) { this@shared.sharedBounds(rememberSharedContentState(key), a) } }

sealed class Scr {
    data object Home : Scr(); data class Browse(val dir: String) : Scr(); data class Category(val cat: Cat) : Scr()
    data object Recent : Scr(); data object Dups : Scr(); data object Apps : Scr(); data object Hidden : Scr()
    data object Data : Scr(); data object Vault : Scr(); data object Trash : Scr(); data object Tools : Scr(); data object Recover : Scr(); data object Junk : Scr(); data object AppFolders : Scr()
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.load(this)
        setContent {
            val dark = when (Prefs.theme) { 1 -> true; 2 -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
            val view = androidx.compose.ui.platform.LocalView.current
            SideEffect { androidx.core.view.WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } }
            MaterialTheme(colorScheme = if (dark) Dark else Light) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root(this) } } }
    }
}

@Composable
fun rememberResumeTick(): Int {
    var t by remember { mutableIntStateOf(0) }
    val lo = LocalLifecycleOwner.current
    DisposableEffect(lo) { val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) t++ }; lo.lifecycle.addObserver(o); onDispose { lo.lifecycle.removeObserver(o) } }
    return t
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun Root(act: FragmentActivity) {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    val hasAll = remember(tick) { Environment.isExternalStorageManager() }
    if (!hasAll) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.FolderOpen, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Acesso a todos os arquivos", style = MaterialTheme.typography.headlineSmall)
            Text("Para gerenciar seus arquivos, ativar a lixeira e achar duplicados, permita \"Acesso a todos os arquivos\".", Modifier.padding(vertical = 12.dp))
            Button({ runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))) }
                .onFailure { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }, Modifier.fillMaxWidth()) { Text("Conceder permissão") }
        }
        return
    }
    val stack = remember { mutableStateListOf<Scr>(Scr.Home) }
    val cur = stack.last()
    fun go(s: Scr) { if (s is Scr.Browse) Nav.openFolder(s.dir) else stack.add(s) }
    fun tab(s: Scr) { stack.clear(); stack.add(s) }
    BackHandlerCompat(stack.size > 1) { stack.removeAt(stack.lastIndex) }
    remember { Nav.load(ctx); 0 }
    val pager = androidx.compose.foundation.pager.rememberPagerState { 1 + Nav.tabs.size }
    val pscope = rememberCoroutineScope()
    fun openTab(dir: String) { val (n, idx) = Tabs.open(Nav.tabs, dir); Nav.set(ctx, n); stack.clear(); stack.add(Scr.Home)
        pscope.launch { kotlinx.coroutines.withTimeoutOrNull(1000) { androidx.compose.runtime.snapshotFlow { pager.pageCount }.first { it > idx + 1 } }; if (kotlin.math.abs(pager.currentPage - (idx + 1)) > 1) pager.scrollToPage(idx + 1) else pager.animateScrollToPage(idx + 1) } }
    Nav.openFolder = ::openTab
    fun goHomePage() = pscope.launch { pager.animateScrollToPage(0) }
    val tabs = listOf(Triple(Scr.Home, "Início", Icons.Default.Home), Triple(Scr.Dups, "Duplicados", Icons.Default.VideoLibrary),
        Triple(Scr.Apps, "Apps", Icons.Default.Apps), Triple(Scr.Tools, "Ferramentas", Icons.Default.Build))
    var settings by remember { mutableStateOf(false) }
    Scaffold(bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            NavigationBarItem(selected = false, onClick = { settings = true }, icon = { Icon(Icons.Default.Menu, "Configurações") }, label = { Text("Menu") })
            tabs.forEach { (s, l, i) -> NavigationBarItem(selected = stack.first() == s, onClick = { if (s == Scr.Home && stack.size == 1 && stack[0] == Scr.Home) goHomePage(); tab(s) }, icon = { Icon(i, null) }, label = { Text(l) }) }
        }
    }) { pad ->
        if (settings) SettingsSheet { settings = false }
        Box(Modifier.padding(pad).graphicsLayer {
             val p = Nav.progress; scaleX = 1f - 0.08f * p; scaleY = 1f - 0.08f * p; alpha = 1f - 0.3f * p
            shape = RoundedCornerShape((32 * p).dp); clip = p > 0f }) {
         SharedTransitionLayout {
          AnimatedContent(cur, transitionSpec = {
              val a = initialState; val b = targetState
              val ease = androidx.compose.animation.core.FastOutSlowInEasing
              fun fwd() = (slideInHorizontally(tween(320, easing = ease)) { it / 3 } + fadeIn(tween(240)) + scaleIn(tween(320, easing = ease), initialScale = 0.96f)) togetherWith
                  (slideOutHorizontally(tween(320, easing = ease)) { -it / 5 } + fadeOut(tween(200)))
              fun bwd() = (slideInHorizontally(tween(320, easing = ease)) { -it / 5 } + fadeIn(tween(260))) togetherWith
                  (slideOutHorizontally(tween(300, easing = ease)) { it / 3 } + fadeOut(tween(220)) + scaleOut(tween(300), targetScale = 0.96f))
              val quick = tween<androidx.compose.ui.unit.IntOffset>(240, easing = ease)
              when {
                  a is Scr.Browse && b is Scr.Browse && !b.dir.startsWith(a.dir + "/") && !a.dir.startsWith(b.dir + "/") -> slideInHorizontally(quick) { it / 2 } togetherWith slideOutHorizontally(quick) { -it / 2 }
                  a == Scr.Home && b is Scr.Browse && Nav.swipeDx != 0f -> slideInHorizontally(quick) { it / 2 } togetherWith slideOutHorizontally(quick) { -it / 2 }
                  a is Scr.Browse && b == Scr.Home && Nav.swipeDx != 0f -> slideInHorizontally(quick) { -it / 2 } togetherWith slideOutHorizontally(quick) { it / 2 }
                  a is Scr.Browse && b is Scr.Browse -> if (b.dir.startsWith(a.dir + "/")) fwd() else bwd()
                  a == Scr.Home && b is Scr.Browse -> fwd()
                  a is Scr.Browse && b == Scr.Home -> bwd()
                  else -> (fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.94f)) togetherWith (fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 1.03f))
              }
          }, contentKey = { if (it is Scr.Browse) "b" + it.dir else it.toString() }, label = "nav") { cur ->
           CompositionLocalProvider(LocalSTS provides this@SharedTransitionLayout, LocalAVS provides this@AnimatedContent) {
            when (cur) {
                Scr.Home -> HomePager(pager, ::go, ::openTab) { goHomePage() }
                is Scr.Browse -> BrowserScreen(cur.dir, onNavigate = { stack[stack.lastIndex] = Scr.Browse(it) }, go = ::go, back = { stack.removeAt(stack.lastIndex) })
                is Scr.Category -> FileListScreen(cur.cat.label, { loadCategory(ctx, cur.cat) }, ::go) { stack.removeAt(stack.lastIndex) }
                Scr.Recent -> FileListScreen("Recentes", { loadRecent(ctx) }, ::go) { stack.removeAt(stack.lastIndex) }
                Scr.Dups -> DupScreen()
                Scr.Apps -> AppsScreen()
                Scr.Hidden -> HiddenMediaScreen { stack.removeAt(stack.lastIndex) }
                Scr.Data -> DataScreen { stack.removeAt(stack.lastIndex) }
                Scr.Vault -> VaultScreen(act) { stack.removeAt(stack.lastIndex) }
                Scr.Trash -> TrashScreen { stack.removeAt(stack.lastIndex) }
                Scr.Tools -> ToolsScreen(::go)
                Scr.Recover -> RecoverScreen { stack.removeAt(stack.lastIndex) }
                Scr.Junk -> JunkScreen { stack.removeAt(stack.lastIndex) }
                Scr.AppFolders -> AppFoldersScreen(::go) { stack.removeAt(stack.lastIndex) }
            }
           }
          }
         }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScrollCompat()) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
                Text("Configurações", style = MaterialTheme.typography.headlineSmall) }
            Text("Tema", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { listOf("Sistema", "Escuro", "Claro").forEachIndexed { i, s ->
                SegmentedButton(Prefs.theme == i, { Prefs.theme = i; Prefs.save(ctx) }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(s) } } }
            Text("Navegação", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
            SettingSwitch("Mostrar arquivos ocultos", "Exibe itens que começam com ponto", Prefs.showHidden) { Prefs.showHidden = it; Prefs.save(ctx) }
            SettingSwitch("Visualização em grade", "Mostra pastas e arquivos como blocos", Prefs.grid) { Prefs.grid = it; Prefs.save(ctx) }
            Text("Ordenar por", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { SORTS.forEachIndexed { i, s ->
                FilterChip(Prefs.sort == i, { Prefs.sort = i; Prefs.save(ctx) }, { Text(s) }) } }
            Text("Meu drive", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            Text("Pasta: ${driveDir().path}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DriveColorPicker(Modifier.padding(top = 10.dp))
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("Gerenciador de Arquivos · versão ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingSwitch(title: String, sub: String, v: Boolean, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(v, on)
    }
}

@Composable
fun ToolTile(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    ElevatedCard(onClick, Modifier.fillMaxWidth().padding(vertical = 5.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(16.dp))
            Column { Text(title, style = MaterialTheme.typography.titleMedium); Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(go: (Scr) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScrollCompat()) {
        Text("Ferramentas", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
        ToolTile(Icons.Default.ManageSearch, "Busca intensa / Recuperar", "Lixeiras, caches, miniaturas, blobs, extensão trocada, órfãos") { go(Scr.Recover) }
        ToolTile(Icons.Default.CleaningServices, "Buscar lixos inúteis", "Ícones, vídeos e fotos de status, miniaturas, caches (vai para a lixeira)") { go(Scr.Junk) }
        ToolTile(Icons.Default.FolderSpecial, "Pastas de apps", "Todas as pastas de aplicativos, inclusive ocultas") { go(Scr.AppFolders) }
        ToolTile(Icons.Default.VisibilityOff, "Fotos e vídeos ocultos", "Mídias escondidas em pastas com ponto e .nomedia") { go(Scr.Hidden) }
        ToolTile(Icons.Default.Android, "Android/data e obb", "Acesso via SAF ou Shizuku (Android 11 a 15)") { go(Scr.Data) }
        ToolTile(Icons.Default.Lock, "Cofre", "Esconda arquivos com criptografia AES-256 e PIN/biometria") { go(Scr.Vault) }
        ToolTile(Icons.Default.Delete, "Lixeira", "Restaure ou apague definitivamente") { go(Scr.Trash) }
        ToolTile(Icons.Default.ContentCopy, "Duplicados", "Vídeos, imagens e outros arquivos repetidos") { go(Scr.Dups) }
        ToolTile(Icons.Default.Apps, "Apps ocultos e do sistema", "Gerenciador de aplicativos") { go(Scr.Apps) }
        Text("Versão ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
fun Modifier.verticalScrollCompat(): Modifier = this.verticalScroll(rememberScrollState())
