package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize
import org.json.JSONObject

/**
 * 配图（插图）记录
 *
 * 锚点策略：
 * - 段间配图：anchorType = between_paragraphs，anchorPos 为章节文本（排版后）中的段落边界字符偏移，
 *   frontFingerprint / backFingerprint 为边界前后段落文本指纹，用于章节内容变化后的容错重定位。
 * - 章末配图：anchorType = chapter_end，插入该章末尾。
 */
@Parcelize
@Entity(
    tableName = "book_illustrations",
    indices = [
        Index(value = ["bookUrl"]),
        Index(value = ["bookUrl", "chapterIndex"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["bookUrl"],
            childColumns = ["bookUrl"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class BookIllustration(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookUrl: String = "",
    val chapterIndex: Int = 0,
    val chapterUrl: String = "",
    val chapterName: String = "",
    val anchorType: String = ANCHOR_BETWEEN_PARAGRAPHS,
    val anchorPos: Int = -1,
    val frontParagraphText: String = "",
    val backParagraphText: String = "",
    val frontFingerprint: String = "",
    val backFingerprint: String = "",
    val imageSrcs: String = "[]",
    val layoutType: String = LAYOUT_SINGLE,
    val displayHeight: Int = 0,
    val pageBreak: Boolean = false,
    val sortOrder: Int = 0,
    val pdfPage: Int = -1,
    val pdfRect: String = "",
    /** 配图备注（Markdown 文本），空串表示无备注 */
    val note: String = "",
    /**
     * 每图备注：src -> 备注（宫格多图各写各的，排版只管显示不管备注）；
     * 单图、统一备注走 note 字段，本字段为空对象。
     */
    val srcNotes: String = "{}"
) : Parcelable {

    companion object {
        const val ANCHOR_BETWEEN_PARAGRAPHS = "between_paragraphs"
        const val ANCHOR_CHAPTER_END = "chapter_end"

        const val LAYOUT_SINGLE = "single"
        const val LAYOUT_DOUBLE = "double"
        const val LAYOUT_TRIPLE = "triple"
        const val LAYOUT_QUAD = "quad"
        const val LAYOUT_QUAD_GRID = "quad_grid"
    }

    /** 每图备注表：非法 JSON 直接视为空，不抛 */
    fun srcNotesMap(): Map<String, String> = runCatching {
        val obj = JSONObject(srcNotes)
        obj.keys().asSequence().associateWith { obj.optString(it).orEmpty() }
    }.getOrDefault(emptyMap())

    /** 写回单图备注：空串摘掉该图条目 */
    fun withSrcNote(src: String, note: String): BookIllustration {
        val map = srcNotesMap().toMutableMap()
        if (note.isBlank()) map.remove(src) else map[src] = note
        return copy(srcNotes = JSONObject(map as Map<*, *>).toString())
    }
}
