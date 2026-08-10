package io.legado.app.help.gsyVideo

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.ExtensionRendererMode
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.legado.app.help.exoplayer.ExoPlayerHelper
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
import tv.danmaku.ijk.media.exo2.demo.EventLogger
import java.io.File

class Exo2MediaPlayer(context: Context) : IjkExo2MediaPlayer(context) {
    companion object {
        private const val TAG = "GSYExo2MediaPlayer"
        private const val MAX_POSITION_FOR_SEEK_TO_PREVIOUS: Long = 3000
    }
    private val window = Timeline.Window()
    private val mediaKeys = arrayListOf<String?>(null)
    private val reportedMediaKeys = hashSetOf<String>()
    private var pendingNextMediaSource: MediaSource? = null
    private var pendingNextKey: String? = null
    private var mediaCacheDir: File? = null
    var onMediaKeyTransition: ((String) -> Unit)? = null

    override fun setCacheDir(cacheDir: File?) {
        mediaCacheDir = cacheDir
        super.setCacheDir(cacheDir)
    }

    override fun setDataSource(context: Context?, uri: Uri?, headers: MutableMap<String, String>?) {
        if (headers != null) {
            mHeaders.clear()
            mHeaders.putAll(headers)
        }
        setDataSource(context, uri)
    }

    override fun setDataSource(context: Context?, uri: Uri?) {
        val dataSource = uri?.toString() ?: return
        mDataSource = dataSource
        mediaKeys.clear()
        mediaKeys.add(null)
        reportedMediaKeys.clear()
        pendingNextMediaSource = null
        pendingNextKey = null
        mMediaSource = ExoPlayerHelper.createVideoMediaSource(
            context ?: mAppContext,
            dataSource,
            mHeaders,
            mediaCacheDir
        )
    }

    /**
     * 上一集
     */
    fun previous() {
        if (mInternalPlayer == null) {
            return
        }
        val timeline: Timeline = mInternalPlayer.currentTimeline
        if (timeline.isEmpty) {
            return
        }
        val windowIndex: Int = mInternalPlayer.currentMediaItemIndex
        timeline.getWindow(windowIndex, window)
        val previousWindowIndex: Int = mInternalPlayer.previousMediaItemIndex
        if (previousWindowIndex != C.INDEX_UNSET
            && (mInternalPlayer.currentPosition <= MAX_POSITION_FOR_SEEK_TO_PREVIOUS
                    || (window.isDynamic && !window.isSeekable))
        ) {
            mInternalPlayer.seekTo(previousWindowIndex, C.TIME_UNSET)
        } else {
            mInternalPlayer.seekTo(0)
        }
    }

    @OptIn(UnstableApi::class)
    override fun prepareAsyncInternal() {
        Handler(Looper.myLooper()!!).post {
            if (mTrackSelector == null) {
                mTrackSelector = DefaultTrackSelector(mAppContext)
            }
            mEventLogger = EventLogger(mTrackSelector)
            val preferExtensionDecoders = true
            val useExtensionRenderers = true //是否开启扩展
            val extensionRendererMode: @ExtensionRendererMode Int =
                if (useExtensionRenderers) (if (preferExtensionDecoders) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON) else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            if (mRendererFactory == null) {
                mRendererFactory = DefaultRenderersFactory(mAppContext)
                mRendererFactory.setExtensionRendererMode(extensionRendererMode)
            }
            if (mLoadControl == null) {
                mLoadControl = DefaultLoadControl()
            }
            mInternalPlayer =
                ExoPlayer.Builder(mAppContext, mRendererFactory).setLooper(Looper.myLooper()!!)
                    .setTrackSelector(mTrackSelector).setLoadControl(mLoadControl)
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(
                            ResolvingDataSource.Factory(ExoPlayerHelper.cacheDataSourceFactory){ it }
                        )
                            .setLiveTargetOffsetMs(5000) //直播时延5秒
                    )
                    .build()
            mInternalPlayer.addListener(this@Exo2MediaPlayer)
            mInternalPlayer.addAnalyticsListener(this@Exo2MediaPlayer)
            mInternalPlayer.addListener(mEventLogger)
            if (mSpeedPlaybackParameters != null) {
                mInternalPlayer.playbackParameters = mSpeedPlaybackParameters
            }
            if (isLooping) {
                mInternalPlayer.repeatMode = Player.REPEAT_MODE_ALL
            }
            if (mSurface != null) mInternalPlayer.setVideoSurface(mSurface)
            mInternalPlayer.setMediaSource(mMediaSource)
            pendingNextMediaSource?.let { mediaSource ->
                mInternalPlayer.addMediaSource(mediaSource)
                mediaKeys.add(pendingNextKey)
            }
            pendingNextMediaSource = null
            pendingNextKey = null
            mInternalPlayer.prepare()
            mInternalPlayer.playWhenReady = false
        }
    }

    fun appendNext(key: String, url: String, headers: Map<String, String>) {
        if (key.isBlank() || url.isBlank()) return
        if (mediaKeys.contains(key) || pendingNextKey == key) return
        val mediaSource = ExoPlayerHelper.createVideoMediaSource(mAppContext, url, headers, mediaCacheDir)
        val player = mInternalPlayer
        if (player == null) {
            pendingNextMediaSource = mediaSource
            pendingNextKey = key
            return
        }
        val insertIndex = player.currentMediaItemIndex + 1
        if (insertIndex < player.mediaItemCount) {
            player.removeMediaItems(insertIndex, player.mediaItemCount)
            while (mediaKeys.size > insertIndex) {
                mediaKeys.removeAt(mediaKeys.lastIndex)
            }
        }
        player.addMediaSource(mediaSource)
        mediaKeys.add(key)
    }

    fun hasNext(): Boolean {
        val player = mInternalPlayer ?: return pendingNextMediaSource != null
        return player.nextMediaItemIndex != C.INDEX_UNSET
    }

    fun currentMediaKey(): String? {
        return mediaKeys.getOrNull(currentWindowIndex)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)
        val key = mediaKeys.getOrNull(currentWindowIndex) ?: return
        if (reportedMediaKeys.add(key)) {
            onMediaKeyTransition?.invoke(key)
        }
    }

    /**
     * 下一集
     */
    fun next() {
        if (mInternalPlayer == null) {
            return
        }
        val timeline: Timeline = mInternalPlayer.currentTimeline
        if (timeline.isEmpty) {
            return
        }
        val windowIndex: Int = mInternalPlayer.currentMediaItemIndex
        val nextWindowIndex: Int = mInternalPlayer.nextMediaItemIndex
        if (nextWindowIndex != C.INDEX_UNSET) {
            mInternalPlayer.seekTo(nextWindowIndex, C.TIME_UNSET)
        } else if (timeline.getWindow(windowIndex, window).isDynamic) {
            mInternalPlayer.seekTo(windowIndex, C.TIME_UNSET)
        }
    }

    val currentWindowIndex: Int
        get() {
            if (mInternalPlayer == null) {
                return 0
            }
            return mInternalPlayer.currentMediaItemIndex
        }


}
