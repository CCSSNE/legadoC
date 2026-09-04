package io.legado.app.ui.book.read.creation

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.help.ai.AiCreationImageTaskHolder
import io.legado.app.utils.SurfaceBackdrop
import io.legado.app.utils.dpToPx

/**
 * AI 创作生成任务悬浮窗的统一宿主：各 Activity（经 BaseActivity）与创作对话框
 * 共用同一视觉形态与交互，是否显示与打开行为由宿主注入，
 * 转圈/完成态由 AiCreationImageTaskHolder 的任务状态驱动。
 */
class AiCreationFloatingHost(
    private val container: ViewGroup,
    private val layoutParams: () -> ViewGroup.LayoutParams,
    private val onOpen: () -> Unit
) {

    private var view: View? = null

    fun update(show: Boolean, taskRunning: Boolean) {
        if (!show) {
            view?.let { container.removeView(it) }
            view = null
            return
        }
        val floating = view ?: attach()
        floating.findViewById<View>(R.id.rotate_loading).visibility =
            if (taskRunning) View.VISIBLE else View.GONE
        floating.findViewById<View>(R.id.tv_floating_done).visibility =
            if (taskRunning) View.GONE else View.VISIBLE
    }

    private fun attach(): View {
        val view = LayoutInflater.from(container.context)
            .inflate(R.layout.floating_ai_creation, container, false)
        view.background = GradientDrawable().apply {
            cornerRadius = 22.dpToPx().toFloat()
            setColor(Color.parseColor("#CC222222"))
        }
        container.addView(view, layoutParams())
        // 纸模糊背景：悬浮窗属于纸上内容，不参与弹窗/菜单的模糊采集
        SurfaceBackdrop.excludeFromPaperCapture(view)
        view.setOnClickListener { onOpen() }
        view.findViewById<View>(R.id.iv_floating_close).setOnClickListener {
            AiCreationImageTaskHolder.dismissFloating()
        }
        this.view = view
        return view
    }
}
