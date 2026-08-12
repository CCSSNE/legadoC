package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogPhotoViewBinding
import io.legado.app.databinding.ItemPhotoPagerBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.help.illustration.IllustrationHelp
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.BookCover
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.widget.image.PhotoView
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏查看图片。
 *
 * 支持单图与多图：多图时左右滑动浏览；配图长按从底部弹出"保存到相册"。
 */
class PhotoDialog() : BaseDialogFragment(R.layout.dialog_photo_view) {

    constructor(src: String, sourceOrigin: String? = null, isBook: Boolean = false) : this() {
        arguments = Bundle().apply {
            putStringArrayList("srcs", arrayListOf(src))
            putInt("position", 0)
            putString("sourceOrigin", sourceOrigin)
            putBoolean("isBook", isBook)
        }
    }

    constructor(
        srcs: List<String>,
        position: Int = 0,
        sourceOrigin: String? = null,
        isBook: Boolean = false
    ) : this() {
        arguments = Bundle().apply {
            putStringArrayList("srcs", ArrayList(srcs))
            putInt("position", position)
            putString("sourceOrigin", sourceOrigin)
            putBoolean("isBook", isBook)
        }
    }

    private val binding by viewBinding(DialogPhotoViewBinding::bind)

    private val srcs by lazy {
        arguments?.getStringArrayList("srcs").orEmpty()
    }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        // 全屏看图背景固定黑色，不受主题 dialogSurface 覆盖影响
        binding.root.setBackgroundColor(Color.BLACK)
        if (srcs.isEmpty()) {
            dismissAllowingStateLoss()
            return
        }
        binding.photoPager.adapter = PhotoPagerAdapter()
        val position = (arguments?.getInt("position") ?: 0).coerceIn(0, srcs.size - 1)
        binding.photoPager.setCurrentItem(position, false)
    }

    private fun showSaveSheet(src: String) {
        showActionBottomSheet(
            requireContext(),
            listOf(SelectItem(getString(R.string.illustration_save_to_album), "save"))
        ) {
            saveToAlbum(src)
        }
    }

    private fun saveToAlbum(src: String) {
        val book = ReadBook.book ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = IllustrationHelp.saveToAlbum(requireContext(), book, src)
            withContext(Dispatchers.Main) {
                toastOnUi(
                    if (ok) R.string.illustration_saved_to_album else R.string.illustration_save_failed
                )
            }
        }
    }

    private inner class PhotoPagerAdapter : RecyclerView.Adapter<PhotoPagerAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(ItemPhotoPagerBinding.inflate(layoutInflater, parent, false))
        }

        override fun getItemCount(): Int = srcs.size

        @SuppressLint("CheckResult")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val src = srcs[position]
            loadImage(holder.binding.photoView, src)
            holder.binding.photoView.setOnLongClickListener {
                if (src.startsWith(IllustrationHelp.SRC_PREFIX)) {
                    showSaveSheet(src)
                    true
                } else {
                    false
                }
            }
        }

        inner class Holder(val binding: ItemPhotoPagerBinding) :
            RecyclerView.ViewHolder(binding.root)
    }

    @SuppressLint("CheckResult")
    private fun loadImage(photoView: PhotoView, src: String) {
        ImageProvider.get(src)?.let {
            photoView.setImageBitmap(it)
            return
        }
        val isBook = arguments?.getBoolean("isBook") == true
        val file = if (isBook) ReadBook.book?.let { book ->
            BookHelp.getImage(book, src)
        } else null
        if (file?.exists() == true) {
            ImageLoader.load(requireContext(), file)
                .error(R.drawable.image_loading_error)
                .dontTransform()
                .downsample(DownsampleStrategy.NONE)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(photoView)
        } else {
            ImageLoader.load(requireContext(), src).apply {
                arguments?.getString("sourceOrigin")?.let { sourceOrigin ->
                    apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, sourceOrigin))
                }
            }.error(if (isBook) BookCover.defaultDrawable else R.drawable.image_loading_error)
                .dontTransform()
                .downsample(DownsampleStrategy.NONE)
                .into(photoView)
        }
    }

}
