package br.geracaoai.arquivos

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random

class CarverTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun be(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun le(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    private fun noise(n: Int, seed: Int) = Random(seed).nextBytes(n).map { if (it == 0xFF.toByte()) 0x11 else it }.toByteArray()

    fun jpeg(payload: Int = 2000): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        // APP1 com miniatura EXIF embutida (não pode cortar o JPEG cedo)
        val inner = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()); val app1 = "Exif".toByteArray() + inner
        write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((app1.size + 2) shr 8).toByte(), (app1.size + 2).toByte())); write(app1)
        write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2)); write(noise(payload, 3))
        write(byteArrayOf(0xFF.toByte(), 0x00, 0xFF.toByte(), 0xD3.toByte())); write(noise(100, 4))
        write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    }.toByteArray()
    fun png(): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(t: String, d: ByteArray) { write(be(d.size)); write(t.toByteArray()); write(d); write(be(0)) }
        chunk("IHDR", ByteArray(13)); chunk("IDAT", noise(900, 5)); chunk("IEND", ByteArray(0))
    }.toByteArray()
    fun gif(): ByteArray = ByteArrayOutputStream().apply {
        write("GIF89a".toByteArray()); write(byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0)); write(ByteArray(6))
        write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 0, 0, 0, 0))
        write(byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0)); write(byteArrayOf(2)); repeat(3) { write(byteArrayOf(200.toByte())); write(noise(200, it)) }; write(byteArrayOf(0))
        write(byteArrayOf(0x3B))
    }.toByteArray()
    fun webp(): ByteArray { val body = "WEBP".toByteArray() + "VP8 ".toByteArray() + le(800) + noise(800, 7); return "RIFF".toByteArray() + le(body.size) + body }
    fun mp4(): ByteArray = ByteArrayOutputStream().apply {
        fun box(t: String, d: ByteArray) { write(be(d.size + 8)); write(t.toByteArray()); write(d) }
        box("ftyp", "isom".toByteArray() + ByteArray(4) + "isommp41".toByteArray()); box("moov", noise(600, 8)); box("mdat", noise(3000, 9))
    }.toByteArray()

    @Test fun carvesAllTypesFromBlob() {
        val parts = listOf(jpeg() to "jpg", png() to "png", gif() to "gif", webp() to "webp", mp4() to "mp4")
        val blob = ByteArrayOutputStream(); val expected = ArrayList<Carver.Carved>()
        parts.forEachIndexed { i, (b, t) -> blob.write(noise(777 + i * 13, 100 + i)); expected += Carver.Carved(blob.size().toLong(), b.size.toLong(), t); blob.write(b) }
        blob.write(noise(500, 99))
        val got = Carver.carve(ByteBuffer.wrap(blob.toByteArray()), minLen = 16)
        assertEquals(expected, got)
    }
    @Test fun jpegWithEmbeddedThumbIsNotCutEarly() { val j = jpeg(); val r = Carver.carve(ByteBuffer.wrap(j), 16); assertEquals(1, r.size); assertEquals(j.size.toLong(), r[0].length) }
    @Test fun truncatedFilesAreIgnored() {
        listOf(jpeg(), png(), gif(), mp4()).forEach { b -> val t = b.copyOf(b.size - 3)
            assertTrue(Carver.carve(ByteBuffer.wrap(t), 16).none { it.offset == 0L && it.length == b.size.toLong() }) } }
    @Test fun noiseYieldsNothing() { assertTrue(Carver.carve(ByteBuffer.wrap(noise(200_000, 42)), 512).isEmpty()) }
    @Test fun extractWritesExactBytesAndKeepsSource() {
        val p = png(); val f = File(tmp.root, "thumbdata"); f.writeBytes(noise(1000, 1) + p + noise(1000, 2)); val before = DupCore.fullHash(f)
        val c = Carver.carveFile(f).single(); val out = File(tmp.root, "out.png"); Carver.extract(f, c, out)
        assertArrayEquals(p, out.readBytes()); assertEquals(before, DupCore.fullHash(f)) }
    @Test fun sniffDetectsMisnamed() {
        val f = File(tmp.root, "video.dat").apply { writeBytes(mp4()) }; assertEquals("mp4", Carver.sniff(f))
        assertEquals("jpg", Carver.sniff(jpeg())); assertEquals("png", Carver.sniff(png())); assertEquals("gif", Carver.sniff(gif())); assertEquals("webp", Carver.sniff(webp()))
        assertNull(Carver.sniff(noise(64, 3))) }
    @Test fun pHashClusters() { val a = 0b1011L; val b = 0b1001L; val c = -1L
        assertEquals(listOf(listOf("a", "b")), PHash.cluster(listOf("a" to a, "b" to b, "c" to c), 2)) }
    @Test fun zipRoundTripAndSlipProtection() {
        val d = File(tmp.root, "pasta").apply { mkdirs() }; File(d, "x.bin").writeBytes(noise(5000, 1)); File(d, "s/y.txt").apply { parentFile.mkdirs(); writeText("oi") }
        val z = File(tmp.root, "a.zip"); Zip.compress(listOf(d), z); val out = File(tmp.root, "ext"); Zip.extract(z, out)
        assertEquals(DupCore.fullHash(File(d, "x.bin")), DupCore.fullHash(File(out, "pasta/x.bin"))); assertEquals("oi", File(out, "pasta/s/y.txt").readText())
        val evil = File(tmp.root, "evil.zip"); java.util.zip.ZipOutputStream(evil.outputStream()).use { it.putNextEntry(java.util.zip.ZipEntry("../../fora.txt")); it.write(1); it.closeEntry() }
        assertThrows(Exception::class.java) { Zip.extract(evil, File(tmp.root, "e2")) }; assertFalse(File(tmp.root, "e2").exists()); assertFalse(File(tmp.root.parentFile, "fora.txt").exists()) }
}

class CarverBrandTest {
    @Test fun carvesHeicAndMovBrands() {
        fun box(brand: String): ByteArray { val ftyp = java.nio.ByteBuffer.allocate(16).putInt(16).put("ftyp".toByteArray()).put(brand.toByteArray()).putInt(0).array()
            val mdat = java.nio.ByteBuffer.allocate(1032).putInt(1032).put("mdat".toByteArray()).array(); return ftyp + mdat }
        val blob = ByteArray(300) + box("heic") + ByteArray(200) + box("qt  ")
        val types = Carver.carve(java.nio.ByteBuffer.wrap(blob)).map { it.type }
        org.junit.Assert.assertTrue(types.toString(), types.containsAll(listOf("heic", "mov")))
    }
}
