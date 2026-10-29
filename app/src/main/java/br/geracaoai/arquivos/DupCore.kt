package br.geracaoai.arquivos

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Lógica pura (testável em JVM) para achar duplicados: tamanho -> hash parcial -> hash completo. */
object DupCore {
    const val CHUNK = 64 * 1024

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Hash de início + meio + fim + tamanho. */
    fun partialHash(f: File, chunk: Int = CHUNK): String {
        val md = MessageDigest.getInstance("SHA-256")
        val len = f.length()
        md.update(len.toString().toByteArray())
        RandomAccessFile(f, "r").use { raf ->
            val buf = ByteArray(chunk)
            val offsets = if (len <= chunk * 3L) listOf(0L) else listOf(0L, len / 2 - chunk / 2, len - chunk)
            for (o in offsets) {
                raf.seek(o)
                var read = 0
                while (read < buf.size) { val n = raf.read(buf, read, buf.size - read); if (n <= 0) break; read += n }
                md.update(buf, 0, read)
            }
        }
        return hex(md.digest())
    }

    fun fullHash(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 20).use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return hex(md.digest())
    }

    fun findDuplicates(files: List<File>, fullConfirm: Boolean = true, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<List<File>> {
        val bySize = files.filter { it.isFile && it.length() > 0 }.groupBy { it.length() }.values.filter { it.size > 1 }
        val total = bySize.sumOf { it.size }
        var done = 0
        val result = mutableListOf<List<File>>()
        for (sameSize in bySize) {
            val byPartial = sameSize.groupBy { done++; onProgress(done, total); runCatching { partialHash(it) }.getOrNull() }
                .filterKeys { it != null }.values.filter { it.size > 1 }
            for (g in byPartial) {
                if (!fullConfirm) { result += g; continue }
                result += g.groupBy { runCatching { fullHash(it) }.getOrNull() }.filterKeys { it != null }.values.filter { it.size > 1 }
            }
        }
        return result.sortedByDescending { g -> g.first().length() * (g.size - 1) }
    }

    /** Índices a marcar para exclusão (mantém um). */
    fun autoSelect(group: List<File>, keepNewest: Boolean): Set<File> {
        val keep = if (keepNewest) group.maxBy { it.lastModified() } else group.minBy { it.lastModified() }
        return group.filter { it != keep }.toSet()
    }
}
