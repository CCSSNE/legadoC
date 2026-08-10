package io.legado.app.help.gsyVideo

import android.os.Message
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.shuyu.gsyvideoplayer.GSYVideoBaseManager
import io.legado.app.R

/**
基类管理器
 */
class ExoVideoManager: GSYVideoBaseManager() {
    companion object {
        val SMALL_ID: Int = R.id.small_id
        val FULLSCREEN_ID: Int = R.id.full_id
        var TAG: String = "GSYExoVideoManager"
    }

    init {
        super.init()
    }

    private var onMediaKeyTransition: ((String) -> Unit)? = null

    @OptIn(UnstableApi::class)
    override fun getPlayManager(): ExoPlayerManager {
        return ExoPlayerManager().apply {
            setOnMediaKeyTransitionListener(onMediaKeyTransition)
        }
    }

//    fun prepare(
//        url: String,
//        mapHeadData: MutableMap<String?, String?>?,
//        index: Int,
//        loop: Boolean,
//        speed: Float,
//        cache: Boolean,
//        cachePath: File?,
//        overrideExtension: String?
//    ) {
//        val msg = Message()
//        msg.what = HANDLER_PREPARE
//        msg.obj =GSYModel(url, mapHeadData, loop, speed, cache, cachePath, overrideExtension)
//        sendMessage(msg)
//    }
    /**
     * 上一集
     */
    @OptIn(UnstableApi::class)
    fun previous() {
        if (playerManager == null) {
            return
        }
        (playerManager as ExoPlayerManager).previous()
    }


    fun setDisplayNew(holder: Any?) {
        val msg = Message()
        msg.what = HANDLER_SETDISPLAY
        msg.obj = holder
        if (playerManager != null) {
            playerManager.showDisplay(msg)
        }
    }

    /**
     * 下一集
     */
    @OptIn(UnstableApi::class)
    fun next() {
        if (playerManager == null) {
            return
        }
        (playerManager as ExoPlayerManager).next()
    }

    @OptIn(UnstableApi::class)
    fun appendNext(key: String, url: String, headers: Map<String, String>) {
        (playerManager as? ExoPlayerManager)?.appendNext(key, url, headers)
    }

    @OptIn(UnstableApi::class)
    fun hasNext(): Boolean {
        return (playerManager as? ExoPlayerManager)?.hasNext() == true
    }

    @OptIn(UnstableApi::class)
    fun currentMediaKey(): String? {
        return (playerManager as? ExoPlayerManager)?.currentMediaKey()
    }

    @OptIn(UnstableApi::class)
    fun setOnMediaKeyTransitionListener(listener: ((String) -> Unit)?) {
        onMediaKeyTransition = listener
        (playerManager as? ExoPlayerManager)?.setOnMediaKeyTransitionListener(listener)
    }

}
