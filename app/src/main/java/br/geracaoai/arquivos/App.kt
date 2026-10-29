package br.geracaoai.arquivos

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

class App : Application(), ImageLoaderFactory {
    override fun newImageLoader() = ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.crossfade(true).build()
}
