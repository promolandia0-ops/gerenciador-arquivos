package br.geracaoai.arquivos

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import rikka.shizuku.Shizuku

/** Acesso a Android/data e Android/obb: (a) SAF tree-URI, (b) Shizuku. */
object Saf {
    const val AUTH = "com.android.externalstorage.documents"
    fun docId(sub: String) = "primary:Android/$sub"
    fun treeUri(sub: String): Uri = DocumentsContract.buildTreeDocumentUri(AUTH, docId(sub))
    /** Intent do seletor já apontando para Android/data (ou Android/data/<pacote> no Android 13+). */
    fun pickIntent(sub: String): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUriUsingTree(treeUri(sub), docId(sub)))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        putExtra("android.provider.extra.SHOW_ADVANCED", true)
    }
    fun persist(ctx: Context, uri: Uri) = runCatching {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }.isSuccess
    fun granted(ctx: Context) = ctx.contentResolver.persistedUriPermissions.filter { it.uri.authority == AUTH && DocumentsContract.getTreeDocumentId(it.uri).contains("Android/") }.map { it.uri }

    data class Doc(val tree: Uri, val id: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)
    fun list(ctx: Context, tree: Uri, docId: String): List<Doc> {
        val ch = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val out = ArrayList<Doc>()
        runCatching {
            ctx.contentResolver.query(ch, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                while (c.moveToNext()) out += Doc(tree, c.getString(0), c.getString(1), c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, c.getLong(3), c.getLong(4))
            }
        }
        return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }
    fun delete(ctx: Context, d: Doc) = runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(d.tree, d.id)) }.getOrDefault(false)
    fun copyOut(ctx: Context, d: Doc, dst: java.io.File) = runCatching {
        ctx.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(d.tree, d.id))!!.use { i -> dst.outputStream().use { i.copyTo(it) } }; true
    }.getOrDefault(false)
    /** Teste: o método funciona neste aparelho? */
    fun works(ctx: Context) = granted(ctx).any { list(ctx, it, DocumentsContract.getTreeDocumentId(it)).isNotEmpty() }
}

object Shz {
    const val REQ = 4242
    fun running() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun granted() = running() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    fun request() { if (running()) Shizuku.requestPermission(REQ) }
    fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true }.getOrDefault(false)
    fun uid() = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    /** Executa comando como usuário shell (uid 2000) via Shizuku. */
    fun exec(cmd: String): Pair<Int, String> = runCatching {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        p.waitFor() to out
    }.getOrElse { -1 to (it.message ?: it.toString()) }

    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    data class Entry(val path: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)
    fun list(dir: String): List<Entry> {
        val (_, out) = exec("cd ${q(dir)} && for f in .* *; do [ \"\$f\" = . ] || [ \"\$f\" = .. ] || [ ! -e \"\$f\" ] || stat -c '%F|%s|%Y|%n' \"\$f\"; done")
        return out.lines().mapNotNull { l -> val p = l.split("|", limit = 4); if (p.size < 4) null else
            Entry("$dir/${p[3]}", p[3], p[0].contains("directory"), p[1].toLongOrNull() ?: 0, (p[2].toLongOrNull() ?: 0) * 1000) }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }
    fun delete(path: String) = exec("rm -rf ${q(path)}").first == 0
    fun copy(src: String, dstDir: String) = exec("mkdir -p ${q(dstDir)} && cp -r ${q(src)} ${q(dstDir)}/").first == 0
    fun copyIn(src: String, dstDir: String) = copy(src, dstDir)
    fun works() = granted() && exec("ls /storage/emulated/0/Android/data").first == 0
}

fun sdkInt() = Build.VERSION.SDK_INT

object Root {
    fun available() = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su").any { java.io.File(it).exists() }
    fun exec(cmd: String): Pair<Int, String> = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start(); val o = p.inputStream.bufferedReader().readText(); p.waitFor() to o
    }.getOrElse { -1 to (it.message ?: "") }
}

/** Shell privilegiado: Shizuku (shell) ou root (opcional). */
object Priv {
    fun ok() = Shz.granted() || (Prefs.root && Root.available())
    fun name() = if (Shz.granted()) "Shizuku" else if (Prefs.root && Root.available()) "Root" else "nenhum"
    fun exec(cmd: String) = if (Shz.granted()) Shz.exec(cmd) else if (Prefs.root) Root.exec(cmd) else (-1 to "")
    data class PF(val path: String, val size: Long, val modified: Long, val head: String)
    /** Lista arquivos (recursivo) com tamanho, data e cabeçalho hex de 16 bytes. Somente leitura. */
    fun findFiles(dir: String, minSize: Long = 0): List<PF> {
        val q = Shz.q(dir)
        val (_, out) = exec("[ -d $q ] && find $q -type f -size +${minSize / 1024}k 2>/dev/null | while IFS= read -r f; do s=\$(stat -c '%s|%Y' \"\$f\"); h=\$(head -c 16 \"\$f\" | od -An -tx1 | tr -d ' \\n'); echo \"\$s|\$h|\$f\"; done")
        return out.lines().mapNotNull { l -> val p = l.split("|", limit = 4); if (p.size < 4) null else PF(p[3], p[0].toLongOrNull() ?: 0, (p[1].toLongOrNull() ?: 0) * 1000, p[2]) }
    }
    fun listDirs(dir: String): List<String> = exec("ls -1 ${Shz.q(dir)} 2>/dev/null").second.lines().filter { it.isNotBlank() }
    fun du(path: String): Long = exec("du -sk ${Shz.q(path)} 2>/dev/null").second.trim().substringBefore('\t').substringBefore(' ').toLongOrNull()?.times(1024) ?: 0
    fun copy(src: String, dst: String) = exec("cp -p ${Shz.q(src)} ${Shz.q(dst)}").first == 0
    fun hexToBytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
