package br.geracaoai.arquivos

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

class App : Application(), ImageLoaderFactory {
    override fun onCreate() { super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> runCatching {
            val txt = "Gerenciador ${BuildConfig.VERSION_NAME} · " + java.util.Date() + " · Android " + android.os.Build.VERSION.RELEASE + " · " + android.os.Build.MODEL + "\n" + android.util.Log.getStackTraceString(e) + "\n\n"
            runCatching { java.io.File(getExternalFilesDir(null), "crash.txt").appendText(txt) }
            runCatching { java.io.File(android.os.Environment.getExternalStorageDirectory(), "MeuDrive").apply { mkdirs() }.let { java.io.File(it, "crash.txt").appendText(txt) } }
            // Se o app quebrou ao abrir, zera as abas salvas para a próxima abertura não repetir o erro
            getSharedPreferences("prefs", 0).edit().remove("tabs").remove("lastDir").commit() }
            prev?.uncaughtException(t, e) } }
    override fun newImageLoader() = ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.crossfade(true).build()
}
