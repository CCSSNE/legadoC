package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
import io.legado.app.help.bdtts.BdSpeakerStore
import io.legado.app.help.tts.AiBatchAnalyzeDialog
import io.legado.app.help.tts.AiMultiVoiceConfig
import io.legado.app.help.tts.AiStoryboardCacheDialog
import io.legado.app.help.tts.BookTtsAutomationConfig
import io.legado.app.help.tts.BookTtsCastingCoordinator
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
        refreshSpeakerValues()
        binding.tvNarratorValue.setOnClickListener {
            pickSpeaker(
                PreferKey.aiNarratorSpeakerId,
                AiMultiVoiceConfig.narratorSpeakerId
            )
        }
        binding.tvDialogueMaleValue.setOnClickListener {
            pickSpeaker(
                PreferKey.aiDialogueMaleSpeakerId,
                AiMultiVoiceConfig.dialogueMaleSpeakerId
            )
        }
        binding.tvDialogueFemaleValue.setOnClickListener {
            pickSpeaker(
                PreferKey.aiDialogueFemaleSpeakerId,
                AiMultiVoiceConfig.dialogueFemaleSpeakerId
            )
        }
        binding.tvBatchAnalyze.setOnClickListener {
            AiBatchAnalyzeDialog().show(childFragmentManager, "aiBatchAnalyze")
        }
        binding.tvStoryboardCache.setOnClickListener {
            AiStoryboardCacheDialog().show(childFragmentManager, "aiStoryboardCache")
        }
    }

    private fun refreshSpeakerValues() {
        val speakers = BdSpeakerStore.load()
        fun nameOf(id: String): String =
            speakers.firstOrNull { it.id == id }?.name ?: getString(R.string.ai_speaker_unset)
        binding.tvNarratorValue.text = nameOf(AiMultiVoiceConfig.narratorSpeakerId)
        binding.tvDialogueMaleValue.text = nameOf(AiMultiVoiceConfig.dialogueMaleSpeakerId)
        binding.tvDialogueFemaleValue.text = nameOf(AiMultiVoiceConfig.dialogueFemaleSpeakerId)
    }

    private fun pickSpeaker(prefKey: String, currentId: String) {
        val speakers = BdSpeakerStore.load()
        if (speakers.isEmpty()) {
            toastOnUi(R.string.ai_speaker_empty)
            return
        }
        val names = speakers.map { it.name.ifBlank { it.id } }
        val selectedIndex = speakers.indexOfFirst { it.id == currentId }
        alert(titleResource = R.string.ai_pick_speaker) {
            singleChoiceItems(names.toTypedArray(), selectedIndex) { dialog, which ->
                requireContext().putPrefString(prefKey, speakers[which].id)
                refreshSpeakerValues()
                dialog.dismiss()
            }
            negativeButton(R.string.ai_speaker_unset) { dialog ->
                requireContext().putPrefString(prefKey, "")
                refreshSpeakerValues()
                dialog.dismiss()
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
            val speakers = BdSpeakerStore.load()
            fun speakerName(id: String?): String {
                if (id.isNullOrBlank()) return ""
                return speakers.firstOrNull { it.id == id }?.name ?: ""
            }
            val pendingLabel = getString(R.string.ai_role_state_pending)
            val stableLabel = getString(R.string.ai_role_state_stable)
            val rows: List<RoleRow> = if (rolesTab == 0) {
                dao.getRoles(workKey).map { role ->
                    val bound = dao.getBinding(
                        workKey, BookTtsVoiceBinding.TargetType.CHARACTER, role.roleId
                    )?.speakerId
                    RoleRow(
                        targetType = BookTtsVoiceBinding.TargetType.CHARACTER,
                        targetId = role.roleId,
                        name = role.name,
                        detail = buildString {
                            append(genderLabel(role.gender))
                            val speaker = speakerName(bound)
                            if (speaker.isNotBlank()) append(" · ").append(speaker)
                        },
                        speakerId = bound.orEmpty()
                    )
                }
            } else {
                dao.getCastRoles(workKey).filter { !it.ignored }.map { role ->
                    val bound = dao.getBinding(
                        workKey, BookTtsVoiceBinding.TargetType.CAST_ROLE, role.castRoleId
                    )?.speakerId
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
                            val speaker = speakerName(bound)
                            if (speaker.isNotBlank()) append(" · ").append(speaker)
                        },
                        speakerId = bound.orEmpty()
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
        val detail: String,
        val speakerId: String
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
        val speakers = BdSpeakerStore.load()
        if (speakers.isEmpty()) {
            toastOnUi(R.string.ai_speaker_empty)
            return
        }
        val names = speakers.map { it.name.ifBlank { it.id } }
        val selectedIndex = speakers.indexOfFirst { it.id == row.speakerId }
        alert(titleResource = R.string.ai_role_change_speaker) {
            singleChoiceItems(names.toTypedArray(), selectedIndex) { dialog, which ->
                val workKey = currentWorkKey()
                if (workKey != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        appDb.bookRoleDao.insertBinding(
                            BookTtsVoiceBinding(
                                workKey = workKey,
                                targetType = row.targetType,
                                targetId = row.targetId,
                                speakerId = speakers[which].id,
                                bindingMode = BookTtsVoiceBinding.BindingMode.MANUAL,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        withContext(Dispatchers.Main) { loadRoles() }
                    }
                }
                dialog.dismiss()
            }
            negativeButton(R.string.ai_speaker_unset) { dialog ->
                val workKey = currentWorkKey()
                if (workKey != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        appDb.bookRoleDao.deleteBinding(workKey, row.targetType, row.targetId)
                        withContext(Dispatchers.Main) { loadRoles() }
                    }
                }
                dialog.dismiss()
            }
        }
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
