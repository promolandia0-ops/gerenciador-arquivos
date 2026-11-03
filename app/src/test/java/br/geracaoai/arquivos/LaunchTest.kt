package br.geracaoai.arquivos

import android.os.Environment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class LaunchTest {
    private fun launch(tabs: String?, last: String? = null) {
        Nav.permOverride = true
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = Environment.getExternalStorageDirectory().apply { mkdirs() }
        java.io.File(root, "Download").mkdirs(); java.io.File(root, "DCIM").mkdirs()
        ctx.getSharedPreferences("prefs", 0).edit().clear().apply {
            if (tabs != null) putString("tabs", tabs.replace("ROOT", root.path)); if (last != null) putString("lastDir", last.replace("ROOT", root.path)) }.commit()
        Nav.tabs.clear()
        ActivityScenario.launch(MainActivity::class.java).use { s -> s.onActivity { } ; Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
    }
    @Test fun freshInstall() = launch(null)
    @Test fun withV16Tabs() = launch("ROOT\nROOT/Download\nROOT/DCIM")
    @Test fun withV15LastDir() = launch(null, "ROOT/Download")
    @Test fun withGarbage() = launch("lixo\n\n/nao/existe\u0000")
}
