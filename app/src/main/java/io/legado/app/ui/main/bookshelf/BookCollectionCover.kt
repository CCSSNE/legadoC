package io.legado.app.ui.main.bookshelf

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import android.view.View
import android.graphics.drawable.GradientDrawable
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ViewBookCollectionMosaicBinding
import io.legado.app.lib.theme.UiCorner

fun ViewBookCollectionMosaicBinding.loadCollectionCovers(
    books: List<Book>,
    fragment: Fragment? = null,
    lifecycle: Lifecycle? = null,
    dialogSurface: Boolean = false,
    collectionName: String? = null
) {
    val backgroundColor = UiCorner.themeSurfaceCardColor(root.context)
    val coverAlpha = if (dialogSurface) {
        UiCorner.dialogSurfaceAlpha()
    } else {
        UiCorner.bookshelfCoverAlpha()
    }
    // A dialog mosaic is composited once as a whole. Its children must remain
    // opaque so the same dialog alpha is not multiplied by nested layers.
    root.alpha = if (dialogSurface) coverAlpha else 1f
    root.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = root.resources.getDimension(R.dimen.book_collection_cover_corner_radius)
        setColor(
            if (dialogSurface) {
                backgroundColor
            } else {
                UiCorner.bookshelfCoverSurfaceColor(backgroundColor)
            }
        )
    }
    root.clipToOutline = true
    vwShadow.visibility = if (dialogSurface) View.GONE else View.VISIBLE
    vwShadow.alpha = if (dialogSurface) 1f else coverAlpha
    tvCollectionName.text = collectionName
    tvCollectionName.visibility = if (collectionName.isNullOrBlank()) View.GONE else View.VISIBLE
    val covers = listOf(ivCover1, ivCover2, ivCover3, ivCover4)
    covers.forEachIndexed { index, imageView ->
        imageView.alpha = if (dialogSurface) 1f else coverAlpha
        val book = books.getOrNull(index)
        if (book == null) {
            // 缺书的空位保持占位，不显示封面也不放大已有封面
            imageView.visibility = View.INVISIBLE
        } else {
            imageView.visibility = View.VISIBLE
            imageView.loadThumb(book, false, fragment, lifecycle)
            imageView.alpha = if (dialogSurface) 1f else coverAlpha
        }
    }
    // 行始终占位，避免剩余封面被放大填充空位
    row1.visibility = View.VISIBLE
    row2.visibility = View.VISIBLE
}
