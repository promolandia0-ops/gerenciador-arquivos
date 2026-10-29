package br.geracaoai.arquivos

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

fun fmtSize(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("KB", "MB", "GB", "TB"); var v = b / 1024.0; var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return String.format(Locale("pt", "BR"), "%.1f %s", v, u[i])
}
fun fmtDate(t: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(t))

val VIDEO = setOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "m4v", "flv", "wmv", "ts")
val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng")
val AUDIO = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr")
val DOCS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "odt", "rtf", "epub", "zip", "rar", "7z")
enum class Cat(val label: String, val exts: Set<String>?) {
    VIDEOS("Vídeos", VIDEO), IMAGENS("Imagens", IMAGE), AUDIO("Áudio", br.geracaoai.arquivos.AUDIO), DOCS("Documentos", br.geracaoai.arquivos.DOCS), APKS("APKs", setOf("apk", "apks", "xapk")), DOWNLOADS("Downloads", null)
}
val File.ext get() = extension.lowercase()
fun mimeOf(f: File): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.ext) ?: "*/*"

data class Vol(val name: String, val root: File)
fun volumes(ctx: Context): List<Vol> {
    val sm = ctx.getSystemService(StorageManager::class.java)
    return sm.storageVolumes.mapNotNull { v -> v.directory?.let { Vol(if (v.isPrimary) "Armazenamento interno" else (v.getDescription(ctx) ?: "Cartão SD"), it) } }
        .ifEmpty { listOf(Vol("Armazenamento interno", Environment.getExternalStorageDirectory())) }
}
fun stat(f: File): Pair<Long, Long> = runCatching { val s = StatFs(f.path); (s.totalBytes - s.availableBytes) to s.totalBytes }.getOrDefault(0L to 0L)

fun toast(ctx: Context, m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
fun uriOf(ctx: Context, f: File) = FileProvider.getUriForFile(ctx, ctx.packageName + ".fp", f)

fun openWith(ctx: Context, f: File, chooser: Boolean = false) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(ctx, f), mimeOf(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(if (chooser) Intent.createChooser(i, "Abrir com").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else i) }
    catch (e: ActivityNotFoundException) { toast(ctx, "Nenhum app para abrir este arquivo") }
}
fun share(ctx: Context, files: List<File>) {
    val uris = ArrayList(files.filter { it.isFile }.map { uriOf(ctx, it) })
    if (uris.isEmpty()) return toast(ctx, "Selecione arquivos (não pastas)")
    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mimeOf(files.first { it.isFile }))
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
    ctx.startActivity(Intent.createChooser(i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartilhar").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Percorre recursivamente (pula Android/data e obb, inacessíveis). */
fun walk(root: File, includeHidden: Boolean = true, filter: (File) -> Boolean): List<File> {
    val out = ArrayList<File>()
    root.walkTopDown().onEnter { d -> (includeHidden || !d.name.startsWith(".") || d == root) && !(d.parentFile?.name == "Android" && (d.name == "data" || d.name == "obb")) }
        .forEach { if (it.isFile && filter(it)) out += it }
    return out
}

object FileOps {
    fun uniqueTarget(dstDir: File, name: String): File {
        var dst = File(dstDir, name); var n = 1
        val base = name.substringBeforeLast('.', name); val ext = name.substringAfterLast('.', "")
        while (dst.exists()) dst = File(dstDir, "$base (${n++})" + if (ext.isNotEmpty() && ext != name) ".$ext" else "")
        return dst
    }
    fun copy(src: File, dstDir: File): File { val d = uniqueTarget(dstDir, src.name); SafeOps.copyVerified(src, d); return d }
    fun move(src: File, dstDir: File): File { val d = uniqueTarget(dstDir, src.name); SafeOps.moveVerified(src, d); return d }
}

object Trash {
    fun dir(ctx: Context) = File(Environment.getExternalStorageDirectory(), ".LixeiraGerenciador").apply { mkdirs(); File(this, ".nomedia").createNewFile() }
    fun moveToTrash(ctx: Context, f: File): Boolean {
        val d = dir(ctx); val id = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val dst = File(d, id + "__" + f.name)
        File(d, dst.name + ".meta").writeText(f.absolutePath)
        val ok = runCatching { SafeOps.moveVerified(f, dst); true }.getOrDefault(false)
        if (!ok) File(d, dst.name + ".meta").delete()
        return ok
    }
    data class Item(val data: File, val original: String) { val name get() = original.substringAfterLast('/') }
    fun list(ctx: Context) = dir(ctx).listFiles { x -> x.name.endsWith(".meta") }.orEmpty()
        .mapNotNull { m -> val d = File(m.parentFile, m.name.removeSuffix(".meta")); if (d.exists()) Item(d, m.readText()) else { m.delete(); null } }
        .sortedByDescending { it.data.name }
    fun restore(ctx: Context, it: Item): Boolean {
        var t = File(it.original); t.parentFile?.mkdirs()
        if (t.exists()) t = File(t.parentFile, "restaurado_" + t.name)
        var ok = runCatching { SafeOps.moveVerified(it.data, t); true }.getOrDefault(false)
        if (!ok && it.original.contains("/Android/") && Shz.granted()) ok = Shz.exec("mv ${Shz.q(it.data.path)} ${Shz.q(t.path)}").first == 0
        if (ok) File(it.data.path + ".meta").delete()
        return ok
    }
    fun purge(it: Item) { it.data.deleteRecursively(); File(it.data.path + ".meta").delete() }
}
