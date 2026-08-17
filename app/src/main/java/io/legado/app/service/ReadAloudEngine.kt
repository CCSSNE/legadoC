package io.legado.app.service

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter

/**
 * 融合第一阶段：统一听书引擎接口
 *
 * 让书源音频、系统 TTS、HTTP TTS 使用统一接口
 */
interface ReadAloudEngine {

    /**
     * 引擎类型
     */
    enum class Type {
        /** 书源音频 */
        SOURCE_AUDIO,
        /** 系统 TTS */
        SYSTEM_TTS,
        /** HTTP TTS */
        HTTP_TTS
    }

    /**
     * 获取引擎类型
     */
    fun getType(): Type

    /**
     * 初始化引擎
     */
    fun init(book: Book, chapter: BookChapter)

    /**
     * 开始播放
     * @param content 内容（对音频引擎是URL，对TTS是文本）
     * @param startPos 起始位置（音频是毫秒数，TTS是段落索引）
     */
    fun play(content: String, startPos: Int = 0)

    /**
     * 暂停
     */
    fun pause()

    /**
     * 恢复
     */
    fun resume()

    /**
     * 停止
     */
    fun stop()

    /**
     * 设置速度
     */
    fun setSpeed(speed: Float)

    /**
     * 调整进度
     * @param position 位置（音频是毫秒数，TTS是段落索引）
     */
    fun seekTo(position: Int)

    /**
     * 获取当前进度
     * @return 位置（音频是毫秒数，TTS是段落索引）
     */
    fun getCurrentPosition(): Int

    /**
     * 获取总时长/总数
     * @return 音频引擎返回毫秒数，TTS引擎返回段落总数
     */
    fun getDuration(): Int

    /**
     * 是否正在播放
     */
    fun isPlaying(): Boolean

    /**
     * 释放资源
     */
    fun release()

    /**
     * 回调接口
     */
    interface Callback {
        /**
         * 进度更新
         */
        fun onProgressUpdate(position: Int, duration: Int)

        /**
         * 播放完成
         */
        fun onCompletion()

        /**
         * 错误
         */
        fun onError(error: String)

        /**
         * 加载中
         */
        fun onLoading(loading: Boolean)
    }
}
