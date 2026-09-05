package io.legado.app.ui.book.read.config

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
import io.legado.app.help.ai.AiCreationImageFile
import io.legado.app.help.ai.AiCreationInsertStash
import io.legado.app.help.ai.AiCreationMediaMetadata
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.utils.SelectImagesContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 插入媒体对话框：选择图片、视频或音频，设置显示高度、布局、独占一页、备注。
 * 备注默认统一填写（本次插入的所有记录共用）；取消"统一备注"后按成组单元逐条填写。
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

    /** 成组单元：firstIndex 为单元内第一个媒体的下标，indexes 为单元包含的媒体下标 */
    private data class MediaUnit(
        val firstIndex: Int,
        val indexes: List<Int>,
        val isAudio: Boolean
    )

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

    // 选择时统一读取字节并解析类型，保存与逐条备注分组共用这份结果
    private val parsedMedia = arrayListOf<Pair<ByteArray, String>>() // bytes to ext

    // 与 parsedMedia 平行索引：各媒体内嵌的工作流 JSON 原文（无元数据为 null）
    private val parsedWorkflows = arrayListOf<String?>()

    private val unitNoteEdits = arrayListOf<EditText>()
    private val unitKeys = arrayListOf<Int>()
    private val unitNotes = LinkedHashMap<Int, String>() // 单元 firstIndex -> 备注

    private val selectImages = registerForActivityResult(SelectImagesContract()) {
        if (it.uris.isNotEmpty()) {
            val parsed = arrayListOf<Pair<ByteArray, String>>()
            it.uris.forEach { uri ->
                // 选择器放开 */* 后可能选到非媒体文件，先统一读取字节并解析类型
                val bytes = kotlin.runCatching {
                    requireContext().contentResolver.openInputStream(uri)?.use { s ->
                        s.readBytes()
                    }
                }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    toastOnUi("读取媒体文件失败")
                    return@forEach
                }
                // 文件选择器返回的 MIME 可能不可靠（null/octet-stream/报成 image 类），
                // 按文件名扩展名 → MIME → 文件头嗅探三级判断，确保视频/音频不会落成 jpg
                val name = IllustrationHelp.queryDisplayName(requireContext(), uri)
                val mime = requireContext().contentResolver.getType(uri)
                val ext = IllustrationHelp.resolveMediaExt(name, mime, bytes)
                if (ext !in IllustrationHelp.VIDEO_EXTS &&
                    ext !in IllustrationHelp.AUDIO_EXTS &&
                    ext !in IllustrationHelp.IMAGE_EXTS
                ) {
                    toastOnUi("仅支持图片、视频、音频文件")
                    return@forEach
                }
                parsed.add(bytes to ext)
            }
            if (parsed.size != it.uris.size) {
                // 有媒体读取或解析失败：本次选择整体不生效，直接暴露问题
                return@registerForActivityResult
            }
            selectedUris.clear()
            selectedUris.addAll(it.uris)
            parsedMedia.clear()
            parsedMedia.addAll(parsed)
            parsedWorkflows.clear()
            parsedWorkflows.addAll(
                parsed.map { (bytes, _) ->
                    //AI 创作生成的文件自带工作流元数据（PNG 文本块 / MP4 meta box），按签名读取
                    runCatching { AiCreationMediaMetadata.readWorkflowJson(bytes) }.getOrNull()
                }
            )
            upSelected()
            rebuildNoteInputs()
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
        binding.rgLayout.setOnCheckedChangeListener { _, _ ->
            rebuildNoteInputs()
        }
        binding.cbUnifiedNote.isChecked = true
        binding.cbUnifiedNote.setOnCheckedChangeListener { _, _ ->
            rebuildNoteInputs()
        }
        prefillStashedMedia()
        rebuildNoteInputs()
    }

    /**
     * 暂存预填：别处长按点的"插入"攒在这里，开弹窗就把媒体装进来（只看不拿，等插完再清）；
     * 内嵌工作流照读，备注预填走后面统一逻辑；文件没了整批作废并明说。
     */
    private fun prefillStashedMedia() {
        val stashed = AiCreationInsertStash.peek()
        if (stashed.isEmpty()) return
        val parsed = arrayListOf<Pair<ByteArray, String>>()
        val workflows = arrayListOf<String?>()
        val uris = arrayListOf<Uri>()
        stashed.forEach { name ->
            val file = AiCreationImageFile.fileOf(name)?.takeIf { it.isFile } ?: return@forEach
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) return@forEach
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in IllustrationHelp.VIDEO_EXTS &&
                ext !in IllustrationHelp.AUDIO_EXTS &&
                ext !in IllustrationHelp.IMAGE_EXTS
            ) {
                return@forEach
            }
            parsed.add(bytes to ext)
            workflows.add(runCatching { AiCreationMediaMetadata.readWorkflowJson(bytes) }.getOrNull())
            uris.add(Uri.fromFile(file))
        }
        if (parsed.isEmpty()) {
            AiCreationInsertStash.clear()
            toastOnUi(R.string.ai_creation_stash_gone)
            return
        }
        selectedUris.clear()
        selectedUris.addAll(uris)
        parsedMedia.clear()
        parsedMedia.addAll(parsed)
        parsedWorkflows.clear()
        parsedWorkflows.addAll(workflows)
        upSelected()
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

    /**
     * 成组单元与保存时的记录一一对应：图片/视频按所选布局分块，音频永不参与宫格单独成格；
     * 各块按原始选择顺序排序（音频夹在宫格区间内时排在宫格块之后）。
     */
    private fun computeUnits(): List<MediaUnit> {
        val cellCount = layoutCellCount()
        val units = arrayListOf<MediaUnit>()
        parsedMedia.mapIndexedNotNull { index, m ->
            if (m.second in IllustrationHelp.AUDIO_EXTS) null else index
        }.chunked(cellCount).forEach { chunk ->
            units.add(MediaUnit(chunk.first(), chunk, false))
        }
        parsedMedia.forEachIndexed { index, m ->
            if (m.second in IllustrationHelp.AUDIO_EXTS) {
                units.add(MediaUnit(index, listOf(index), true))
            }
        }
        units.sortBy { it.firstIndex }
        return units
    }

    private fun snapshotUnitNotes() {
        unitNoteEdits.forEachIndexed { i, edit ->
            unitKeys.getOrNull(i)?.let { key ->
                unitNotes[key] = edit.text.toString()
            }
        }
    }

    /** 备注输入区：统一备注勾选时单个输入框；取消勾选后按成组单元逐条输入 */
    private fun rebuildNoteInputs() {
        snapshotUnitNotes()
        val unified = binding.cbUnifiedNote.isChecked
        binding.etNote.visibility = if (unified) View.VISIBLE else View.GONE
        binding.llNoteItems.visibility = if (unified) View.GONE else View.VISIBLE
        binding.llNoteItems.removeAllViews()
        unitNoteEdits.clear()
        unitKeys.clear()
        if (unified) {
            //自带工作流元数据的媒体：预填内嵌工作流 JSON 完整原文，仅填空备注，不覆盖用户已输入内容
            if (binding.etNote.text.isNullOrBlank()) {
                firstWorkflowJson()?.let { binding.etNote.setText(it) }
            }
            return
        }
        val context = requireContext()
        computeUnits().forEachIndexed { unitIndex, unit ->
            val label = TextView(context).apply {
                text = if (unit.isAudio) {
                    getString(R.string.illustration_note_audio_unit, unitIndex + 1)
                } else {
                    getString(R.string.illustration_note_image_unit, unitIndex + 1, unit.indexes.size)
                }
                setTextColor(context.getCompatColor(R.color.primaryText))
                textSize = 14f
            }
            val edit = EditText(context).apply {
                hint = getString(R.string.illustration_note_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                gravity = Gravity.TOP or Gravity.START
                background = null
                setTextColor(context.getCompatColor(R.color.primaryText))
                textSize = 14f
                setText(unitNotes[unit.firstIndex] ?: workflowJsonOf(unit.firstIndex).orEmpty())
            }
            val labelParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            labelParams.topMargin = 12.dpToPx()
            label.layoutParams = labelParams
            val editParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            editParams.topMargin = 4.dpToPx()
            edit.layoutParams = editParams
            binding.llNoteItems.addView(label)
            binding.llNoteItems.addView(edit)
            unitNoteEdits.add(edit)
            unitKeys.add(unit.firstIndex)
        }
        binding.llNoteItems.applyUiBodyTypefaceDeep(context.uiTypeface())
    }

    /** 指定媒体内嵌的工作流 JSON 原文：存的啥填啥，不解析、不提取；无元数据返回 null 不预填 */
    private fun workflowJsonOf(mediaIndex: Int): String? =
        parsedWorkflows.getOrNull(mediaIndex)?.takeIf { it.isNotBlank() }

    private fun firstWorkflowJson(): String? =
        parsedWorkflows.indices.firstNotNullOfOrNull { workflowJsonOf(it) }

    private fun save() {
        if (parsedMedia.isEmpty()) {
            toastOnUi(R.string.illustration_no_images)
            return
        }
        val book = ReadBook.book ?: return
        val chapter = ReadBook.curTextChapter?.chapter ?: return
        val heightText = binding.etHeight.text.toString().trim()
        val displayHeight = heightText.toIntOrNull() ?: 0
        val pageBreak = binding.cbPageBreak.isChecked
        snapshotUnitNotes()
        val unified = binding.cbUnifiedNote.isChecked
        val unifiedNote = binding.etNote.text.toString()
        val units = computeUnits()
        // 保存媒体文件，选择顺序与单元下标一致
        val srcs = parsedMedia.map { (bytes, ext) ->
            val src = IllustrationHelp.newSrc(ext)
            IllustrationHelp.saveImage(book, src, bytes)
            src
        }
        val records = arrayListOf<BookIllustration>()
        units.forEach { unit ->
            val unitSrcs = unit.indexes.map { srcs[it] }
            val note = if (unified) unifiedNote else unitNotes[unit.firstIndex].orEmpty()
            records.add(
                newRecord(
                    book,
                    chapter,
                    unitSrcs,
                    displayHeight,
                    pageBreak,
                    records.size,
                    note = note,
                    single = unit.isAudio
                )
            )
        }
        if (records.isEmpty()) return
        appDb.bookIllustrationDao.insert(*records.toTypedArray())
        //插完暂存清掉，下次不再默认带入
        AiCreationInsertStash.clear()
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
        note: String,
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
            sortOrder = sortOrder,
            note = note
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
