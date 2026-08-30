package io.legado.app.ui.book.read.config

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemHttpTtsBinding
import io.legado.app.help.IntentHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.help.book.isAudio
import io.legado.app.plugin.ReadAloudEngines
import io.legado.app.plugin.ReadAloudEnginePlugin
import io.legado.app.ui.association.ImportHttpTtsDialog
import io.legado.app.ui.config.TtsEngineManageActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.applyUiMenuStyle
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.gone
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File

/**
 * tts引擎管理
 */
class SpeakEngineDialog() : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel: SpeakEngineViewModel by viewModels()
    private val adapter by lazy { Adapter(requireContext()) }
    private var ttsEngine: String? = ReadAloud.ttsEngine
    private val sysTtsViews = arrayListOf<RadioButton>()

    /** 插件引擎行（行视图, 插件）：语音包等运行依赖变化后由 [refreshPluginRows] 重算显示状态。 */
    private val pluginRows = arrayListOf<Pair<RadioButton, ReadAloudEnginePlugin>>()
    private val callBack: CallBack? get() = parentFragment as? CallBack
    private val importDocResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportHttpTtsDialog(uri.toString()))
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onResume() {
        super.onResume()
        // 覆盖"长按进入管理页导入语音包后返回"的场景：按最新就绪状态重算插件行
        refreshPluginRows()
    }

    /**
     * 插件引擎行状态刷新：语音包导入状态变化后重新计算可选性显示——
     * 未就绪置灰、标签附原因、不勾选；就绪后恢复可选。
     */
    private fun refreshPluginRows() {
        pluginRows.forEach { (row, plugin) ->
            val unavailable = plugin.unavailableReason
            row.text = if (unavailable != null) {
                "${plugin.engineLabel}（$unavailable）"
            } else {
                plugin.engineLabel
            }
            row.isChecked = unavailable == null && ttsEngine == plugin.engineId
            row.alpha = if (unavailable == null) 1f else 0.45f
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initMenu()
        initData()
    }

    private fun initView() = binding.run {
        root.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
        toolBar.setBackgroundColor(primaryColor)
        toolBar.setTitle(R.string.speak_engine)
        recyclerView.setEdgeEffectColor(primaryColor)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        if (ReadBook.book?.isAudio == true) {
            adapter.addHeaderView {
                ItemHttpTtsBinding.inflate(layoutInflater, recyclerView, false).apply {
                    root.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
                    sysTtsViews.add(cbName)
                    ivEdit.gone()
                    ivMenuDelete.gone()
                    labelSys.visible()
                    cbName.setText(R.string.source_audio_engine)
                    cbName.tag = ReadAloud.SOURCE_AUDIO_ENGINE_ID
                    cbName.isChecked = ttsEngine == ReadAloud.SOURCE_AUDIO_ENGINE_ID
                    cbName.setOnClickListener {
                        upTts(ReadAloud.SOURCE_AUDIO_ENGINE_ID)
                    }
                }
            }
        }
        // 内置引擎插件（如百度的"本地百度 TTS"）：由各构建的插件注册表提供，
        // 开源构建注册表为空，此处不渲染任何行。
        // 状态规则：运行依赖未就绪（如未导入语音包）时不可选——置灰、标签附原因、
        // 点击拦截提示；长按仍进入插件管理页（这是语音包导入的唯一入口）。
        ReadAloudEngines.all.forEach { plugin ->
            adapter.addHeaderView {
                ItemHttpTtsBinding.inflate(layoutInflater, recyclerView, false).apply {
                    root.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
                    sysTtsViews.add(cbName)
                    pluginRows.add(cbName to plugin)
                    ivEdit.gone()
                    ivMenuDelete.gone()
                    labelSys.visible()
                    cbName.tag = plugin.engineId
                    val unavailable = plugin.unavailableReason
                    cbName.text = if (unavailable != null) {
                        "${plugin.engineLabel}（$unavailable）"
                    } else {
                        plugin.engineLabel
                    }
                    cbName.isChecked = unavailable == null && ttsEngine == plugin.engineId
                    cbName.alpha = if (unavailable == null) 1f else 0.45f
                    cbName.setOnClickListener {
                        val reason = plugin.unavailableReason
                        if (reason == null) {
                            upTts(plugin.engineId)
                        } else {
                            toastOnUi("${plugin.engineLabel}：$reason")
                        }
                    }
                    cbName.setOnLongClickListener {
                        val manageActivity = plugin.manageActivityClass
                            ?: return@setOnLongClickListener false
                        startActivity(android.content.Intent(requireContext(), manageActivity))
                        true
                    }
                }
            }
        }
        adapter.addHeaderView {
            ItemHttpTtsBinding.inflate(layoutInflater, recyclerView, false).apply {
                root.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
                sysTtsViews.add(cbName)
                ivEdit.gone()
                ivMenuDelete.gone()
                labelSys.visible()
                cbName.text = "系统默认"
                cbName.tag = ""
                cbName.isChecked = ttsEngine == null || ttsEngine!!.isJsonObject()
                        && GSON.fromJsonObject<SelectItem<String>>(ttsEngine)
                    .getOrNull()?.value.isNullOrEmpty()
                cbName.setOnClickListener {
                    upTts(GSON.toJson(SelectItem("系统默认", "")))
                }
                // 长按"系统默认"直接跳到系统 TTS 设置界面（与朗读设置里的"系统TTS设置"一致）
                cbName.setOnLongClickListener {
                    IntentHelp.openTTSSetting()
                    true
                }
            }
        }
        viewModel.sysEngines.forEach { engine ->
            adapter.addHeaderView {
                ItemHttpTtsBinding.inflate(layoutInflater, recyclerView, false).apply {
                    root.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
                    sysTtsViews.add(cbName)
                    ivEdit.gone()
                    ivMenuDelete.gone()
                    labelSys.visible()
                    cbName.text = engine.label
                    cbName.tag = engine.name
                    cbName.isChecked = GSON.fromJsonObject<SelectItem<String>>(ttsEngine)
                        .getOrNull()?.value == cbName.tag
                    cbName.setOnClickListener {
                        upTts(GSON.toJson(SelectItem(engine.label, engine.name)))
                    }
                }
            }
        }
        tvFooterLeft.setText(R.string.book)
        tvFooterLeft.typeface = requireContext().uiTypeface()
        tvFooterLeft.visible()
        tvFooterLeft.setOnClickListener {
            ReadBook.book?.setTtsEngine(ttsEngine)
            callBack?.upSpeakEngineSummary()
            ReadAloud.upReadAloudClass()
            dismissAllowingStateLoss()
        }
        tvOk.setText(R.string.general)
        tvOk.typeface = requireContext().uiTypeface()
        tvOk.visible()
        tvOk.setOnClickListener {
            if (ttsEngine == ReadAloud.SOURCE_AUDIO_ENGINE_ID) {
                toastOnUi("书源音频只能应用到当前有声书")
                return@setOnClickListener
            }
            ReadBook.book?.setTtsEngine(null)
            AppConfig.ttsEngine = ttsEngine
            callBack?.upSpeakEngineSummary()
            ReadAloud.upReadAloudClass()
            dismissAllowingStateLoss()
        }
        tvCancel.visible()
        tvCancel.typeface = requireContext().uiTypeface()
        tvCancel.setOnClickListener {
            dismissAllowingStateLoss()
        }
        updateGeneralActionState()
    }

    private fun initMenu() = binding.run {
        toolBar.inflateMenu(R.menu.speak_engine)
        toolBar.menu.applyUiMenuStyle(requireContext())
        toolBar.setOnMenuItemClickListener(this@SpeakEngineDialog)
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.httpTTSDao.flowAll().catch {
                AppLog.put("朗读引擎界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                adapter.setItems(it)
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_clear -> clearCache()
            R.id.menu_add -> showDialogFragment<HttpTtsEditDialog>()
            R.id.menu_default -> viewModel.importDefault()
            R.id.menu_import_local -> importDocResult.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }
            R.id.menu_tts_engine_v2 -> startActivity<TtsEngineManageActivity>()
        }
        return true
    }

    fun clearCache() {
        execute {
            ReadAloud.upReadAloudClass()
            val ttsFolderPath = "${requireContext().cacheDir.absolutePath}${File.separator}httpTTS${File.separator}"
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
                FileUtils.delete(it.absolutePath)
            }
            toastOnUi(R.string.clear_cache_success)
        }
    }

    private fun upTts(tts: String) {
        ttsEngine = tts
        sysTtsViews.forEach {
            val isChecked = when {
                ttsEngine == ReadAloud.SOURCE_AUDIO_ENGINE_ID -> it.tag == ReadAloud.SOURCE_AUDIO_ENGINE_ID
                ReadAloudEngines.byId(ttsEngine) != null -> it.tag == ttsEngine
                else -> GSON.fromJsonObject<SelectItem<String>>(ttsEngine)
                    .getOrNull()?.value == it.tag
            }
            it.isChecked = isChecked
        }
        adapter.notifyItemRangeChanged(adapter.getHeaderCount(), adapter.itemCount)
        updateGeneralActionState()
    }

    private fun updateGeneralActionState() {
        val enabled = ttsEngine != ReadAloud.SOURCE_AUDIO_ENGINE_ID
        binding.tvOk.isEnabled = enabled
        binding.tvOk.alpha = if (enabled) 1f else 0.45f
    }

    inner class Adapter(context: Context) :
        RecyclerAdapter<HttpTTS, ItemHttpTtsBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemHttpTtsBinding {
            return ItemHttpTtsBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemHttpTtsBinding,
            item: HttpTTS,
            payloads: MutableList<Any>
        ) {
            binding.apply {
                cbName.text = item.name
                cbName.typeface = context.uiTypeface()
                val isChecked = item.id.toString() == ttsEngine
                cbName.isChecked = isChecked
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemHttpTtsBinding) {
            binding.run {
                cbName.setOnClickListener {
                    getItemByLayoutPosition(holder.layoutPosition)?.let { httpTTS ->
                        val id = httpTTS.id.toString()
                        upTts(id)
                        if (!httpTTS.loginUrl.isNullOrBlank()
                            && httpTTS.getLoginInfo().isNullOrBlank()
                        ) {
                            startActivity<SourceLoginActivity> {
                                putExtra("type", "httpTts")
                                putExtra("key", id)
                            }
                        }
                    }
                }
                cbName.setOnLongClickListener {
                    getItemByLayoutPosition(holder.layoutPosition)?.let { httpTTS ->
                        if (!httpTTS.loginUrl.isNullOrBlank()) {
                            val id = httpTTS.id.toString()
                            startActivity<SourceLoginActivity> {
                                putExtra("type", "httpTts")
                                putExtra("key", id)
                            }
                            return@setOnLongClickListener true
                        }
                    }
                    false
                }
                ivEdit.setOnClickListener {
                    val id = getItemByLayoutPosition(holder.layoutPosition)!!.id
                    showDialogFragment(HttpTtsEditDialog(id))
                }
                ivMenuDelete.setOnClickListener {
                    getItemByLayoutPosition(holder.layoutPosition)?.let { httpTTS ->
                        alert(R.string.draw) {
                            setMessage(getString(R.string.sure_del) + "\n" + httpTTS.name)
                            noButton()
                            yesButton {
                                appDb.httpTTSDao.delete(httpTTS)
                            }
                        }
                    }
                }
            }
        }

    }

    interface CallBack {
        fun upSpeakEngineSummary()
    }

}
