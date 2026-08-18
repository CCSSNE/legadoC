package io.legado.app.service

data class ReadAloudFloatingObstruction(
    val source: String,
    val topOnScreen: Int = 0,
    val bottomOnScreen: Int = 0,
    val active: Boolean = true,
) {
    companion object {
        fun clear(source: String) = ReadAloudFloatingObstruction(
            source = source,
            active = false,
        )
    }
}
