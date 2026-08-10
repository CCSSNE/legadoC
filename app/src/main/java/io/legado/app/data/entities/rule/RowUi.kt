package io.legado.app.data.entities.rule

import android.view.View
import io.legado.app.R

data class RowUi(
    val name: String = "",
    val type: String = "text",
    val action: String? = null,
    val chars: Array<String?>? = null,
    val default: String? = null,
    var viewName: String? = null,
    val style: FlexChildStyle? = null
) {

    @Suppress("ConstPropertyName")
    object Type {

        const val text = "text"
        const val password = "password"
        const val button = "button"
        const val toggle = "toggle"
        const val select = "select"

    }

    fun style(): FlexChildStyle {
        return style ?: FlexChildStyle.defaultStyle
    }

    val isChoice: Boolean
        get() = type == Type.select || type == Type.toggle

    val isAction: Boolean
        get() = type == Type.button

    val modernBackgroundRes: Int
        get() = if (isAction) {
            R.drawable.bg_book_info_action_secondary
        } else {
            R.drawable.bg_book_info_subtle_button
        }

    fun applyModernStyle(view: View) {
        view.setBackgroundResource(modernBackgroundRes)
    }

    override fun equals(other: Any?): Boolean {
        if (other is RowUi) {
            return other.name == name
                    && other.type == type
                    && other.action == action
                    && other.default == default
        }
        return false
    }

    override fun hashCode(): Int {
        var result = name.hashCode() + type.hashCode()
        result = 31 * result + (action?.hashCode() ?: 0)
        result = 31 * result + (default?.hashCode() ?: 0)
        return result
    }

}
