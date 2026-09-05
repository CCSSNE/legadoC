package io.legado.app.help.ai

import android.content.Context
import io.legado.app.R
import io.legado.app.utils.toastOnUi

/**
 * 待插入正文的生成媒体暂存：长按菜单点"插入"攒起来（可多处多选累加），
 * 正文选词开插图弹窗时预填进去，插完清空。只记文件名（creation_results 目录），
 * 文件没了预填时跳过并明说，不静默吞。
 */
object AiCreationInsertStash {

    private val fileNames = linkedSetOf<String>()

    /** 暂存一批，返回实际收下的个数（不存在的文件不收） */
    fun add(names: Collection<String>): Int {
        var added = 0
        names.forEach { name ->
            if (name.isBlank()) return@forEach
            if (AiCreationImageFile.fileOf(name)?.isFile != true) return@forEach
            if (fileNames.add(name)) added++
        }
        return added
    }

    /** 暂存并弹提示：收下就报总数让去选位置，一个没收下就明说 */
    fun stashWithToast(context: Context, names: Collection<String>) {
        val added = add(names)
        context.toastOnUi(
            if (added > 0) context.getString(R.string.ai_creation_stash_hint, fileNames.size)
            else context.getString(R.string.ai_creation_stash_gone)
        )
    }

    fun peek(): List<String> = fileNames.toList()

    fun clear() {
        fileNames.clear()
    }

    val isEmpty: Boolean
        get() = fileNames.isEmpty()
}
