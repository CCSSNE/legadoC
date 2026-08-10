package io.legado.app.help.glide

import com.bumptech.glide.load.Option
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.signature.ObjectKey
import java.io.InputStream

object OkHttpModelLoader : ModelLoader<GlideUrl?, InputStream?> {

    val loadOnlyWifiOption = Option.memory("loadOnlyWifi", false)
    val sourceOriginOption = Option.memory<String>("sourceOrigin")
    val stableCoverCacheKeyOption = Option.memory<String>("stableCoverCacheKey")
    val mangaOption = Option.memory<Boolean>("manga",false)

    override fun buildLoadData(
        model: GlideUrl,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<InputStream?> {
        val sourceKey = options.get(stableCoverCacheKeyOption)?.let(::ObjectKey) ?: model
        return ModelLoader.LoadData(sourceKey, OkHttpStreamFetcher(model, options))
    }

    override fun handles(model: GlideUrl): Boolean {
        return true
    }

}
