package io.legado.app.ui.config

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivityTtsEngineManageBinding
import io.legado.app.databinding.ItemTtsEngineV2Binding
import io.legado.app.help.tts.TtsEngineSetting
import io.legado.app.help.tts.TtsEngineStore
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 脚本引擎详情页。唯一入口是经典朗读引擎选择弹窗中脚本行的长按；
 * 这里不再承担引擎选择、系统 TTS 或内置语音包的重复入口。
 */
class TtsEngineManageActivity : BaseActivity<ActivityTtsEngineManageBinding>() {

    companion object {
        const val EXTRA_SCRIPT_ENGINE_ID = "scriptEngineId"

        fun intent(context: Context, engineId: String): Intent {
            return Intent(context, TtsEngineManageActivity::class.java)
                .putExtra(EXTRA_SCRIPT_ENGINE_ID, engineId)
        }
    }

    override val binding by viewBinding(ActivityTtsEngineManageBinding::inflate)

    private val engineId: String by lazy {
        intent.getStringExtra(EXTRA_SCRIPT_ENGINE_ID)
            ?.takeIf { it.isNotBlank() }
            ?: error("脚本引擎详情页缺少引擎 id")
    }
    private val adapter by lazy { Adapter() }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        setSupportActionBar(binding.toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        initRecyclerView()
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

    private fun refreshData() {
        lifecycleScope.launch(IO) {
            val engine = TtsEngineStore.scriptEngineById(engineId)
            val voiceCounts = TtsEngineStore.voiceCounts()
            withContext(Dispatchers.Main) {
                if (engine == null) {
                    val message = "脚本引擎详情无法打开：引擎「$engineId」不存在"
                    AppLog.put(message)
                    toastOnUi(message)
                    finish()
                    return@withContext
                }
                title = getString(R.string.tts_engine_script_detail)
                adapter.setItems(listOf(engine), voiceCounts)
            }
        }
    }

    private fun exportEngine(engine: TtsEngineSetting) {
        lifecycleScope.launch {
            val text = withContext(IO) { engine.script }
            runCatching { share(text, "${engine.name}.js") }
                .onSuccess { toastOnUi(R.string.tts_engine_v2_export_success) }
                .onFailure { sendToClip(text) }
        }
    }

    inner class Adapter :
        RecyclerAdapter<TtsEngineSetting, ItemTtsEngineV2Binding>(this@TtsEngineManageActivity) {

        private var voiceCounts: Map<String, Int> = emptyMap()

        fun setItems(items: List<TtsEngineSetting>, voiceCounts: Map<String, Int>) {
            this.voiceCounts = voiceCounts
            setItems(items)
        }

        override fun getViewBinding(parent: ViewGroup): ItemTtsEngineV2Binding {
            return ItemTtsEngineV2Binding.inflate(inflater, parent, false)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemTtsEngineV2Binding) {
            binding.root.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let(::showItemMenu)
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
                tvName.text = item.name
                val state = if (item.enabled) {
                    getString(R.string.tts_engine_v2_state_enabled)
                } else {
                    getString(R.string.tts_engine_v2_state_disabled)
                }
                val voiceCount = maxOf(
                    item.effectiveVoices().size,
                    voiceCounts[item.id] ?: 0
                )
                tvSummary.text = getString(
                    R.string.tts_engine_v2_summary_format,
                    "V2", voiceCount, state
                )
            }
        }

        private fun showItemMenu(engine: TtsEngineSetting) {
            val options = buildList {
                add(
                    if (engine.enabled) getString(R.string.tts_engine_v2_disable)
                    else getString(R.string.tts_engine_v2_enable)
                )
                if (engine.supportsVoiceFetch()) {
                    add(getString(R.string.tts_engine_v2_fetch_voices))
                }
                add(getString(R.string.export))
                add(getString(R.string.delete))
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
            alert(message = getString(R.string.tts_engine_v2_delete_confirm, engine.name)) {
                positiveButton(R.string.sure) {
                    lifecycleScope.launch(IO) {
                        val deleted = TtsEngineStore.deleteEngine(engine.id)
                        withContext(Dispatchers.Main) {
                            if (deleted) finish()
                        }
                    }
                }
                negativeButton(R.string.cancel)
            }
        }
    }
}
