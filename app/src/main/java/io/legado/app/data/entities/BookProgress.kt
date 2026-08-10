package io.legado.app.data.entities

data class BookProgress(
    val name: String,
    val author: String,
    val durChapterIndex: Int,
    val durChapterPos: Int,
    val durChapterTime: Long,
    val durChapterTitle: String?
) {

    constructor(book: Book) : this(
        name = book.name,
        author = book.author,
        durChapterIndex = book.durChapterIndex,
        durChapterPos = book.durChapterPos,
        durChapterTime = book.durChapterTime,
        durChapterTitle = book.durChapterTitle
    )

    fun compareWith(book: Book): BookProgressComparison {
        return when {
            durChapterTime > book.durChapterTime -> BookProgressComparison.REMOTE_NEWER
            durChapterTime < book.durChapterTime -> BookProgressComparison.LOCAL_NEWER
            durChapterIndex > book.durChapterIndex -> BookProgressComparison.REMOTE_NEWER
            durChapterIndex < book.durChapterIndex -> BookProgressComparison.LOCAL_NEWER
            durChapterPos > book.durChapterPos -> BookProgressComparison.REMOTE_NEWER
            durChapterPos < book.durChapterPos -> BookProgressComparison.LOCAL_NEWER
            else -> BookProgressComparison.SAME
        }
    }

}

enum class BookProgressComparison {
    SAME,
    LOCAL_NEWER,
    REMOTE_NEWER
}
