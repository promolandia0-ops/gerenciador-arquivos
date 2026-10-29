package br.geracaoai.arquivos

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

object Nav { var progress by mutableFloatStateOf(0f) }
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSTS = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalAVS = compositionLocalOf<AnimatedVisibilityScope?> { null }
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable fun Modifier.shared(key: String): Modifier { val s = LocalSTS.current ?: return this; val a = LocalAVS.current ?: return this
    return with(s) { this@shared.sharedBounds(rememberSharedContentState(key), a) } }

sealed class Scr {
    data object Home : Scr(); data class Browse(val dir: String) : Scr(); data class Category(val cat: Cat) : Scr()
    data object Recent : Scr(); data object Dups : Scr(); data object Apps : Scr(); data object Hidden : Scr()
    data object Data : Scr(); data object Vault : Scr(); data object Trash : Scr(); data object Tools : Scr(); data object Recover : Scr(); data object Junk : Scr()
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
    fun go(s: Scr) { stack.add(s) }
    fun tab(s: Scr) { stack.clear(); stack.add(s) }
    BackHandlerCompat(stack.size > 1) { stack.removeAt(stack.lastIndex) }
    val tabs = listOf(Triple(Scr.Home, "Início", Icons.Default.Home), Triple(Scr.Dups, "Duplicados", Icons.Default.VideoLibrary),
        Triple(Scr.Apps, "Apps", Icons.Default.Apps), Triple(Scr.Tools, "Ferramentas", Icons.Default.Build))
    Scaffold(bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            tabs.forEach { (s, l, i) -> NavigationBarItem(selected = stack.first() == s, onClick = { tab(s) }, icon = { Icon(i, null) }, label = { Text(l) }) }
        }
    }) { pad ->
        Box(Modifier.padding(pad).graphicsLayer { val p = Nav.progress; scaleX = 1f - 0.08f * p; scaleY = 1f - 0.08f * p; alpha = 1f - 0.3f * p
            shape = RoundedCornerShape((32 * p).dp); clip = p > 0f }) {
         SharedTransitionLayout {
          AnimatedContent(cur, transitionSpec = {
              (fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.94f)) togetherWith (fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 1.03f))
          }, contentKey = { if (it is Scr.Browse) "b" + it.dir else it.toString() }, label = "nav") { cur ->
           CompositionLocalProvider(LocalSTS provides this@SharedTransitionLayout, LocalAVS provides this@AnimatedContent) {
            when (cur) {
                Scr.Home -> HomeScreen(::go)
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
            }
           }
          }
         }
        }
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
        ToolTile(Icons.Default.CleaningServices, "Limpeza", "Cache, temporários, APKs instalados, pastas vazias (vai para a lixeira)") { go(Scr.Junk) }
        ToolTile(Icons.Default.VisibilityOff, "Fotos e vídeos ocultos", "Mídias escondidas em pastas com ponto e .nomedia") { go(Scr.Hidden) }
        ToolTile(Icons.Default.Android, "Android/data e obb", "Acesso via SAF ou Shizuku (Android 11 a 15)") { go(Scr.Data) }
        ToolTile(Icons.Default.Lock, "Cofre", "Esconda arquivos com criptografia AES-256 e PIN/biometria") { go(Scr.Vault) }
        ToolTile(Icons.Default.Delete, "Lixeira", "Restaure ou apague definitivamente") { go(Scr.Trash) }
        ToolTile(Icons.Default.ContentCopy, "Duplicados", "Vídeos, imagens e outros arquivos repetidos") { go(Scr.Dups) }
        ToolTile(Icons.Default.Apps, "Apps ocultos e do sistema", "Gerenciador de aplicativos") { go(Scr.Apps) }
        val ctx = LocalContext.current
        Text("Tema", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { listOf("Sistema", "Escuro", "Claro").forEachIndexed { i, s ->
            SegmentedButton(Prefs.theme == i, { Prefs.theme = i; Prefs.save(ctx) }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(s) } } }
        Text("Versão ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
fun Modifier.verticalScrollCompat(): Modifier = this.verticalScroll(rememberScrollState())
