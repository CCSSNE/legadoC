package io.legado.app.help.review.reviewoutbox

import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.LogModule
import io.legado.app.data.appDb
import io.legado.app.data.entities.PendingReviewComment
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx

/**
 * 离线评论账本统一入口：入队、状态流转、查询、导出、清除。
 * 状态机：PENDING → SENDING → SENT / FAILED；SENDING 中断在启动回收时置 FAILED
 * 并标记"结果未知"（可能已发出，重试由用户确认）。
 */
object ReviewOutboxStore {

    const val LogTag = "[离线评论]"

    private const val EXPORT_VERSION = 1

    /** 页面脚本入队载荷（wire-up JS 生成） */
    private data class OutboxJsPayload(
        val kind: Int = 0,
        val content: String = "",
        val bookId: String = "",
        val itemId: String = "",
        val para: String = "",
        val pageUrl: String = "",
    )

    private data class EnqueueResult(val ok: Boolean, val message: String? = null, val count: Int = 0)

    /**
     * 评论弹窗桥入队入口（桥线程调用，同步落库返回）。
     * 校验失败返回 ok=false，由页面侧以错误文案呈现。
     */
    fun enqueueFromJs(context: ReviewOutboxContext, json: String): String {
        val payload = GSON.fromJsonObject<OutboxJsPayload>(json).getOrNull()
        if (payload == null || payload.content.isBlank()) {
            AppLog.putDebug("$LogTag 入队拒绝：内容为空", module = LogModule.REVIEW_OFFLINE)
            return GSON.toJson(
                EnqueueResult(ok = false, message = appCtx.getString(R.string.offline_review_content_empty))
            )
        }
        if (payload.content.length > PendingReviewComment.MAX_CONTENT_LENGTH) {
            AppLog.putDebug(
                "$LogTag 入队拒绝：内容超长 ${payload.content.length}",
                module = LogModule.REVIEW_OFFLINE
            )
            return GSON.toJson(
                EnqueueResult(
                    ok = false,
                    message = appCtx.getString(
                        R.string.offline_review_content_too_long,
                        PendingReviewComment.MAX_CONTENT_LENGTH
                    )
                )
            )
        }
        val kind = when (payload.kind) {
            PendingReviewComment.KIND_CHAPTER -> PendingReviewComment.KIND_CHAPTER
            else -> PendingReviewComment.KIND_PARAGRAPH
        }
        val item = PendingReviewComment(
            bookUrl = context.bookUrl,
            bookName = context.bookName,
            chapterUrl = context.chapterUrl,
            chapterIndex = context.chapterIndex,
            chapterTitle = context.chapterTitle,
            origin = context.origin,
            buttonSrc = context.buttonSrc?.takeIf { it.isNotBlank() },
            reviewPageUrl = payload.pageUrl.ifBlank { context.pageUrl }.ifBlank { null },
            kind = kind,
            para = payload.para.ifBlank { null }?.takeIf { kind == PendingReviewComment.KIND_PARAGRAPH },
            content = payload.content,
        )
        val id = runBlocking { appDb.pendingReviewCommentDao.insert(item) }
        val pending = runBlocking { appDb.pendingReviewCommentDao.countByStatus(PendingReviewComment.STATUS_PENDING) }
        AppLog.putDebug(
            "$LogTag 已入队 id=$id kind=${item.kindText()} 书=${item.bookName} " +
                "章=${item.chapterTitle} para=${item.para} 字数=${item.content.length} " +
                "来源=${if (item.buttonSrc != null) "快照" else "在线弹窗"} 待发=$pending",
            module = LogModule.REVIEW_OFFLINE
        )
        appCtx.toastOnUi(appCtx.getString(R.string.offline_review_enqueued, pending))
        return GSON.toJson(EnqueueResult(ok = true, count = pending))
    }

    /** 启动回收：上次发送中断的 SENDING 置 FAILED（结果未知），返回回收条数 */
    suspend fun recoverSendingOnStart(): Int {
        val dao = appDb.pendingReviewCommentDao
        var recovered = 0
        while (true) {
            val item = dao.firstSending() ?: break
            dao.update(
                item.copy(
                    status = PendingReviewComment.STATUS_FAILED,
                    lastError = PendingReviewComment.ERROR_UNKNOWN_RESULT,
                    lastAttemptAt = System.currentTimeMillis(),
                )
            )
            recovered++
        }
        if (recovered > 0) {
            AppLog.putDebug(
                "$LogTag 启动回收：$recovered 条发送中记录标记为结果未知",
                module = LogModule.REVIEW_OFFLINE
            )
        }
        return recovered
    }

    /** 可发送列表：待发送 + 失败，按入队顺序 */
    suspend fun sendable(): List<PendingReviewComment> =
        appDb.pendingReviewCommentDao.sendable()

    /** 上次发送中断（结果未知）的条数 */
    suspend fun unknownCount(): Int =
        appDb.pendingReviewCommentDao.all().count {
            it.status == PendingReviewComment.STATUS_FAILED &&
                it.lastError == PendingReviewComment.ERROR_UNKNOWN_RESULT
        }

    suspend fun all(): List<PendingReviewComment> =
        appDb.pendingReviewCommentDao.all()

    suspend fun countSendable(): Int =
        appDb.pendingReviewCommentDao.countByStatus(PendingReviewComment.STATUS_PENDING) +
            appDb.pendingReviewCommentDao.countByStatus(PendingReviewComment.STATUS_FAILED)

    suspend fun countAll(): Int = appDb.pendingReviewCommentDao.countAll()

    suspend fun markSending(item: PendingReviewComment): PendingReviewComment {
        val updated = item.copy(
            status = PendingReviewComment.STATUS_SENDING,
            attempts = item.attempts + 1,
            lastAttemptAt = System.currentTimeMillis(),
        )
        appDb.pendingReviewCommentDao.update(updated)
        return updated
    }

    suspend fun markSent(item: PendingReviewComment): PendingReviewComment {
        val updated = item.copy(
            status = PendingReviewComment.STATUS_SENT,
            lastError = null,
            sentAt = System.currentTimeMillis(),
        )
        appDb.pendingReviewCommentDao.update(updated)
        return updated
    }

    suspend fun markFailed(item: PendingReviewComment, error: String): PendingReviewComment {
        val updated = item.copy(
            status = PendingReviewComment.STATUS_FAILED,
            lastError = error,
        )
        appDb.pendingReviewCommentDao.update(updated)
        return updated
    }

    /** 清除全部历史（含已发送），返回清除条数 */
    suspend fun clearAll(): Int {
        val total = appDb.pendingReviewCommentDao.countAll()
        appDb.pendingReviewCommentDao.clearAll()
        AppLog.putDebug("$LogTag 已清除全部离线评论 $total 条", module = LogModule.REVIEW_OFFLINE)
        return total
    }

    /** 导出全历史 JSON（含已发送），字段含类型/书籍/章节/段落位置/内容/状态 */
    fun buildExportJson(items: List<PendingReviewComment>): String {
        val exportedAt = System.currentTimeMillis()
        val itemsJson = items.map { item ->
            mapOf(
                "id" to item.id,
                "kind" to item.kindText(),
                "bookName" to item.bookName,
                "chapterTitle" to item.chapterTitle,
                "para" to item.para,
                "content" to item.content,
                "status" to item.statusText(),
                "targetCommentId" to item.targetCommentId,
                "reviewPageUrl" to item.reviewPageUrl,
                "buttonSrc" to item.buttonSrc,
                "createdAt" to item.createdAt,
                "sentAt" to item.sentAt,
                "attempts" to item.attempts,
                "lastError" to item.lastError,
            )
        }
        return GSON.toJson(
            mapOf(
                "version" to EXPORT_VERSION,
                "exportedAt" to exportedAt,
                "total" to items.size,
                "items" to itemsJson,
            )
        )
    }
}
