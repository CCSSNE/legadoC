package io.legado.app.ui.config

import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivityTtsEngineManageBinding
import io.legado.app.databinding.ItemTtsEngineV2Binding
import io.legado.app.help.tts.TtsEngineImportConflictAction
import io.legado.app.help.tts.TtsEngineImportConflictException
import io.legado.app.help.tts.TtsEngineSetting
import io.legado.app.help.tts.TtsEngineStore
import io.legado.app.help.tts.TtsEngineType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.GSON
import io.legado.app.utils.applyTint
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TTS 引擎 V2 管理：数据驱动引擎（SYSTEM/SCRIPT）列表、启用/禁用、选为当前朗读引擎、
 * 导入导出与内置语音包引擎外观展示。选角绑定（引擎,音色）的引擎来源即本页引擎集合。
 */
class TtsEngineManageActivity : BaseActivity<ActivityTtsEngineManageBinding>(),
    Toolbar.OnMenuItemClickListener {

    override val binding by viewBinding(ActivityTtsEngineManageBinding::inflate)

    private val adapter by lazy { Adapter() }

    private val importDocResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> importEngine(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(binding.toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        initRecyclerView()
        initMenu()
        refreshData()
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun initRecyclerView() = binding.run {
        recyclerView.setEdgeEffectColor(primaryColor)
        recyclerView.layoutManager = LinearLayoutManager(this@TtsEngineManageActivity)
        recyclerView.adapter = adapter
    }

    private fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.tts_engine_v2)
        binding.toolBar.menu.applyTint(this)
        binding.toolBar.setOnMenuItemClickListener(this)
    }

    private fun refreshData() {
        lifecycleScope.launch(IO) {
            val engines = TtsEngineStore.engines()
            val voiceCounts = TtsEngineStore.voiceCounts()
            val activeId = TtsEngineStore.activeEngineId()
            val facade = TtsEngineStore.voiceDirectoryEngine()
            withContext(Dispatchers.Main) {
                title = getString(R.string.tts_engine_v2_manage)
                val items = buildList {
                    facade?.let { add(it) }
                    addAll(engines)
                }
                adapter.setItems(items, activeId, voiceCounts)
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_add -> createEngine()
            R.id.menu_import_local -> importDocResult.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }
            R.id.menu_restore_classic -> restoreClassicSelection()
        }
        return true
    }

    private fun createEngine() {
        lifecycleScope.launch(IO) {
            runCatching {
                TtsEngineStore.saveEngine(TtsEngineStore.createCustomScriptEngine())
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    toastOnUi(R.string.tts_engine_v2_create_success)
                }
                refreshData()
            }.onFailure { error ->
                AppLog.put("新建朗读引擎失败\n${error.localizedMessage}", error)
                withContext(Dispatchers.Main) { toastOnUi(error.localizedMessage) }
            }
        }
    }

    private fun importEngine(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                toastOnUi(R.string.tts_engine_v2_import_empty)
                return@launch
            }
            importEngineText(text, TtsEngineImportConflictAction.ASK)
        }
    }

    private fun importEngineText(text: String, action: TtsEngineImportConflictAction) {
        lifecycleScope.launch(IO) {
            runCatching {
                TtsEngineStore.importEngineText(text, action).getOrThrow()
            }.onSuccess { imported ->
                withContext(Dispatchers.Main) {
                    toastOnUi(getString(R.string.tts_engine_v2_import_success, imported.size))
                }
                refreshData()
            }.onFailure { error ->
                if (error is TtsEngineImportConflictException) {
                    val names = error.conflicts.joinToString("\n") {
                        "${it.importedName}（已有：${it.existingName}）"
                    }
                    withContext(Dispatchers.Main) {
                        alert(
                            title = getString(R.string.tts_engine_v2_import_conflict_title),
                            message = getString(R.string.tts_engine_v2_import_conflict_msg, names)
                        ) {
                            positiveButton(R.string.tts_engine_v2_import_overwrite) {
                                importEngineText(text, TtsEngineImportConflictAction.OVERWRITE)
                            }
                            negativeButton(R.string.tts_engine_v2_import_keep_both) {
                                importEngineText(text, TtsEngineImportConflictAction.KEEP_BOTH)
                            }
                        }
                    }
                } else {
                    AppLog.put("导入朗读引擎失败\n${error.localizedMessage}", error)
                    withContext(Dispatchers.Main) { toastOnUi(error.localizedMessage) }
                }
            }
        }
    }

    private fun restoreClassicSelection() {
        lifecycleScope.launch(IO) {
            TtsEngineStore.clearActiveEngine()
            withContext(Dispatchers.Main) {
                toastOnUi(R.string.tts_engine_v2_restore_classic_toast)
            }
            refreshData()
        }
    }

    private fun exportEngine(engine: TtsEngineSetting) {
        lifecycleScope.launch {
            val text = withContext(IO) {
                if (engine.type == TtsEngineType.SCRIPT) {
                    engine.script
                } else {
                    GSON.toJson(engine.forExport())
                }
            }
            withContext(Dispatchers.Main) {
                runCatching { share(text, "${engine.name}.json") }
                    .onSuccess { toastOnUi(R.string.tts_engine_v2_export_success) }
                    .onFailure { sendToClip(text) }
            }
        }
    }

    private fun TtsEngineSetting.forExport(): TtsEngineSetting = copy(
        runtimeSpeed = null,
        runtimeVolume = null,
        runtimePitch = null,
        runtimeVoices = null,
        lastVoiceUpdateTime = 0L
    )

    private fun selectEngine(engine: TtsEngineSetting) {
        if (engine.id == TtsEngineStore.VOICE_DIRECTORY_ID) {
            // 内置语音包引擎外观不作为全局活动引擎：它由经典引擎选择路径管理
            return
        }
        if (!engine.enabled) {
            toastOnUi(R.string.tts_engine_v2_state_disabled)
            return
        }
        TtsEngineStore.selectEngine(engine.id)
        refreshData()
    }

    inner class Adapter :
        RecyclerAdapter<TtsEngineSetting, ItemTtsEngineV2Binding>(this@TtsEngineManageActivity) {

        private var activeId: String = ""
        private var voiceCounts: Map<String, Int> = emptyMap()

        fun setItems(
            items: List<TtsEngineSetting>,
            activeId: String,
            voiceCounts: Map<String, Int>
        ) {
            this.activeId = activeId
            this.voiceCounts = voiceCounts
            setItems(items)
        }

        override fun getViewBinding(parent: ViewGroup): ItemTtsEngineV2Binding {
            return ItemTtsEngineV2Binding.inflate(inflater, parent, false)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemTtsEngineV2Binding) {
            binding.root.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let(::selectEngine)
            }
            binding.root.setOnLongClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { engine ->
                    showItemMenu(engine)
                    true
                } ?: false
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemTtsEngineV2Binding,
            item: TtsEngineSetting,
            payloads: MutableList<Any>
        ) {
            binding.run {
                rbActive.isChecked = item.id == activeId
                tvName.text = item.name
                val state = when {
                    item.id == activeId -> getString(R.string.tts_engine_v2_state_active)
                    item.enabled -> getString(R.string.tts_engine_v2_state_enabled)
                    else -> getString(R.string.tts_engine_v2_state_disabled)
                }
                val typeLabel = when (item.type) {
                    TtsEngineType.SYSTEM -> "SYSTEM"
                    TtsEngineType.SCRIPT -> "SCRIPT"
                }
                val voiceCount = maxOf(
                    item.effectiveVoices().size,
                    voiceCounts[item.id] ?: 0
                )
                val summary = getString(
                    R.string.tts_engine_v2_summary_format,
                    typeLabel, voiceCount, state
                ) + if (item.builtIn) {
                    " · " + getString(R.string.tts_engine_v2_state_builtin)
                } else {
                    ""
                }
                tvSummary.text = summary
                tvNote.visible(item.id == TtsEngineStore.VOICE_DIRECTORY_ID)
            }
        }

        private fun showItemMenu(engine: TtsEngineSetting) {
            if (engine.id == TtsEngineStore.VOICE_DIRECTORY_ID) {
                return
            }
            val options = buildList {
                add(
                    if (engine.enabled) getString(R.string.tts_engine_v2_disable)
                    else getString(R.string.tts_engine_v2_enable)
                )
                if (engine.supportsVoiceFetch()) {
                    add(getString(R.string.tts_engine_v2_fetch_voices))
                }
                add(getString(R.string.export))
                if (TtsEngineStore.isDeletableEngine(engine)) {
                    add(getString(R.string.delete))
                }
            }
            alert(title = engine.name) {
                items(options) { _: DialogInterface, index: Int ->
                    when (options[index]) {
                        getString(R.string.tts_engine_v2_enable),
                        getString(R.string.tts_engine_v2_disable) -> toggleEnabled(engine)
                        getString(R.string.tts_engine_v2_fetch_voices) -> fetchVoices(engine)
                        getString(R.string.export) -> exportEngine(engine)
                        getString(R.string.delete) -> deleteEngine(engine)
                    }
                }
            }
        }

        private fun fetchVoices(engine: TtsEngineSetting) {
            lifecycleScope.launch(IO) {
                toastOnUi(R.string.tts_engine_v2_fetching)
                runCatching {
                    TtsEngineStore.ensureVoiceCatalog(engine.id, forceRefresh = true)
                }.onSuccess {
                    toastOnUi(R.string.tts_engine_v2_fetch_done)
                    refreshData()
                }.onFailure { error ->
                    AppLog.put("获取发音人目录失败\n${error.localizedMessage}", error)
                    toastOnUi(error.localizedMessage)
                }
            }
        }

        private fun toggleEnabled(engine: TtsEngineSetting) {
            lifecycleScope.launch(IO) {
                TtsEngineStore.saveEngine(engine.copy(enabled = !engine.enabled))
                refreshData()
            }
        }

        private fun deleteEngine(engine: TtsEngineSetting) {
            if (!TtsEngineStore.isDeletableEngine(engine)) {
                toastOnUi(R.string.tts_engine_v2_system_no_delete)
                return
            }
            alert(message = getString(R.string.tts_engine_v2_delete_confirm, engine.name)) {
                positiveButton(R.string.sure) {
                    lifecycleScope.launch(IO) {
                        TtsEngineStore.deleteEngine(engine.id)
                        refreshData()
                    }
                }
                negativeButton(R.string.cancel)
            }
        }
    }
}
