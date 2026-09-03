package io.legado.app.ui.main.homepage

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.domain.model.BookShelfState
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.dpToPx

/** 书架状态角标：已在书架显示对勾，同名作者显示乱序图标，底色随日夜主题反转 */
internal object HomepageBookBadge {

    fun bind(flBadge: FrameLayout, ivBadge: ImageView, item: HomepageBookItemUi) {
        when (item.shelfState) {
            BookShelfState.IN_SHELF, BookShelfState.SAME_NAME_AUTHOR -> {
                flBadge.isVisible = true
                val isLight = !AppConfig.isNightTheme
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4.dpToPx().toFloat()
                    setColor(if (isLight) Color.WHITE else Color.BLACK)
                }
                flBadge.background = bg
                ivBadge.setImageResource(
                    if (item.shelfState == BookShelfState.IN_SHELF) {
                        R.drawable.ic_check
                    } else {
                        R.drawable.ic_homepage_shuffle
                    }
                )
                ivBadge.setColorFilter(if (isLight) Color.BLACK else Color.WHITE)
            }

            BookShelfState.NOT_IN_SHELF -> flBadge.isVisible = false
        }
    }
}
