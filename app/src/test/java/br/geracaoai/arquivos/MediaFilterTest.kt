package br.geracaoai.arquivos

import org.junit.Assert.*
import org.junit.Test

class MediaFilterTest {
    private fun it(type: String?, size: Long = 50_000, tiny: Boolean = false, dir: Boolean = false, mod: Long = 1) = RItem(RCat.OCULTO, "/x/$type$size$mod", size, mod, type, isDir = dir, tiny = tiny)
    @Test fun foldersNeverShown() { assertFalse(MediaFilter.keep(it(null, dir = true), true, true)) }
    @Test fun nonMediaHiddenByDefault() { assertFalse(MediaFilter.keep(it("pdf"), false, false)); assertTrue(MediaFilter.keep(it("pdf"), true, false)) }
    @Test fun tinyRules() {
        assertTrue(MediaFilter.isTiny(5_000, 0, 0, false)); assertTrue(MediaFilter.isTiny(50_000, 64, 64, false))
        assertFalse(MediaFilter.isTiny(50_000, 640, 480, false)); assertFalse(MediaFilter.isTiny(5_000, 0, 0, true))
        assertFalse(MediaFilter.keep(it("jpg", tiny = true), false, false)); assertTrue(MediaFilter.keep(it("jpg", tiny = true), false, true)) }
    @Test fun sortBySizeAndDate() { val l = listOf(it("jpg", 1, mod = 5), it("mp4", 3, mod = 1), it("png", 2, mod = 9))
        assertEquals(listOf(3L, 2L, 1L), MediaFilter.sort(l, 2).map { it.size }); assertEquals(listOf(9L, 5L, 1L), MediaFilter.sort(l, 0).map { it.modified }) }
}
