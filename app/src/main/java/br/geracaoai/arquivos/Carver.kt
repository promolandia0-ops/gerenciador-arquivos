package br.geracaoai.arquivos

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Detecção por assinatura (magic bytes) e "carving" de mídias embutidas em blobs/caches. Somente leitura. */
object Carver {
    data class Carved(val offset: Long, val length: Long, val type: String)

    private fun u8(b: ByteBuffer, i: Long) = b.get(i.toInt()).toInt() and 0xFF
    private fun be32(b: ByteBuffer, i: Long) = (u8(b, i) shl 24 or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)).toLong() and 0xFFFFFFFFL
    private fun le32(b: ByteBuffer, i: Long) = (u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)).toLong() and 0xFFFFFFFFL
    private fun ascii(b: ByteBuffer, i: Long, n: Int) = String(CharArray(n) { u8(b, i + it).toChar() })

    /** Tipo real do arquivo pelo cabeçalho. */
    fun sniff(h: ByteArray): String? {
        if (h.size < 12) return null
        val b = ByteBuffer.wrap(h); val u = { i: Int -> h[i].toInt() and 0xFF }
        return when {
            u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF -> "jpg"
            u(0) == 0x89 && ascii(b, 1, 3) == "PNG" -> "png"
            ascii(b, 0, 4) == "GIF8" -> "gif"
            ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP" -> "webp"
            ascii(b, 4, 4) == "ftyp" -> when (ascii(b, 8, 4)) { "heic", "heix", "mif1", "msf1" -> "heic"; "qt  " -> "mov"; "3gp4", "3gp5", "3gp6" -> "3gp"; "M4A " -> "m4a"; else -> "mp4" }
            u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3 -> "mkv"
            ascii(b, 0, 3) == "ID3" -> "mp3"
            ascii(b, 0, 4) == "OggS" -> "ogg"
            ascii(b, 0, 4) == "%PDF" -> "pdf"
            else -> null
        }
    }
    fun sniff(f: File): String? = runCatching { RandomAccessFile(f, "r").use { r -> val h = ByteArray(16); val n = r.read(h); if (n < 12) null else sniff(h) } }.getOrNull()

    private fun jpegEnd(b: ByteBuffer, s: Long, lim: Long): Long {
        var i = s + 2
        while (i + 2 <= lim) {
            if (u8(b, i) != 0xFF) return -1
            val m = u8(b, i + 1)
            if (m == 0xFF) { i++; continue }
            if (m == 0xD9) return i + 2
            if (m in 0xD0..0xD7 || m == 0x01) { i += 2; continue }
            if (i + 4 > lim) return -1
            val len = (u8(b, i + 2) shl 8) or u8(b, i + 3)
            if (len < 2) return -1
            i += 2 + len
            if (m == 0xDA) { // dados comprimidos até o próximo marcador real
                while (i + 1 < lim) {
                    if (u8(b, i) == 0xFF) { val n = u8(b, i + 1); if (n != 0 && n !in 0xD0..0xD7 && n != 0xFF) break }
                    i++
                }
            }
        }
        return -1
    }
    private fun pngEnd(b: ByteBuffer, s: Long, lim: Long): Long {
        var i = s + 8
        while (i + 12 <= lim) { val len = be32(b, i); val t = ascii(b, i + 4, 4)
            if (!t.all { it.isLetter() } || len > 0x7FFFFFFF) return -1
            i += 12 + len; if (t == "IEND") return if (i <= lim) i else -1 }
        return -1
    }
    private fun gifEnd(b: ByteBuffer, s: Long, lim: Long): Long {
        var i = s + 6; if (i + 7 > lim) return -1
        val flags = u8(b, i + 4); i += 7
        if (flags and 0x80 != 0) i += 3L * (1 shl ((flags and 7) + 1))
        fun subs(): Boolean { while (i < lim) { val n = u8(b, i); i += 1 + n; if (n == 0) return true }; return false }
        while (i < lim) {
            when (u8(b, i)) {
                0x3B -> return i + 1
                0x21 -> { i += 2; if (!subs()) return -1 }
                0x2C -> { if (i + 10 > lim) return -1; val f = u8(b, i + 9); i += 10
                    if (f and 0x80 != 0) i += 3L * (1 shl ((f and 7) + 1)); i += 1; if (!subs()) return -1 }
                else -> return -1
            }
        }
        return -1
    }
    private val MP4_BOXES = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "uuid", "meta", "pdin", "moof", "mfra", "sidx", "styp", "udta", "prft", "emsg")
    private fun mp4End(b: ByteBuffer, s: Long, lim: Long): Long {
        var i = s; var media = false
        while (i + 8 <= lim) { var size = be32(b, i); val t = ascii(b, i + 4, 4)
            if (t !in MP4_BOXES) break
            if (size == 1L) { if (i + 16 > lim) return -1; size = (be32(b, i + 8) shl 32) or be32(b, i + 12) } else if (size == 0L) size = lim - i
            if (size < 8 || i + size > lim) return -1
            if (t == "moov" || t == "mdat") media = true
            i += size }
        return if (media && i > s) i else -1
    }

    /** Procura mídias completas dentro do buffer. */
    fun carve(b: ByteBuffer, minLen: Int = 512, onlyEmbedded: Boolean = false): List<Carved> {
        val lim = b.limit().toLong(); val out = ArrayList<Carved>(); var i = 0L
        while (i + 12 <= lim) {
            val c = u8(b, i)
            var found: Carved? = null
            if (c == 0xFF && u8(b, i + 1) == 0xD8 && u8(b, i + 2) == 0xFF) { val e = jpegEnd(b, i, lim); if (e > 0) found = Carved(i, e - i, "jpg") }
            else if (c == 0x89 && u8(b, i + 1) == 0x50 && ascii(b, i + 1, 3) == "PNG") { val e = pngEnd(b, i, lim); if (e > 0) found = Carved(i, e - i, "png") }
            else if (c == 0x47 && ascii(b, i, 4) == "GIF8") { val e = gifEnd(b, i, lim); if (e > 0) found = Carved(i, e - i, "gif") }
            else if (c == 0x52 && ascii(b, i, 4) == "RIFF" && ascii(b, i + 8, 4) == "WEBP") { val e = i + 8 + le32(b, i + 4); if (e <= lim) found = Carved(i, e - i, "webp") }
            else if (i >= 0 && u8(b, i + 4) == 0x66 && ascii(b, i + 4, 4) == "ftyp") { val e = mp4End(b, i, lim); if (e > 0) { val t = when (ascii(b, i + 8, 4)) { "heic", "heix", "mif1", "msf1" -> "heic"; "qt  " -> "mov"; "3gp4", "3gp5", "3gp6", "3g2a" -> "3gp"; "M4A ", "M4B " -> "m4a"; else -> "mp4" }
                if (t != "m4a") found = Carved(i, e - i, t) } }
            if (found != null && found.length >= minLen) { out += found; i += found.length } else i++
        }
        return if (onlyEmbedded) out.filterNot { it.offset == 0L && it.length == lim } else out
    }
    fun carveFile(f: File, maxBytes: Long = 512L shl 20): List<Carved> {
        if (f.length() < 64 || f.length() > maxBytes) return emptyList()
        return RandomAccessFile(f, "r").use { r -> carve(r.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())) }
    }
    fun extract(f: File, c: Carved, dst: File) {
        val tmp = File(dst.parentFile, ".${dst.name}.part")
        try {
            RandomAccessFile(f, "r").use { r -> tmp.outputStream().use { o -> r.seek(c.offset); var left = c.length; val buf = ByteArray(1 shl 16)
                while (left > 0) { val n = r.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n <= 0) break; o.write(buf, 0, n); left -= n }; o.fd.sync() } }
            if (tmp.length() != c.length || dst.exists() || !tmp.renameTo(dst)) error("falha")
        } catch (e: Exception) { tmp.delete(); throw e }
    }
    fun readRange(f: File, c: Carved): ByteArray = RandomAccessFile(f, "r").use { r -> ByteArray(c.length.toInt()).also { r.seek(c.offset); r.readFully(it) } }
}

/** Hash perceptual (dHash 64 bits) e agrupamento por distância de Hamming. */
object PHash {
    /** gray: 9x8 em tons de cinza (linha a linha). */
    fun dHash(gray: IntArray): Long { var h = 0L; var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) { if (gray[y * 9 + x] > gray[y * 9 + x + 1]) h = h or (1L shl bit); bit++ }; return h }
    fun dist(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)
    fun <T> cluster(items: List<Pair<T, Long>>, maxDist: Int = 6): List<List<T>> {
        val used = BooleanArray(items.size); val out = ArrayList<List<T>>()
        for (i in items.indices) { if (used[i]) continue; val g = arrayListOf(items[i].first); used[i] = true
            for (j in i + 1 until items.size) if (!used[j] && dist(items[i].second, items[j].second) <= maxDist) { used[j] = true; g += items[j].first }
            if (g.size > 1) out += g }
        return out
    }
}

/** ZIP seguro: escreve em .part e renomeia; extração protegida contra zip-slip. */
object Zip {
    fun compress(files: List<File>, dst: File) {
        val tmp = File(dst.parentFile, ".${dst.name}.part")
        try {
            java.util.zip.ZipOutputStream(tmp.outputStream().buffered()).use { z ->
                files.forEach { root -> val base = root.parentFile!!
                    root.walkTopDown().forEach { f -> val name = f.relativeTo(base).path + if (f.isDirectory) "/" else ""
                        z.putNextEntry(java.util.zip.ZipEntry(name).apply { time = f.lastModified() }); if (f.isFile) f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
            }
            if (dst.exists() || !tmp.renameTo(dst)) error("destino existe")
        } catch (e: Exception) { tmp.delete(); throw e }
    }
    fun extract(zip: File, dstDir: File) {
        if (dstDir.exists()) error("destino existe")
        val tmp = File(dstDir.parentFile, ".${dstDir.name}.part"); tmp.deleteRecursively(); tmp.mkdirs()
        try {
            val canon = tmp.canonicalPath + File.separator
            java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) { val e = z.nextEntry ?: break; val out = File(tmp, e.name)
                    if (!out.canonicalPath.startsWith(canon)) error("entrada inválida: ${e.name}")
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } } }
            }
            if (!tmp.renameTo(dstDir)) error("rename")
        } catch (e: Exception) { tmp.deleteRecursively(); throw e }
    }
}
