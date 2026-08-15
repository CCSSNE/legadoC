package io.legado.app.base

import android.content.Context
import androidx.annotation.LayoutRes
import io.legado.app.lib.theme.surface.SurfaceCorners
import io.legado.app.lib.theme.surface.SurfaceStyle
import io.legado.app.lib.theme.surface.SurfaceStyles

/** Base contract for dialogs whose owned surface is attached to the bottom edge. */
abstract class BaseBottomSheetDialogFragment(
    @LayoutRes layoutId: Int,
    adaptationSoftKeyboard: Boolean = false
) : BaseDialogFragment(layoutId, adaptationSoftKeyboard) {

    override fun dialogSurfaceStyle(context: Context): SurfaceStyle {
        return SurfaceStyles.dialog(context, SurfaceCorners.TOP)
    }
}
