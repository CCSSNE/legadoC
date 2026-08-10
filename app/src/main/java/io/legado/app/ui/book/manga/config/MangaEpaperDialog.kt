package io.legado.app.ui.book.manga.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogMangaEpaperBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

class MangaEpaperDialog(
    private val enableOnConfirm: Boolean = false
) : BaseDialogFragment(R.layout.dialog_manga_epaper) {
    private val binding by viewBinding(DialogMangaEpaperBinding::bind)
    private val callback get() = activity as? Callback
    private var initialMangaEInkEnabled = false
    private var initialMangaEInkThreshold = 150
    private var mMangaEInkThreshold = 150
    private var confirmed = false

    override fun onStart() {
        super.onStart()
        dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initData()
        initView()
    }

    private fun initData() {
        initialMangaEInkEnabled = AppConfig.enableMangaEInk
        initialMangaEInkThreshold = AppConfig.mangaEInkThreshold
        mMangaEInkThreshold = initialMangaEInkThreshold
        binding.dsbEpaper.progress = initialMangaEInkThreshold
        callback?.previewEpaper(initialMangaEInkEnabled || enableOnConfirm, initialMangaEInkThreshold)
    }

    private fun initView() {
        binding.dsbEpaper.onChanged = {
            mMangaEInkThreshold = it
            callback?.previewEpaper(initialMangaEInkEnabled || enableOnConfirm, it)
        }
        binding.tvCancel.setOnClickListener {
            dismiss()
        }
        binding.tvOk.setOnClickListener {
            confirmed = true
            AppConfig.mangaEInkThreshold = mMangaEInkThreshold
            if (enableOnConfirm) {
                callback?.enableEpaper(mMangaEInkThreshold)
            } else {
                callback?.onEpaperSettingConfirmed()
            }
            dismiss()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!confirmed) {
            callback?.restoreEpaper(initialMangaEInkEnabled, initialMangaEInkThreshold)
        }
    }

    interface Callback {
        fun previewEpaper(enable: Boolean, value: Int)
        fun restoreEpaper(enable: Boolean, value: Int)
        fun enableEpaper(value: Int) = Unit
        fun onEpaperSettingConfirmed() = Unit
    }

}
