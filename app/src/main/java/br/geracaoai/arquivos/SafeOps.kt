package br.geracaoai.arquivos

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Operações seguras: nunca perde dados. Cópia em .part + verificação (tamanho+SHA-256) + rename atômico. */
object SafeOps {
    fun copyVerified(src: File, dst: File) {
        if (dst.exists()) throw IOException("Destino já existe: $dst")
        if (src.isDirectory) {
            if (!dst.mkdirs()) throw IOException("Não criou pasta $dst")
            src.listFiles()?.forEach { copyVerified(it, File(dst, it.name)) } ?: throw IOException("Não leu $src")
            dst.setLastModified(src.lastModified()); return
        }
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, ".${dst.name}.part")
        try {
            src.inputStream().use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o, 1 shl 20); o.fd.sync() } }
            if (tmp.length() != src.length() || DupCore.fullHash(tmp) != DupCore.fullHash(src)) throw IOException("Verificação falhou: $src")
            if (!tmp.renameTo(dst)) throw IOException("Rename falhou: $dst")
            dst.setLastModified(src.lastModified())
        } catch (e: Exception) { tmp.delete(); throw e }
    }

    /** Move: rename atômico no mesmo volume; senão cópia verificada e só então apaga a origem. */
    fun moveVerified(src: File, dst: File) {
        if (dst.exists()) throw IOException("Destino já existe: $dst")
        if (!src.exists()) throw IOException("Origem não existe: $src")
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) return
        copyVerified(src, dst)
        if (!src.deleteRecursively()) throw IOException("Copiado, mas a origem não pôde ser apagada: $src")
    }
}
