package io.legado.app.ui.book.read.creation

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.ItemPhotoPagerBinding
import io.legado.app.help.ai.AiCreationCardImages
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.widget.dialog.showActionBottomSheet
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 独立窗口预览 creation_images 引用原图：卡片编辑器图片格与提示词页图片条共用。
 * deletable 时长按可删除（回调宿主按标记收尾重排）或保存到相册；否则仅查看。
 */
class AiCreationRefPhotoDialog : BaseDialogFragment(R.layout.dialog_photo_view) {

    companion object {
        fun newInstance(
            refs: List<String>,
            position: Int,
            deletable: Boolean
        ): AiCreationRefPhotoDialog {
            return AiCreationRefPhotoDialog().apply {
                arguments = Bundle().apply {
                    putStringArrayList("refs", ArrayList(refs))
                    putInt("position", position)
                    putBoolean("deletable", deletable)
                }
            }
        }
    }

    interface CallBack {
        fun onDeleteRefPhoto(index: Int)
    }

    private val binding by viewBinding(io.legado.app.databinding.DialogPhotoViewBinding::bind)

    private val refs by lazy { arguments?.getStringArrayList("refs").orEmpty() }
    private val deletable get() = arguments?.getBoolean("deletable") == true

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.setBackgroundColor(Color.BLACK)
        if (refs.isEmpty()) {
            dismissAllowingStateLoss()
            return
        }
        binding.photoPager.isUserInputEnabled = refs.size > 1
        binding.photoPager.adapter = PagerAdapter()
        val position = (arguments?.getInt("position") ?: 0).coerceIn(0, refs.size - 1)
        binding.photoPager.setCurrentItem(position, false)
    }

    private inner class PagerAdapter : RecyclerView.Adapter<RefHolder>() {

        override fun getItemCount(): Int = refs.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RefHolder {
            return RefHolder(ItemPhotoPagerBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: RefHolder, position: Int) {
            val ref = refs[position]
            ImageLoader.load(holder.binding.root.context, AiCreationCardImages.fileOf(ref))
                .dontTransform()
                .into(holder.binding.photoView)
            holder.binding.photoView.setOnClickListener { dismissAllowingStateLoss() }
            holder.binding.photoView.setOnLongClickListener {
                if (deletable) {
                    showActions(position, ref)
                    true
                } else {
                    false
                }
            }
        }
    }

    private inner class RefHolder(
        val binding: ItemPhotoPagerBinding
    ) : RecyclerView.ViewHolder(binding.root)

    private fun showActions(position: Int, ref: String) {
        showActionBottomSheet(
            requireContext(),
            listOf(
                SelectItem(getString(R.string.illustration_save_to_album), "save"),
                SelectItem(getString(R.string.delete), "delete")
            )
        ) { action ->
            when (action) {
                "save" -> {
                    val ok = AiCreationCardImages.saveToAlbum(
                        requireContext(),
                        ref,
                        ref.substringAfterLast('/')
                    )
                    toastOnUi(
                        if (ok) R.string.illustration_saved_to_album
                        else R.string.illustration_save_failed
                    )
                }

                else -> {
                    (parentFragment as? CallBack)?.onDeleteRefPhoto(position)
                    dismissAllowingStateLoss()
                }
            }
        }
    }
}
