package io.legado.app.ui.book.read.config

import android.content.Context
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.surface.SurfaceStyle

/** Preference-content variant of the reading-page bottom-sheet contract. */
abstract class BaseReaderSheetPrefDialogFragment : BasePrefDialogFragment() {

    protected open fun readerSheetBaseColor(context: Context): Int = context.bottomBackground

    override fun dialogSurfaceStyle(context: Context): SurfaceStyle {
        return ReaderSheetStyle.topSheetSurfaceStyle(context, readerSheetBaseColor(context))
    }
}
