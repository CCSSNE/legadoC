package io.legado.app.ui.book.read.config

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookIllustration
import io.legado.app.databinding.DialogIllustrationEditBinding
import io.legado.app.databinding.ItemImageSimpleBinding
import io.legado.app.help.illustration.IllustrationAnchor
import io.legado.app.help.illustration.IllustrationHelp
import io.legado.app.help.illustration.imageSrcsToJson
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.utils.SelectImagesContract
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 插入配图对话框：选择图片、设置显示高度、布局、独占一页。
 */
class IllustrationEditDialog() : BaseDialogFragment(R.layout.dialog_illustration_edit, true) {

    constructor(anchor: IllustrationAnchor) : this() {
        arguments = Bundle().apply {
            putString("anchorType", anchor.anchorType)
            putInt("anchorPos", anchor.anchorPos)
            putString("frontParagraph", anchor.frontParagraph)
            putString("backParagraph", anchor.backParagraph)
        }
    }

    private val binding by viewBinding(DialogIllustrationEditBinding::bind)
    private val anchor by lazy {
        IllustrationAnchor(
            anchorType = arguments?.getString("anchorType").orEmpty(),
            anchorPos = arguments?.getInt("anchorPos") ?: -1,
            frontParagraph = arguments?.getString("frontParagraph").orEmpty(),
            backParagraph = arguments?.getString("backParagraph").orEmpty()
        )
    }

    private val selectedUris = arrayListOf<Uri>()

    private val selectImages = registerForActivityResult(SelectImagesContract()) {
        if (it.uris.isNotEmpty()) {
            selectedUris.clear()
            selectedUris.addAll(it.uris)
            upSelected()
        }
    }

    private var thumbAdapter: ThumbAdapter? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.rvSelected.layoutManager = LinearLayoutManager(
            requireContext(),
            LinearLayoutManager.HORIZONTAL,
            false
        )
        thumbAdapter = ThumbAdapter()
        binding.rvSelected.adapter = thumbAdapter
        binding.tvPickImages.setOnClickListener {
            selectImages.launch(0)
        }
        binding.tvCancel.setOnClickListener {
            dismissAllowingStateLoss()
        }
        binding.tvOk.setOnClickListener {
            save()
        }
        binding.rgLayout.check(binding.rbSingle.id)
    }

    private fun upSelected() {
        thumbAdapter?.setItems(selectedUris)
        binding.rvSelected.visible(selectedUris.isNotEmpty())
    }

    private fun selectedLayout(): String {
        return when (binding.rgLayout.checkedRadioButtonId) {
            binding.rbDouble.id -> BookIllustration.LAYOUT_DOUBLE
            binding.rbTriple.id -> BookIllustration.LAYOUT_TRIPLE
            binding.rbQuad.id -> BookIllustration.LAYOUT_QUAD
            binding.rbQuadGrid.id -> BookIllustration.LAYOUT_QUAD_GRID
            else -> BookIllustration.LAYOUT_SINGLE
        }
    }

    private fun layoutCellCount(): Int {
        return when (selectedLayout()) {
            BookIllustration.LAYOUT_DOUBLE -> 2
            BookIllustration.LAYOUT_TRIPLE -> 3
            BookIllustration.LAYOUT_QUAD -> 4
            BookIllustration.LAYOUT_QUAD_GRID -> 4
            else -> 1
        }
    }

    private fun save() {
        if (selectedUris.isEmpty()) {
            toastOnUi(R.string.illustration_no_images)
            return
        }
        val book = ReadBook.book ?: return
        val chapter = ReadBook.curTextChapter?.chapter ?: return
        val heightText = binding.etHeight.text.toString().trim()
        val displayHeight = heightText.toIntOrNull() ?: 0
        val pageBreak = binding.cbPageBreak.isChecked
        val cellCount = layoutCellCount()
        // 选择器放开 */* 后可能选到非媒体文件，先统一校验，避免中途保存一半
        selectedUris.forEach { uri ->
            val mime = requireContext().contentResolver.getType(uri)
            val isMedia = mime?.startsWith("image/") == true ||
                mime?.startsWith("video/") == true ||
                mime?.startsWith("audio/") == true
            if (!isMedia) {
                toastOnUi("仅支持图片、视频、音频文件")
                return
            }
        }
        // 先读取所有媒体并保存，记录选择顺序与是否音频
        val media = arrayListOf<Pair<String, Boolean>>() // src to isAudio
        selectedUris.forEach { uri ->
            val bytes = kotlin.runCatching {
                requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                toastOnUi("读取媒体文件失败")
                return
            }
            val mime = requireContext().contentResolver.getType(uri)
            val ext = when {
                mime?.startsWith("video/") == true ->
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                        ?.takeIf { it.isNotBlank() } ?: "mp4"
                mime?.startsWith("audio/") == true ->
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                        ?.takeIf { it.isNotBlank() } ?: "mp3"
                else ->
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                        ?.takeIf { it.isNotBlank() } ?: "jpg"
            }
            val src = IllustrationHelp.newSrc(ext)
            IllustrationHelp.saveImage(book, src, bytes)
            media.add(src to IllustrationHelp.isAudioSrc(src))
        }
        // 音频永不参与宫格：图片/视频按所选布局成宫格，音频单独成格；
        // 各块按原始选择顺序排序（音频夹在宫格区间内时排在宫格块之后）
        val units = arrayListOf<Triple<Int, List<String>, Boolean>>() // firstIndex, srcs, isAudio
        media.mapIndexedNotNull { index, m ->
            if (!m.second) index to m.first else null
        }.chunked(cellCount).forEach { chunk ->
            units.add(Triple(chunk.first().first, chunk.map { it.second }, false))
        }
        media.forEachIndexed { index, m ->
            if (m.second) units.add(Triple(index, listOf(m.first), true))
        }
        units.sortBy { it.first }
        val records = arrayListOf<BookIllustration>()
        units.forEach { (_, srcs, isAudio) ->
            records.add(
                newRecord(
                    book,
                    chapter,
                    srcs,
                    displayHeight,
                    pageBreak,
                    records.size,
                    single = isAudio
                )
            )
        }
        if (records.isEmpty()) return
        appDb.bookIllustrationDao.insert(*records.toTypedArray())
        dismissAllowingStateLoss()
        toastOnUi(R.string.illustration_inserted)
        callback?.invoke()
    }

    private fun newRecord(
        book: Book,
        chapter: BookChapter,
        srcs: List<String>,
        displayHeight: Int,
        pageBreak: Boolean,
        sortOrder: Int,
        single: Boolean = false
    ): BookIllustration {
        return BookIllustration(
            bookUrl = book.bookUrl,
            chapterIndex = chapter.index,
            chapterUrl = chapter.url,
            chapterName = chapter.title,
            anchorType = anchor.anchorType,
            anchorPos = anchor.anchorPos,
            frontParagraphText = anchor.frontParagraph,
            backParagraphText = anchor.backParagraph,
            frontFingerprint = IllustrationHelp.fingerprint(anchor.frontParagraph, false),
            backFingerprint = IllustrationHelp.fingerprint(anchor.backParagraph, true),
            imageSrcs = imageSrcsToJson(srcs),
            layoutType = if (single) BookIllustration.LAYOUT_SINGLE else selectedLayout(),
            displayHeight = displayHeight,
            pageBreak = pageBreak,
            sortOrder = sortOrder
        )
    }

    private var callback: (() -> Unit)? = null

    fun setOnInserted(callback: () -> Unit) {
        this.callback = callback
    }

    private inner class ThumbAdapter :
        RecyclerAdapter<Uri, ItemImageSimpleBinding>(requireContext()) {

        override fun getViewBinding(parent: ViewGroup): ItemImageSimpleBinding {
            return ItemImageSimpleBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemImageSimpleBinding,
            item: Uri,
            payloads: MutableList<Any>
        ) {
            binding.ivImage.run {
                io.legado.app.help.glide.ImageLoader.load(context, item).into(this)
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemImageSimpleBinding) {
            // 缩略图无需点击
        }
    }
}
