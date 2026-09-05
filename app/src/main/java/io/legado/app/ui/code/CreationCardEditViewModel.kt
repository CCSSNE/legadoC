package io.legado.app.ui.code

import android.app.Application
import android.content.Intent
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.CreationCard
import io.legado.app.help.ai.AiCreationCardImages
import io.legado.app.utils.toastOnUi

/**
 * 创作卡片编辑（WebView markdown 编辑器）：
 * 卡片加载/保存/重命名/删除的唯一入口，与代码文本编辑（CodeEditViewModel）完全分离。
 */
class CreationCardEditViewModel(application: Application) : BaseViewModel(application) {

    var initialText = ""
    var title: String? = null
    var creationCard: CreationCard? = null
    var creationCardId = -1L

    /** 从 WebView 拉到的最新内容快照：onPause 缓存 + 界面重建（深浅色切换）后恢复未保存编辑 */
    private var cachedText: String? = null

    fun initData(intent: Intent, success: () -> Unit) {
        execute {
            val cardId = intent.getLongExtra("creationCardId", -1L)
            val card = appDb.creationCardDao.getById(cardId)
                ?: throw Exception("未找到创作卡片")
            creationCard = card
            creationCardId = cardId
            initialText = cachedText ?: card.content
            title = card.name
        }.onSuccess {
            success.invoke()
        }.onError {
            context.toastOnUi("error\n${it.localizedMessage}")
        }
    }

    /** 缓存编辑器最新内容（与 ViewModel 同生命周期，重建后仍可恢复） */
    fun cacheText(text: String) {
        cachedText = text
    }

    fun cachedContent(): String = cachedText ?: initialText

    fun saveCreationCard(
        content: String,
        onBlankDeleted: () -> Unit,
        onSaved: () -> Unit,
        onFailed: () -> Unit = {}
    ) {
        val card = creationCard ?: run {
            onFailed()
            return
        }
        execute {
            if (content.isBlank()) {
                appDb.creationCardDao.delete(card)
                AiCreationCardImages.cleanup(card.content)
                creationCard = null
                creationCardId = -1L
            } else {
                val updated = card.copy(content = content, updateTime = System.currentTimeMillis())
                appDb.creationCardDao.update(updated)
                creationCard = updated
                initialText = content
            }
        }.onSuccess {
            if (creationCard == null) onBlankDeleted() else onSaved()
        }.onError {
            onFailed()
            AppLog.put("创作卡片保存失败", it, true)
            context.toastOnUi("保存失败\n${it.localizedMessage}")
        }
    }

    fun renameCreationCard(name: String, onRenamed: (CreationCard) -> Unit) {
        val card = creationCard ?: return
        execute {
            val updated = card.copy(
                name = name.trim().ifBlank { card.name },
                updateTime = System.currentTimeMillis()
            )
            appDb.creationCardDao.update(updated)
            updated
        }.onSuccess {
            creationCard = it
            title = it.name
            onRenamed(it)
        }.onError {
            AppLog.put("创作卡片重命名失败", it, true)
            context.toastOnUi("重命名失败\n${it.localizedMessage}")
        }
    }

    fun deleteCreationCard(onDeleted: () -> Unit) {
        val card = creationCard ?: return
        execute {
            appDb.creationCardDao.delete(card)
            AiCreationCardImages.cleanup(card.content)
            creationCard = null
            creationCardId = -1L
        }.onSuccess {
            onDeleted()
        }.onError {
            AppLog.put("创作卡片删除失败", it, true)
            context.toastOnUi("删除失败\n${it.localizedMessage}")
        }
    }
}
