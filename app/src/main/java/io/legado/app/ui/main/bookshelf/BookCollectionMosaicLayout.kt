package io.legado.app.ui.main.bookshelf

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

class BookCollectionMosaicLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val measuredHeightSpec = if (width > 0 && heightMode != MeasureSpec.EXACTLY) {
            MeasureSpec.makeMeasureSpec(width * 4 / 3, MeasureSpec.EXACTLY)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, measuredHeightSpec)
    }
}
