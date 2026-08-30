package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookRole
import io.legado.app.data.entities.BookTtsCastRole
import io.legado.app.data.entities.BookTtsVoiceBinding
import io.legado.app.databinding.DialogAiMultiVoiceBinding
import io.legado.app.databinding.ItemAiRoleBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.ai.AiStoryboardConfig
import io.legado.app.help.tts.AiBatchAnalyzeDialog
import io.legado.app.help.tts.AiMultiVoiceConfig
import io.legado.app.help.tts.AiStoryboardCacheDialog
import io.legado.app.help.tts.BookTtsAutomationConfig
import io.legado.app.help.tts.BookTtsCastingCoordinator
import io.legado.app.help.tts.ReadAloudTtsRouter
import io.legado.app.help.tts.TtsEngineSetting
import io.legado.app.help.tts.TtsEngineStore
import io.legado.app.help.tts.TtsEngineType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiTabTypeface
import io.legado.app.model.ReadBook
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.putPrefString
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 多角色朗读总界面：
 * 第一页 = 设置（总开关、兜底发音人、按书开关、批量分析、分镜缓存入口）；
 * 第二页 = 本书角色管理（正式角色 / 临时角色，换音色、编辑、转正、删除）。
 */
class AiMultiVoiceDialog : BaseDialogFragment(R.layout.dialog_ai_multi_voice) {

    companion object {
        fun show(manager: FragmentManager) {
            AiMultiVoiceDialog().show(manager, "aiMultiVoiceDialog")
        }
    }

    private val binding: DialogAiMultiVoiceBinding by lazy {
        DialogAiMultiVoiceBinding.bind(requireView())
    }

    private var rolesTab = 0
    private val roleAdapter by lazy { RoleAdapter(requireContext()) }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initTab()
        initSettingsPage()
        initRolesPage()
    }

    private fun initTab() {
        binding.tabLayout.applyUiTabTypeface()
        binding.tabLayoutRoles.applyUiTabTypeface()
        binding.tabLayout.removeAllTabs()
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.ai_multi_voice_tab_settings))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.ai_multi_voice_tab_roles))
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                binding.pageSettings.isVisible = tab.position == 0
                binding.pageRoles.isVisible = tab.position == 1
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    // ===================== 第一页：设置 =====================

    private fun initSettingsPage() {
        binding.switchEnable.isChecked = AiMultiVoiceConfig.enabled
        binding.itemEnable.setOnClickListener {
            binding.switchEnable.toggle()
        }
        binding.switchEnable.setOnCheckedChangeListener { _, checked ->
            AiMultiVoiceConfig.enabled = checked
        }
        val workKey = currentWorkKey()
        if (workKey == null) {
            binding.itemAutoCreateRoles.isEnabled = false
            binding.itemAutoAssignVoices.isEnabled = false
            binding.switchAutoCreateRoles.isEnabled = false
            binding.switchAutoAssignVoices.isEnabled = false
        } else {
            val automation = BookTtsAutomationConfig.get(workKey)
            binding.switchAutoCreateRoles.isChecked = automation.autoCreateRoles
            binding.switchAutoAssignVoices.isChecked = automation.autoAssignVoices
        }
        binding.itemAutoCreateRoles.setOnClickListener {
            val key = currentWorkKey() ?: return@setOnClickListener
            binding.switchAutoCreateRoles.toggle()
            BookTtsAutomationConfig.setAutoCreateRoles(key, binding.switchAutoCreateRoles.isChecked)
        }
        binding.itemAutoAssignVoices.setOnClickListener {
            val key = currentWorkKey() ?: return@setOnClickListener
            binding.switchAutoAssignVoices.toggle()
            BookTtsAutomationConfig.setAutoAssignVoices(key, binding.switchAutoAssignVoices.isChecked)
        }
        binding.switchSplitLongChapters.isChecked = AiStoryboardConfig.splitLongChapters
        binding.switchSplitLongChapters.setOnCheckedChangeListener { _, checked ->
            AiStoryboardConfig.splitLongChapters = checked
        }
        binding.itemSplitLongChapters.setOnClickListener {
            binding.switchSplitLongChapters.toggle()
            AiStoryboardConfig.splitLongChapters = binding.switchSplitLongChapters.isChecked
        }
        refreshMaxChapterChars()
        binding.itemMaxChapterChars.setOnClickListener {
            editMaxChapterChars()
        }
        refreshSpeakerValues()
        binding.tvNarratorValue.setOnClickListener {
            pickPoolSpeaker(SpeakerPoolKind.NARRATOR)
        }
        binding.tvDialogueMaleValue.setOnClickListener {
            pickPoolSpeaker(SpeakerPoolKind.DIALOGUE_MALE)
        }
        binding.tvDialogueFemaleValue.setOnClickListener {
            pickPoolSpeaker(SpeakerPoolKind.DIALOGUE_FEMALE)
        }
        binding.tvBatchAnalyze.setOnClickListener {
            AiBatchAnalyzeDialog().show(childFragmentManager, "aiBatchAnalyze")
        }
        binding.tvStoryboardCache.setOnClickListener {
            AiStoryboardCacheDialog().show(childFragmentManager, "aiStoryboardCache")
        }
    }

    private fun refreshMaxChapterChars() {
        binding.tvMaxChapterCharsValue.text = getString(
            R.string.ai_max_chapter_chars_value, AiStoryboardConfig.maxChapterChars
        )
    }

    private fun editMaxChapterChars() {
        val context = requireContext()
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(AiStoryboardConfig.maxChapterChars.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.ai_max_chapter_chars)
            .setView(input)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                input.text.toString().trim().toIntOrNull()?.let {
                    AiStoryboardConfig.maxChapterChars = it
                    refreshMaxChapterChars()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshSpeakerValues() {
        val bindings = ReadAloudTtsRouter.resolveGlobalBindings(
            multiRoleEngineId = AppConfig.multiRoleTtsEngineId,
            narratorEngineId = AppConfig.defaultNarratorTtsEngineId,
            narratorVoiceId = AppConfig.defaultNarratorTtsVoiceId,
            dialogueMaleVoiceId = AppConfig.defaultDialogueMaleTtsVoiceId,
            dialogueFemaleVoiceId = AppConfig.defaultDialogueFemaleTtsVoiceId,
            engineResolver = TtsEngineStore::engineOrVoiceDirectory
        )
        fun labelOf(pool: ReadAloudTtsRouter.RouteBinding?): String {
            if (pool == null) return getString(R.string.ai_speaker_unset)
            val voiceName = pool.engine.effectiveVoices()
                .firstOrNull { it.id == pool.voiceId }?.name
                ?: pool.voiceId.orEmpty()
            return "${pool.engine.name} · $voiceName"
        }
        binding.tvNarratorValue.text = labelOf(bindings.narrator)
        binding.tvDialogueMaleValue.text = labelOf(bindings.dialogueMale)
        binding.tvDialogueFemaleValue.text = labelOf(bindings.dialogueFemale)
    }

    /** 全局兜底池类别：旁白 / 对白男 / 对白女。 */
    private enum class SpeakerPoolKind(val legacyKey: String) {
        NARRATOR(PreferKey.aiNarratorSpeakerId),
        DIALOGUE_MALE(PreferKey.aiDialogueMaleSpeakerId),
        DIALOGUE_FEMALE(PreferKey.aiDialogueFemaleSpeakerId)
    }

    private fun pickPoolSpeaker(kind: SpeakerPoolKind) {
        pickEngineThenVoice(
            allowClear = true,
            onPicked = { engineId, voiceId ->
                if (engineId == TtsEngineStore.VOICE_DIRECTORY_ID) {
                    // 内置目录：沿用旧偏好键，路由层自动归属内置语音包引擎
                    requireContext().putPrefString(kind.legacyKey, voiceId)
                } else {
                    // 脚本引擎：写 NG 键（引擎, 音色），旧键清空避免双源混淆
                    requireContext().putPrefString(kind.legacyKey, "")
                    when (kind) {
                        SpeakerPoolKind.NARRATOR -> {
                            AppConfig.defaultNarratorTtsEngineId = engineId
                            AppConfig.defaultNarratorTtsVoiceId = voiceId
                        }
                        SpeakerPoolKind.DIALOGUE_MALE -> {
                            AppConfig.multiRoleTtsEngineId = engineId
                            AppConfig.defaultDialogueMaleTtsVoiceId = voiceId
                        }
                        SpeakerPoolKind.DIALOGUE_FEMALE -> {
                            AppConfig.multiRoleTtsEngineId = engineId
                            AppConfig.defaultDialogueFemaleTtsVoiceId = voiceId
                        }
                    }
                }
                refreshSpeakerValues()
            },
            onClear = {
                requireContext().putPrefString(kind.legacyKey, "")
                when (kind) {
                    SpeakerPoolKind.NARRATOR -> {
                        AppConfig.defaultNarratorTtsEngineId = null
                        AppConfig.defaultNarratorTtsVoiceId = null
                    }
                    SpeakerPoolKind.DIALOGUE_MALE ->
                        AppConfig.defaultDialogueMaleTtsVoiceId = null
                    SpeakerPoolKind.DIALOGUE_FEMALE ->
                        AppConfig.defaultDialogueFemaleTtsVoiceId = null
                }
                refreshSpeakerValues()
            }
        )
    }

    /**
     * 两段式选择：先选引擎（内置语音包引擎 + 可用脚本引擎），再选该引擎内的音色。
     * 目录缺失且引擎支持拉取时现场获取发音人目录。
     */
    private fun pickEngineThenVoice(
        allowClear: Boolean = false,
        onPicked: (engineId: String, voiceId: String) -> Unit,
        onClear: (() -> Unit)? = null
    ) {
        val facade = TtsEngineStore.voiceDirectoryEngine()
        val scriptEngines = TtsEngineStore.engines().filter {
            it.enabled && it.type == TtsEngineType.SCRIPT && it.script.isNotBlank()
        }
        val engines = buildList {
            facade?.let { add(it) }
            addAll(scriptEngines)
        }
        if (engines.isEmpty()) {
            toastOnUi(R.string.tts_engine_v2_no_pickable_engine)
            return
        }
        alert(titleResource = R.string.tts_engine_pick_title) {
            items(engines) { dialog, engine, _ ->
                dialog.dismiss()
                pickVoiceInEngine(engine) { voiceId ->
                    onPicked(engine.id, voiceId)
                }
            }
            if (allowClear && onClear != null) {
                neutralButton(R.string.ai_speaker_unset) { onClear() }
            }
            negativeButton(R.string.cancel)
        }
    }

    private fun pickVoiceInEngine(
        engine: TtsEngineSetting,
        onPicked: (voiceId: String) -> Unit
    ) {
        lifecycleScope.launch {
            val voices = withContext(Dispatchers.IO) {
                runCatching {
                    if (engine.effectiveVoices().isEmpty() && engine.supportsVoiceFetch()) {
                        TtsEngineStore.ensureVoiceCatalog(engine.id)
                    } else {
                        engine
                    }
                }.getOrNull()
            }?.effectiveVoices().orEmpty()
            if (voices.isEmpty()) {
                toastOnUi(R.string.tts_engine_v2_fetch_empty)
                return@launch
            }
            val names = voices.map { it.name.ifBlank { it.id } }
            alert(titleResource = R.string.tts_engine_v2_pick_voice) {
                singleChoiceItems(names.toTypedArray(), -1) { dialog, which ->
                    dialog.dismiss()
                    onPicked(voices[which].id)
                }
                negativeButton(R.string.cancel)
            }
        }
    }

    // ===================== 第二页：角色管理 =====================

    private fun initRolesPage() {
        binding.tabLayoutRoles.removeAllTabs()
        binding.tabLayoutRoles.addTab(
            binding.tabLayoutRoles.newTab().setText(R.string.ai_roles_formal)
        )
        binding.tabLayoutRoles.addTab(
            binding.tabLayoutRoles.newTab().setText(R.string.ai_roles_temporary)
        )
        binding.tabLayoutRoles.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                rolesTab = tab.position
                loadRoles()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        binding.recyclerRoles.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerRoles.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerRoles.adapter = roleAdapter
        binding.tvAddRole.setOnClickListener { AiRoleEditDialog.show(childFragmentManager, 0L) }
        loadRoles()
    }

    private fun currentWorkKey(): String? {
        val book = ReadBook.book ?: return null
        return BookTtsAutomationConfig.workKeyOf(book.name, book.author)
    }

    private fun loadRoles() {
        val workKey = currentWorkKey() ?: run {
            roleAdapter.setItems(emptyList())
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = appDb.bookRoleDao
            fun bindingLabel(engineId: String?, speakerId: String?): String {
                if (speakerId.isNullOrBlank()) return ""
                val engine = TtsEngineStore.engineOrVoiceDirectory(engineId)
                    ?: return speakerId
                val voiceName = engine.effectiveVoices()
                    .firstOrNull { it.id == speakerId }?.name
                    ?: speakerId
                return "${engine.name} · $voiceName"
            }
            val pendingLabel = getString(R.string.ai_role_state_pending)
            val stableLabel = getString(R.string.ai_role_state_stable)
            val rows: List<RoleRow> = if (rolesTab == 0) {
                dao.getRoles(workKey).map { role ->
                    val bound = dao.getBinding(
                        workKey, BookTtsVoiceBinding.TargetType.CHARACTER, role.roleId
                    )
                    RoleRow(
                        targetType = BookTtsVoiceBinding.TargetType.CHARACTER,
                        targetId = role.roleId,
                        name = role.name,
                        detail = buildString {
                            append(genderLabel(role.gender))
                            val label = bindingLabel(bound?.engineId, bound?.speakerId)
                            if (label.isNotBlank()) append(" · ").append(label)
                        }
                    )
                }
            } else {
                dao.getCastRoles(workKey).filter { !it.ignored }.map { role ->
                    val bound = dao.getBinding(
                        workKey, BookTtsVoiceBinding.TargetType.CAST_ROLE, role.castRoleId
                    )
                    RoleRow(
                        targetType = BookTtsVoiceBinding.TargetType.CAST_ROLE,
                        targetId = role.castRoleId,
                        name = role.name,
                        detail = buildString {
                            append(genderLabel(role.gender))
                            append(" · ")
                            append(
                                when (role.identityState) {
                                    BookTtsCastRole.IdentityState.PENDING -> pendingLabel
                                    else -> stableLabel
                                }
                            )
                            append(" · ")
                            append(getString(R.string.ai_role_occurrence, role.occurrenceCount))
                            val label = bindingLabel(bound?.engineId, bound?.speakerId)
                            if (label.isNotBlank()) append(" · ").append(label)
                        }
                    )
                }
            }
            withContext(Dispatchers.Main) {
                roleAdapter.setItems(rows)
            }
        }
    }

    private fun genderLabel(gender: String): String = when (gender) {
        BookRole.Gender.MALE -> getString(R.string.ai_role_gender_male)
        BookRole.Gender.FEMALE -> getString(R.string.ai_role_gender_female)
        else -> getString(R.string.ai_role_gender_unknown)
    }

    private data class RoleRow(
        val targetType: String,
        val targetId: Long,
        val name: String,
        val detail: String
    )

    private inner class RoleAdapter(context: android.content.Context) :
        RecyclerAdapter<RoleRow, ItemAiRoleBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemAiRoleBinding {
            return ItemAiRoleBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemAiRoleBinding,
            item: RoleRow,
            payloads: MutableList<Any>
        ) = binding.run {
            tvRoleName.text = item.name
            tvRoleDetail.text = item.detail
            ivRoleAction.setColorFilter(accentColor)
            root.setOnClickListener { showRoleActions(item) }
            ivRoleAction.setOnClickListener { showRoleActions(item) }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemAiRoleBinding) = Unit
    }

    private fun showRoleActions(row: RoleRow) {
        val isFormal = row.targetType == BookTtsVoiceBinding.TargetType.CHARACTER
        val options = mutableListOf<String>()
        options += getString(R.string.ai_role_change_speaker)
        options += if (isFormal) {
            getString(R.string.ai_role_edit)
        } else {
            getString(R.string.ai_role_promote)
        }
        alert(title = row.name) {
            items(options) { dialog, _, which ->
                dialog.dismiss()
                when (which) {
                    0 -> showSpeakerPickerFor(row)
                    1 -> if (isFormal) {
                        AiRoleEditDialog.show(childFragmentManager, row.targetId)
                    } else {
                        promoteCastRole(row.targetId)
                    }
                }
            }
            negativeButton(if (isFormal) R.string.delete else R.string.ai_role_delete) { dialog ->
                dialog.dismiss()
                if (isFormal) {
                    deleteFormalRole(row.targetId)
                } else {
                    deleteCastRole(row.targetId)
                }
            }
        }
    }

    private fun showSpeakerPickerFor(row: RoleRow) {
        pickEngineThenVoice(
            allowClear = true,
            onPicked = { engineId, voiceId ->
                val workKey = currentWorkKey() ?: return@pickEngineThenVoice
                lifecycleScope.launch(Dispatchers.IO) {
                    appDb.bookRoleDao.insertBinding(
                        BookTtsVoiceBinding(
                            workKey = workKey,
                            targetType = row.targetType,
                            targetId = row.targetId,
                            engineId = engineId,
                            speakerId = voiceId,
                            bindingMode = BookTtsVoiceBinding.BindingMode.MANUAL,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    withContext(Dispatchers.Main) { loadRoles() }
                }
            },
            onClear = {
                val workKey = currentWorkKey() ?: return@pickEngineThenVoice
                lifecycleScope.launch(Dispatchers.IO) {
                    appDb.bookRoleDao.deleteBinding(workKey, row.targetType, row.targetId)
                    withContext(Dispatchers.Main) { loadRoles() }
                }
            }
        )
    }

    private fun promoteCastRole(castRoleId: Long) {
        val workKey = currentWorkKey() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = appDb.bookRoleDao
            val castRole = dao.getCastRole(castRoleId) ?: return@launch
            val existing = dao.getRoles(workKey).firstOrNull {
                BookTtsCastingCoordinator.normalizeIdentityName(it.name) ==
                    BookTtsCastingCoordinator.normalizeIdentityName(castRole.name)
            }
            val roleId = existing?.roleId ?: dao.insertRole(
                BookRole(
                    workKey = workKey,
                    name = castRole.name,
                    aliasesJson = castRole.aliasesJson,
                    gender = castRole.gender,
                    enabled = true,
                    updatedAt = System.currentTimeMillis()
                )
            )
            if (existing != null) {
                // 命中已有正式角色：把临时角色的名称与别名并入，避免别名证据链断裂后重建重复角色。
                val mergedAliases = buildList {
                    addAll(parseAliasesJson(existing.aliasesJson))
                    add(castRole.name)
                    addAll(parseAliasesJson(castRole.aliasesJson))
                }.filter {
                    it.isNotBlank() && BookTtsCastingCoordinator.normalizeIdentityName(it) !=
                        BookTtsCastingCoordinator.normalizeIdentityName(existing.name)
                }.distinctBy { BookTtsCastingCoordinator.normalizeIdentityName(it) }
                dao.updateRole(
                    existing.copy(
                        aliasesJson = GSON.toJson(mergedAliases),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            dao.updateCastRole(
                castRole.copy(
                    ignored = false,
                    linkedRoleId = roleId,
                    identityState = BookTtsCastRole.IdentityState.STABLE,
                    updatedAt = System.currentTimeMillis()
                )
            )
            dao.getBinding(workKey, BookTtsVoiceBinding.TargetType.CAST_ROLE, castRoleId)?.let { binding ->
                dao.deleteBinding(workKey, binding.targetType, binding.targetId)
                dao.insertBinding(
                    binding.copy(
                        targetType = BookTtsVoiceBinding.TargetType.CHARACTER,
                        targetId = roleId,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            withContext(Dispatchers.Main) { loadRoles() }
        }
    }

    private fun deleteCastRole(castRoleId: Long) {
        val workKey = currentWorkKey() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = appDb.bookRoleDao
            dao.getCastRole(castRoleId)?.let { dao.deleteCastRole(it) }
            dao.deleteBinding(workKey, BookTtsVoiceBinding.TargetType.CAST_ROLE, castRoleId)
            withContext(Dispatchers.Main) { loadRoles() }
        }
    }

    private fun deleteFormalRole(roleId: Long) {
        val workKey = currentWorkKey() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = appDb.bookRoleDao
            dao.getRole(roleId)?.let { dao.deleteRole(it) }
            dao.deleteBinding(workKey, BookTtsVoiceBinding.TargetType.CHARACTER, roleId)
            // 解除指向该正式角色的临时角色链接，避免留下永远无法自动配音的"幽灵"临时角色。
            dao.getCastRoles(workKey).filter { it.linkedRoleId == roleId }.forEach { castRole ->
                dao.updateCastRole(
                    castRole.copy(linkedRoleId = 0L, updatedAt = System.currentTimeMillis())
                )
            }
            withContext(Dispatchers.Main) { loadRoles() }
        }
    }

    private fun parseAliasesJson(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            GSON.fromJsonArray<String>(json).getOrNull().orEmpty()
        }.getOrDefault(emptyList())
    }
}
