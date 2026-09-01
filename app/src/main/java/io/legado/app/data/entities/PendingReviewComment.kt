package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 离线评论账本：离线评论模式下拦截记录的待发评论，全历史永久保留，
 * 只有"清除离线评论"才删除。发送走书源原有评论通道（无头回填回放）。
 *
 * 发送目标定位：优先 buttonSrc 重新执行书源 click JS 解析真实评论页 URL；
 * buttonSrc 缺失（在线弹窗路径记录）时直接使用 reviewPageUrl。
 */
@Entity(tableName = "pending_review_comments")
data class PendingReviewComment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val bookUrl: String = "",
    val bookName: String = "",
    val chapterUrl: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    /** 书源标识（bookSourceUrl），回放解析书源用 */
    val origin: String? = null,
    /** 评论按钮 src（含 click/js 选项），回放重新解析评论页 URL 的钥匙；在线路径记录为 null */
    val buttonSrc: String? = null,
    /** 记录时的评论页地址；buttonSrc 为空时的回放地址，也是解析失败时的兜底 */
    val reviewPageUrl: String? = null,
    /** 段评 / 章评（实测书源评论页不存在逐条回复通道） */
    val kind: Int = KIND_PARAGRAPH,
    /** 段评的段落定位（来自评论页 URL query），章评为 null；仅展示/导出用 */
    val para: String? = null,
    /** 回复目标预留：快照评论卡片 data-comment-id 已验证可取，当前书源无逐条回复通道，恒为 null */
    val targetCommentId: String? = null,
    val content: String = "",
    val status: Int = STATUS_PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
    val lastAttemptAt: Long? = null,
    val sentAt: Long? = null,
) {

    fun statusText(): String = when (status) {
        STATUS_PENDING -> "待发送"
        STATUS_SENDING -> "发送中"
        STATUS_SENT -> "已发送"
        else -> "失败"
    }

    fun kindText(): String = when (kind) {
        KIND_CHAPTER -> "章评"
        else -> "段评"
    }

    companion object {
        const val KIND_PARAGRAPH = 0
        const val KIND_CHAPTER = 1

        const val STATUS_PENDING = 0
        const val STATUS_SENDING = 1
        const val STATUS_SENT = 2
        const val STATUS_FAILED = 3

        /** 结果未知（上次发送中断，可能已发出）标记在 lastError 文本中 */
        const val ERROR_UNKNOWN_RESULT = "上次发送中断，结果未知，重试可能重复发送"

        const val MAX_CONTENT_LENGTH = 500
    }
}
