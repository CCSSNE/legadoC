package io.legado.app.help.gsyVideo

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.widget.TextView
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import io.legado.app.R
import io.legado.app.data.entities.BookChapter
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.navigationBarHeight

class ChoiceEpisodeDialog(private val mContext: Context) : Dialog(
    mContext, R.style.dialog_style
) {
    private var listView: ListView? = null

    private var adapter: ArrayAdapter<BookChapter>? = null

    private var onItemClickListener: OnListItemClickListener? = null

    private var data: List<BookChapter>? = null

    interface OnListItemClickListener {
        fun onItemClick(position: Int)
        fun finishDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onStop() {
        onItemClickListener!!.finishDialog()
        super.onStop()
    }

    @SuppressLint("SetTextI18n")
    fun initList(
        data: List<BookChapter>,
        onItemClickListener: OnListItemClickListener,
        initialSelection: Int = -1
    ) {
        this.onItemClickListener = onItemClickListener
        this.data = data
        val inflater = LayoutInflater.from(mContext)
        val view: View = inflater.inflate(R.layout.switch_episode_video_dialog, null)
        view.setBackgroundColor(ColorUtils.adjustAlpha(mContext.backgroundColor, 0.96f))
        val bottomPadding = mContext.navigationBarHeight + 8.dpToPx()
        view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottomPadding)
        view.findViewById<TextView>(R.id.listCount).apply {
            text = "选集（${data.size}）"
            setTextColor(mContext.secondaryTextColor)
        }
        listView = view.findViewById(R.id.switch_dialog_list)
        setContentView(view)
        adapter = SwitchVideoAdapter(mContext, data) { item -> item.title }
        listView!!.setAdapter(adapter)
        if (initialSelection >= 0 && initialSelection < data.size) {
            listView!!.setSelectionFromTop(initialSelection, 0)
        }
        listView!!.onItemClickListener = this@ChoiceEpisodeDialog.OnItemClickListener()
        val dialogWindow = window
        val lp = dialogWindow!!.attributes
        val d = mContext.resources.displayMetrics // 获取屏幕宽、高用
        lp.width = (d.widthPixels * 0.4).toInt() // 宽度设置为屏幕的0.4
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.END // 设置靠右对齐
        dialogWindow.setAttributes(lp)
    }

    private inner class OnItemClickListener : AdapterView.OnItemClickListener {
        override fun onItemClick(
            adapterView: AdapterView<*>?,
            view: View?,
            position: Int,
            id: Long
        ) {
            dismiss()
            onItemClickListener!!.onItemClick(position)
        }
    }
}
