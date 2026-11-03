package br.geracaoai.arquivos

import org.junit.Assert.*
import org.junit.Test

class JunkTabsTest {
    @Test fun status() {
        assertTrue(JunkRules.isStatus("Android/media/com.whatsapp/WhatsApp/Media/.Statuses/a.mp4"))
        assertTrue(JunkRules.isStatus("WhatsApp/Media/.Statuses/b.jpg"))
        assertFalse(JunkRules.isStatus("Android/media/com.whatsapp/WhatsApp/Media/.Statuses/.nomedia"))
        assertFalse(JunkRules.isStatus("DCIM/Camera/status.jpg"))
    }
    @Test fun icons() {
        assertTrue(JunkRules.isIcon("Download/fav.png", 3000, 64, 64))
        assertFalse(JunkRules.isIcon("Download/fav.png", 3000, 1024, 768))
        assertFalse(JunkRules.isIcon("DCIM/Camera/x.jpg", 3000, 64, 64))
        assertFalse(JunkRules.isIcon("Pictures/x.png", 3000, 64, 64))
        assertFalse(JunkRules.isIcon("Download/big.png", 500_000))
        assertFalse(JunkRules.isIcon("Download/a.mp4", 3000))
    }
    @Test fun protectedAndCache() {
        assertFalse(JunkRules.isProtected("DCIM/.thumbnails/1.jpg"))
        assertTrue(JunkRules.isProtected("DCIM/Camera/1.jpg"))
        assertTrue(JunkRules.isMediaCache("DCIM/.thumbnails/1.jpg"))
        assertTrue(JunkRules.isMediaCache("Android/media/org.telegram.messenger/Telegram/cache/x.jpg"))
        assertFalse(JunkRules.isMediaCache("Android/media/org.telegram.messenger/Telegram/Telegram Images/x.jpg"))
    }
    @Test fun tabs() {
        var l = emptyList<String>()
        for (i in 1..10) l = Tabs.push(l, "/d$i")
        assertEquals(8, l.size); assertEquals("/d10", l.first())
        l = Tabs.push(l, "/d5"); assertEquals("/d5", l.first()); assertEquals(8, l.size); assertEquals(1, l.count { it == "/d5" })
        assertEquals(l[1], Tabs.next(l, l[0])); assertEquals(l[0], Tabs.next(l, l.last())); assertEquals(l[0], Tabs.next(l, "/outro"))
        assertEquals(l, Tabs.decode(Tabs.encode(l)))
    }
}

class TabStateTest {
    @Test fun openReuseAndLimit() {
        var l = emptyList<String>(); var idx = -1
        for (i in 1..9) { val r = Tabs.open(l, "/d$i"); l = r.first; idx = r.second }
        assertEquals(8, l.size); assertEquals("/d2", l.first()); assertEquals(7, idx)
        val r = Tabs.open(l, "/d4"); assertEquals(l, r.first); assertEquals(2, r.second)
    }
    @Test fun updateAndClose() {
        val l = listOf("/a", "/b", "/c")
        assertEquals(listOf("/a", "/b/x", "/c"), Tabs.update(l, 1, "/b/x"))
        assertEquals(l, Tabs.update(l, 9, "/z"))
        assertEquals(listOf("/a", "/c"), Tabs.close(l, 1))
        assertEquals(0, Tabs.pageAfterClose(0, 1, 2))   // no Início: fica
        assertEquals(1, Tabs.pageAfterClose(2, 0, 2))   // fechou anterior: recua
        assertEquals(2, Tabs.pageAfterClose(3, 2, 2))   // fechou a atual (última): vai p/ anterior
    }
}
