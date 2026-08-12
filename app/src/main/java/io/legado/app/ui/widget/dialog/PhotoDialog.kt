package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogPhotoViewBinding
import io.legado.app.databinding.ItemPhotoPagerBinding
import io.legado.app.databinding.ItemPhotoPagerVideoBinding
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
 * 全屏查看媒体：图片 / 视频。
 *
 * 多图/多视频可左右滑动，滑走即停止播放；单个视频不可滑动，可横屏。
 * 配图图片长按从底部弹出"保存到相册"。
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

    private var currentPage = 0
    private val players = hashMapOf<Int, ExoPlayer>()
    private var isLandscape = false

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
        currentPage = (arguments?.getInt("position") ?: 0).coerceIn(0, srcs.size - 1)
        // 单个媒体不允许左右滑动（单视频不能滑到其它媒体）
        binding.photoPager.isUserInputEnabled = srcs.size > 1
        binding.photoPager.adapter = PhotoPagerAdapter()
        binding.photoPager.setCurrentItem(currentPage, false)
        binding.photoPager.registerOnPageChangeCallback(object :
            androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage = position
                // 滑走即停止播放：只保留当前页播放
                players.forEach { (pos, player) ->
                    player.playWhenReady = pos == currentPage
                }
            }
        })
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

    override fun onDestroy() {
        players.values.forEach { it.release() }
        players.clear()
        if (isLandscape) {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        super.onDestroy()
    }

    private inner class PhotoPagerAdapter :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = srcs.size

        override fun getItemViewType(position: Int): Int {
            return if (IllustrationHelp.isVideoSrc(srcs[position])) {
                TYPE_VIDEO
            } else {
                TYPE_IMAGE
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_VIDEO) {
                VideoHolder(ItemPhotoPagerVideoBinding.inflate(layoutInflater, parent, false))
            } else {
                ImageHolder(ItemPhotoPagerBinding.inflate(layoutInflater, parent, false))
            }
        }

        @SuppressLint("CheckResult")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val src = srcs[position]
            when (holder) {
                is VideoHolder -> {
                    holder.pagePosition = position
                    val file = IllustrationHelp.getImageFile(ReadBook.book ?: return, src)
                    if (file.exists()) {
                        val player = ExoPlayer.Builder(requireContext()).build().apply {
                            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                            prepare()
                            playWhenReady = position == currentPage
                        }
                        players[position] = player
                        holder.binding.playerView.player = player
                        holder.binding.btnLandscape.setOnClickListener {
                            toggleLandscape()
                        }
                    }
                }
                is ImageHolder -> {
                    loadImage(holder.binding.photoView, src)
                    // 单击图片关闭全屏返回阅读页；PhotoView 内部单击与双击/缩放/长按互不冲突
                    holder.binding.photoView.setOnClickListener { dismissAllowingStateLoss() }
                    holder.binding.photoView.setOnLongClickListener {
                        if (src.startsWith(IllustrationHelp.SRC_PREFIX)) {
                            showSaveSheet(src)
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is VideoHolder) {
                players.remove(holder.pagePosition)?.release()
                holder.binding.playerView.player = null
                holder.pagePosition = -1
            }
            super.onViewRecycled(holder)
        }

        inner class ImageHolder(val binding: ItemPhotoPagerBinding) :
            RecyclerView.ViewHolder(binding.root)

        inner class VideoHolder(val binding: ItemPhotoPagerVideoBinding) :
            RecyclerView.ViewHolder(binding.root) {
            var pagePosition: Int = -1
        }
    }

    private fun toggleLandscape() {
        isLandscape = !isLandscape
        requireActivity().requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
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

    companion object {
        private const val TYPE_IMAGE = 0
        private const val TYPE_VIDEO = 1
    }
}
