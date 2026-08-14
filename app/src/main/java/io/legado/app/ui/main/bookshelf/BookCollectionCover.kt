package io.legado.app.ui.main.bookshelf

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.core.content.ContextCompat
import android.view.View
import android.graphics.drawable.ColorDrawable
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ViewBookCollectionMosaicBinding
import io.legado.app.lib.theme.UiCorner

fun ViewBookCollectionMosaicBinding.loadCollectionCovers(
    books: List<Book>,
    fragment: Fragment? = null,
    lifecycle: Lifecycle? = null,
    dialogSurface: Boolean = false
) {
    val backgroundColor = ContextCompat.getColor(root.context, R.color.background_card)
    root.background = ColorDrawable(
        if (dialogSurface) {
            UiCorner.dialogSurfaceColor(backgroundColor)
        } else {
            UiCorner.surfaceColor(backgroundColor)
        }
    )
    val coverAlpha = if (dialogSurface) {
        UiCorner.dialogSurfaceAlpha()
    } else {
        UiCorner.floatingGroupAlpha()
    }
    val covers = listOf(ivCover1, ivCover2, ivCover3, ivCover4)
    covers.forEachIndexed { index, imageView ->
        imageView.alpha = coverAlpha
        val book = books.getOrNull(index)
        if (book == null) {
            // 缺书的空位保持占位，不显示封面也不放大已有封面
            imageView.visibility = View.INVISIBLE
        } else {
            imageView.visibility = View.VISIBLE
            imageView.loadThumb(book, false, fragment, lifecycle)
            imageView.alpha = coverAlpha
        }
    }
    // 行始终占位，避免剩余封面被放大填充空位
    row1.visibility = View.VISIBLE
    row2.visibility = View.VISIBLE
}
