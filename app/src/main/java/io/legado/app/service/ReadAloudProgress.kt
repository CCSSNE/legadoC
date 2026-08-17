package io.legado.app.service

data class ReadAloudProgress(
    val chapterIndex: Int,
    val position: Int,
    val total: Int,
    val kind: Kind,
) {
    init {
        require(chapterIndex >= 0) { "chapterIndex must be non-negative: $chapterIndex" }
        require(total > 0) { "total must be positive: $total" }
        when (kind) {
            Kind.PARAGRAPH -> require(position in 0 until total) {
                "paragraph position must be within total: position=$position, total=$total"
            }
            Kind.TIME -> require(position in 0..total) {
                "time position must be within total: position=$position, total=$total"
            }
        }
    }

    enum class Kind {
        PARAGRAPH,
        TIME,
    }
}
