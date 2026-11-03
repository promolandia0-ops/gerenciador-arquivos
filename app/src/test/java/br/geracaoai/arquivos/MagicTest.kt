package br.geracaoai.arquivos

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MagicTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
    private fun a(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun pad(h: ByteArray) = h + ByteArray(64)
    @Test fun signatures() {
        assertEquals("jpg", Carver.sniff(pad(b(0xFF, 0xD8, 0xFF, 0xE0))))
        assertEquals("png", Carver.sniff(pad(b(0x89) + a("PNG\r\n\u001a\n"))))
        assertEquals("gif", Carver.sniff(pad(a("GIF89a"))))
        assertEquals("webp", Carver.sniff(pad(a("RIFF") + b(0, 0, 0, 0) + a("WEBPVP8 "))))
        assertEquals("avi", Carver.sniff(pad(a("RIFF") + b(0, 0, 0, 0) + a("AVI LIST"))))
        assertEquals("heic", Carver.sniff(pad(b(0, 0, 0, 24) + a("ftypheic"))))
        assertEquals("mov", Carver.sniff(pad(b(0, 0, 0, 20) + a("ftypqt  "))))
        assertEquals("3gp", Carver.sniff(pad(b(0, 0, 0, 20) + a("ftyp3gp5"))))
        assertEquals("mp4", Carver.sniff(pad(b(0, 0, 0, 32) + a("ftypisom"))))
        assertEquals("mkv", Carver.sniff(pad(b(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x82, 0x88) + a("matroska"))))
        assertEquals("webm", Carver.sniff(pad(b(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x82, 0x84) + a("webm"))))
        assertNull(Carver.sniff(pad(a("hello world, not media"))))
        assertNull(Carver.sniff(b(0xFF, 0xD8)))
    }
    @Test fun fileWithoutExtension() {
        val f = tmp.newFile("cache_0001"); f.writeBytes(pad(b(0, 0, 0, 24) + a("ftypmp42")) + ByteArray(5000))
        assertEquals("mp4", Carver.sniff(f))
        val g = tmp.newFile("foto.dat"); g.writeBytes(b(0xFF, 0xD8, 0xFF, 0xE1) + ByteArray(20))
        assertEquals("jpg", Carver.sniff(g))
        val t = tmp.newFile("tiny"); t.writeBytes(b(1, 2, 3)); assertNull(Carver.sniff(t))
    }
}
