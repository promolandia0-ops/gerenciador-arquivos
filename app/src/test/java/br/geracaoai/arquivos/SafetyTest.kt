package br.geracaoai.arquivos

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class SafetyTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun rnd(name: String, size: Int, seed: Int = 1, dir: File = tmp.root) = File(dir, name).apply { parentFile.mkdirs(); writeBytes(Random(seed).nextBytes(size)) }

    @Test fun partialHashSameForEqualFiles() { val a = rnd("a.mp4", 500_000); val b = rnd("b.mp4", 500_000)
        assertEquals(DupCore.partialHash(a), DupCore.partialHash(b)); assertEquals(DupCore.fullHash(a), DupCore.fullHash(b)) }
    @Test fun partialHashDiffersOnTailChange() { val a = rnd("a.mp4", 500_000); val b = File(tmp.root, "b.mp4").apply { writeBytes(a.readBytes().also { it[it.size - 1] = (it.last() + 1).toByte() }) }
        assertNotEquals(DupCore.partialHash(a), DupCore.partialHash(b)) }
    @Test fun middleOnlyChangeCaughtByFullHash() {
        val a = rnd("a.mp4", 1_000_000); val bytes = a.readBytes(); bytes[200_000] = (bytes[200_000] + 1).toByte()
        val b = File(tmp.root, "b.mp4").apply { writeBytes(bytes) }
        assertEquals(DupCore.partialHash(a), DupCore.partialHash(b)) // parcial não vê
        assertTrue(DupCore.findDuplicates(listOf(a, b)).isEmpty()) // completo confirma que NÃO são iguais
    }
    @Test fun findsGroups() { val a = rnd("a.mp4", 300_000, 1); val b = rnd("x/b.mp4", 300_000, 1); val c = rnd("c.mp4", 300_000, 2); val d = rnd("d.mp4", 10, 3)
        val g = DupCore.findDuplicates(listOf(a, b, c, d)); assertEquals(1, g.size); assertEquals(setOf(a, b), g[0].toSet()) }
    @Test fun autoSelectKeepsOne() { val a = rnd("a", 10); val b = rnd("b", 10); a.setLastModified(1000_000); b.setLastModified(2000_000)
        assertEquals(setOf(a), DupCore.autoSelect(listOf(a, b), keepNewest = true)); assertEquals(setOf(b), DupCore.autoSelect(listOf(a, b), keepNewest = false)) }
    @Test fun copyVerifiedKeepsSourceAndMatches() { val a = rnd("src/a.bin", 2_000_000); val d = File(tmp.root, "dst/a.bin")
        SafeOps.copyVerified(a, d); assertTrue(a.exists()); assertEquals(DupCore.fullHash(a), DupCore.fullHash(d)); assertFalse(File(d.parentFile, ".a.bin.part").exists()) }
    @Test fun copyRefusesOverwrite() { val a = rnd("a.bin", 100); val b = rnd("b.bin", 50, 9)
        assertThrows(Exception::class.java) { SafeOps.copyVerified(a, b) }; assertEquals(50, b.length()) }
    @Test fun moveDirectoryPreservesContent() { rnd("d/1.bin", 1000, 1); rnd("d/sub/2.bin", 2000, 2); val h = DupCore.fullHash(File(tmp.root, "d/sub/2.bin"))
        val t = File(tmp.root, "out/d"); SafeOps.moveVerified(File(tmp.root, "d"), t)
        assertFalse(File(tmp.root, "d").exists()); assertEquals(h, DupCore.fullHash(File(t, "sub/2.bin"))) }
    @Test fun moveMissingSourceFails() { assertThrows(Exception::class.java) { SafeOps.moveVerified(File(tmp.root, "nada"), File(tmp.root, "x")) } }
    @Test fun uniqueTargetNeverClobbers() { rnd("v.mp4", 10); assertEquals("v (1).mp4", FileOps.uniqueTarget(tmp.root, "v.mp4").name) }
}
