package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookRole
import io.legado.app.databinding.DialogAiRoleEditBinding
import io.legado.app.help.tts.BookTtsAutomationConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.ReadBook
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 正式角色编辑：名字、别名（| 分隔）、性别。
 * roleId=0 表示新增（经 arguments 传递，支持 Fragment 重建）。
 */
class AiRoleEditDialog : BaseDialogFragment(R.layout.dialog_ai_role_edit) {

    companion object {
        private const val KEY_ROLE_ID = "roleId"

        fun show(manager: FragmentManager, roleId: Long) {
            AiRoleEditDialog().apply {
                arguments = Bundle().apply { putLong(KEY_ROLE_ID, roleId) }
            }.show(manager, "aiRoleEditDialog")
        }
    }

    private val roleId: Long get() = arguments?.getLong(KEY_ROLE_ID, 0L) ?: 0L

    private val binding: DialogAiRoleEditBinding by lazy {
        DialogAiRoleEditBinding.bind(requireView())
    }

    private var gender: String = BookRole.Gender.UNKNOWN

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        if (roleId > 0L) {
            lifecycleScope.launch(Dispatchers.IO) {
                val role = appDb.bookRoleDao.getRole(roleId)
                launch(Dispatchers.Main) { fillRole(role) }
            }
        }
        binding.tvGenderValue.setOnClickListener { pickGender() }
        binding.btnConfirm.setOnClickListener { saveRole() }
        binding.btnCancel.setOnClickListener { dismiss() }
    }

    private fun fillRole(role: BookRole?) {
        if (role == null) return
        binding.etName.setText(role.name)
        val aliases = io.legado.app.utils.GSON.fromJsonArray<String>(role.aliasesJson)
            .getOrNull().orEmpty()
        binding.etAliases.setText(aliases.joinToString("|"))
        gender = role.gender
        binding.tvGenderValue.text = genderLabel(gender)
    }

    private fun pickGender() {
        val options = listOf(
            genderLabel(BookRole.Gender.MALE),
            genderLabel(BookRole.Gender.FEMALE),
            genderLabel(BookRole.Gender.UNKNOWN)
        )
        val values = listOf(
            BookRole.Gender.MALE,
            BookRole.Gender.FEMALE,
            BookRole.Gender.UNKNOWN
        )
        val checked = values.indexOf(gender)
        alert(titleResource = R.string.ai_role_gender) {
            singleChoiceItems(options.toTypedArray(), checked) { dialog, which ->
                gender = values[which]
                binding.tvGenderValue.text = options[which]
                dialog.dismiss()
            }
        }
    }

    private fun genderLabel(value: String): String = when (value) {
        BookRole.Gender.MALE -> getString(R.string.ai_role_gender_male)
        BookRole.Gender.FEMALE -> getString(R.string.ai_role_gender_female)
        else -> getString(R.string.ai_role_gender_unknown)
    }

    private fun saveRole() {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            toastOnUi(R.string.ai_role_name_empty)
            return
        }
        val book = ReadBook.book ?: return
        val workKey = BookTtsAutomationConfig.workKeyOf(book.name, book.author)
        val aliases = binding.etAliases.text?.toString()
            ?.split('|')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = appDb.bookRoleDao
            if (roleId > 0L) {
                dao.getRole(roleId)?.let { existing ->
                    dao.updateRole(
                        existing.copy(
                            name = name,
                            aliasesJson = io.legado.app.utils.GSON.toJson(aliases),
                            gender = gender,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            } else {
                dao.insertRole(
                    BookRole(
                        workKey = workKey,
                        name = name,
                        aliasesJson = io.legado.app.utils.GSON.toJson(aliases),
                        gender = gender,
                        enabled = true,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            launch(Dispatchers.Main) { dismiss() }
        }
    }
}
