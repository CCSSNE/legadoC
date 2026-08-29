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
import io.legado.app.help.ai.AiCreationImageFile
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.widget.dialog.showActionBottomSheet
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AiCreationPhotoDialog : BaseDialogFragment(R.layout.dialog_photo_view) {

    companion object {
        fun newInstance(fileNames: List<String>, position: Int): AiCreationPhotoDialog {
            return AiCreationPhotoDialog().apply {
                arguments = Bundle().apply {
                    putStringArrayList("fileNames", ArrayList(fileNames))
                    putInt("position", position)
                }
            }
        }
    }

    private val binding by viewBinding(io.legado.app.databinding.DialogPhotoViewBinding::bind)

    private val fileNames by lazy {
        arguments?.getStringArrayList("fileNames").orEmpty()
    }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.setBackgroundColor(Color.BLACK)
        if (fileNames.isEmpty()) {
            dismissAllowingStateLoss()
            return
        }
        binding.photoPager.isUserInputEnabled = fileNames.size > 1
        binding.photoPager.adapter = PagerAdapter()
        binding.photoPager.setCurrentItem(
            (arguments?.getInt("position") ?: 0).coerceIn(0, fileNames.size - 1),
            false
        )
    }

    private inner class PagerAdapter : RecyclerView.Adapter<PhotoViewHolder>() {

        override fun getItemCount(): Int = fileNames.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
            return PhotoViewHolder(ItemPhotoPagerBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
            holder.bind(fileNames[position])
        }
    }

    private inner class PhotoViewHolder(
        private val itemBinding: ItemPhotoPagerBinding
    ) : RecyclerView.ViewHolder(itemBinding.root) {

        fun bind(fileName: String) = itemBinding.run {
            ImageLoader.load(root.context, AiCreationImageFile.fileOf(fileName))
                .dontTransform()
                .into(photoView)
            photoView.setOnClickListener { dismissAllowingStateLoss() }
            photoView.setOnLongClickListener {
                showSaveSheet(fileName)
                true
            }
        }
    }

    private fun showSaveSheet(fileName: String) {
        showActionBottomSheet(
            requireContext(),
            listOf(SelectItem(getString(R.string.illustration_save_to_album), "save"))
        ) {
            val ok = AiCreationImageFile.saveToAlbum(requireContext(), fileName)
            toastOnUi(
                if (ok) R.string.illustration_saved_to_album
                else R.string.illustration_save_failed
            )
        }
    }
}
