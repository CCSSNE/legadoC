package io.legado.app.help.rhino

import com.script.rhino.JavaObjectWrapFactory
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.Scriptable

class BookScriptObject(scope: Scriptable?, javaObject: Any, staticType: Class<*>?) :
    NativeJavaObject(scope, javaObject, staticType) {

    override fun has(name: String, start: Scriptable): Boolean {
        if (name == SET_USE_REPLACE_RULE) {
            return false
        }
        return super.has(name, start)
    }

    override fun get(name: String, start: Scriptable): Any? {
        if (name == SET_USE_REPLACE_RULE) {
            return NOT_FOUND
        }
        return super.get(name, start)
    }

    override fun put(name: String, start: Scriptable, value: Any?) {
        if (name == USE_REPLACE_RULE) {
            throw EvaluatorException("book.useReplaceRule is controlled by the reader")
        }
        super.put(name, start, value)
    }

    companion object {
        private const val SET_USE_REPLACE_RULE = "setUseReplaceRule"
        private const val USE_REPLACE_RULE = "useReplaceRule"

        val factory = JavaObjectWrapFactory { scope, javaObject, staticType ->
            BookScriptObject(scope, javaObject, staticType)
        }
    }
}
