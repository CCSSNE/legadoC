package io.legado.app.ui.book.read.creation

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.ItemPhotoPagerBinding
import io.legado.app.databinding.ItemPhotoPagerVideoBinding
import io.legado.app.help.ai.AiCreationImageFile
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.widget.dialog.showActionBottomSheet
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 全屏查看创作结果：图片 / 视频。
 * 视频按 vid_ 前缀识别，页内直接用内置播放器播放，与正文插入视频一致。
 */
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

        private const val TYPE_IMAGE = 0
        private const val TYPE_VIDEO = 1
    }

    private val binding by viewBinding(io.legado.app.databinding.DialogPhotoViewBinding::bind)

    private val fileNames by lazy {
        arguments?.getStringArrayList("fileNames").orEmpty()
    }

    private var currentPage = 0
    private val players = hashMapOf<Int, ExoPlayer>()
    private var isLandscape = false

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
        currentPage = (arguments?.getInt("position") ?: 0).coerceIn(0, fileNames.size - 1)
        binding.photoPager.isUserInputEnabled = fileNames.size > 1
        binding.photoPager.adapter = PagerAdapter()
        binding.photoPager.setCurrentItem(currentPage, false)
        binding.photoPager.registerOnPageChangeCallback(object :
            ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage = position
                //滑走即停止播放：只保留当前页播放
                players.forEach { (pos, player) ->
                    player.playWhenReady = pos == currentPage
                }
            }
        })
    }

    override fun onDestroy() {
        players.values.forEach { it.release() }
        players.clear()
        if (isLandscape) {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        super.onDestroy()
    }

    private inner class PagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = fileNames.size

        override fun getItemViewType(position: Int): Int {
            return if (fileNames[position].startsWith("vid_")) TYPE_VIDEO else TYPE_IMAGE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_VIDEO) {
                VideoHolder(ItemPhotoPagerVideoBinding.inflate(layoutInflater, parent, false))
            } else {
                ImageHolder(ItemPhotoPagerBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val fileName = fileNames[position]
            when (holder) {
                is VideoHolder -> {
                    holder.pagePosition = position
                    val file = AiCreationImageFile.fileOf(fileName)
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
                    holder.binding.playerView.setOnLongClickListener {
                        showSaveSheet(fileName)
                        true
                    }
                }
                is ImageHolder -> {
                    ImageLoader.load(holder.binding.root.context, AiCreationImageFile.fileOf(fileName))
                        .dontTransform()
                        .into(holder.binding.photoView)
                    holder.binding.photoView.setOnClickListener { dismissAllowingStateLoss() }
                    holder.binding.photoView.setOnLongClickListener {
                        showSaveSheet(fileName)
                        true
                    }
                }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is VideoHolder) {
                players.remove(holder.pagePosition)?.release()
                holder.binding.playerView.player = null
                holder.binding.playerView.setOnLongClickListener(null)
                holder.pagePosition = -1
            }
            super.onViewRecycled(holder)
        }
    }

    private inner class ImageHolder(
        val binding: ItemPhotoPagerBinding
    ) : RecyclerView.ViewHolder(binding.root)

    private inner class VideoHolder(
        val binding: ItemPhotoPagerVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        var pagePosition: Int = -1
    }

    private fun toggleLandscape() {
        isLandscape = !isLandscape
        requireActivity().requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
