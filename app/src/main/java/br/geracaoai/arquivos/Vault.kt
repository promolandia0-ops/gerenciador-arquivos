package br.geracaoai.arquivos

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Cofre: arquivos criptografados (AES-256-GCM via Jetpack Security/Tink) no armazenamento privado do app. */
object Vault {
    private fun prefs(c: Context) = c.getSharedPreferences("cofre", Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.filesDir, "cofre").apply { mkdirs() }
    private fun key(c: Context) = MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private fun ef(c: Context, f: File) = EncryptedFile.Builder(c, f, key(c), EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()
    private fun h(pin: String, salt: String) = MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    fun hasPin(c: Context) = prefs(c).contains("pin")
    fun setPin(c: Context, pin: String) { val s = UUID.randomUUID().toString(); prefs(c).edit().putString("salt", s).putString("pin", h(pin, s)).apply() }
    fun check(c: Context, pin: String) = prefs(c).getString("pin", null) == h(pin, prefs(c).getString("salt", "")!!)

    data class Item(val id: String, val name: String, val original: String, val size: Long)
    private fun index(c: Context) = JSONObject(prefs(c).getString("idx", "{}")!!)
    private fun save(c: Context, j: JSONObject) = prefs(c).edit().putString("idx", j.toString()).apply()
    fun list(c: Context): List<Item> { val j = index(c); return j.keys().asSequence().map { k -> val o = j.getJSONObject(k); Item(k, o.getString("n"), o.getString("o"), o.getLong("s")) }.toList() }

    fun add(c: Context, f: File): Boolean = runCatching {
        val id = UUID.randomUUID().toString(); val out = File(dir(c), id)
        val srcHash = DupCore.fullHash(f)
        f.inputStream().use { i -> ef(c, out).openFileOutput().use { i.copyTo(it) } }
        // verifica: descriptografa e compara hash ANTES de apagar o original
        val md = java.security.MessageDigest.getInstance("SHA-256")
        ef(c, out).openFileInput().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        if (md.digest().joinToString("") { "%02x".format(it) } != srcHash) { out.delete(); error("verificação falhou") }
        val j = index(c); j.put(id, JSONObject().put("n", f.name).put("o", f.absolutePath).put("s", f.length())); save(c, j)
        f.delete(); true
    }.getOrDefault(false)

    fun restore(c: Context, it: Item): File? = runCatching {
        var t = File(it.original); t.parentFile?.mkdirs(); if (t.exists()) t = File(t.parentFile, "restaurado_" + t.name)
        val tmp = File(t.parentFile, ".${t.name}.part")
        ef(c, File(dir(c), it.id)).openFileInput().use { i -> tmp.outputStream().use { o -> i.copyTo(o); o.fd.sync() } }
        if (tmp.length() != it.size || !tmp.renameTo(t)) { tmp.delete(); error("falha ao restaurar") }
        remove(c, it); t
    }.getOrNull()
    fun remove(c: Context, it: Item) { File(dir(c), it.id).delete(); val j = index(c); j.remove(it.id); save(c, j) }
    /** Descriptografa para cache temporário para visualizar. */
    fun peek(c: Context, it: Item): File = File(c.cacheDir, "ver").apply { mkdirs() }.let { d -> File(d, it.name).also { t ->
        ef(c, File(dir(c), it.id)).openFileInput().use { i -> t.outputStream().use { i.copyTo(it) } } } }
}
