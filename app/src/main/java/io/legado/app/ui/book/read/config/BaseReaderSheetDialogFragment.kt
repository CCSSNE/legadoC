package io.legado.app.ui.book.read.config

import android.content.Context
import androidx.annotation.LayoutRes
import io.legado.app.base.BaseBottomSheetDialogFragment
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.surface.SurfaceStyle

/** One visual surface contract shared by every bottom sheet on the reading page. */
abstract class BaseReaderSheetDialogFragment(
    @LayoutRes layoutId: Int
) : BaseBottomSheetDialogFragment(layoutId) {

    protected open fun readerSheetBaseColor(context: Context): Int = context.bottomBackground

    override fun dialogSurfaceStyle(context: Context): SurfaceStyle {
        return ReaderSheetStyle.topSheetSurfaceStyle(context, readerSheetBaseColor(context))
    }
}
