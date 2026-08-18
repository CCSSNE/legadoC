package io.legado.app.utils

import android.content.Context
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.annotation.AnimRes
import androidx.core.view.doOnLayout
import io.legado.app.help.config.AppConfig

fun loadAnimation(context: Context, @AnimRes id: Int): Animation {
    val animation = AnimationUtils.loadAnimation(context, id)
    if (AppConfig.isEInkMode) {
        animation.duration = 0
    }
    return animation
}

/** Runs after the view's first completed draw without mutating the draw listener in onDraw. */
fun View.doAfterFirstDraw(action: () -> Unit) {
    doOnLayout {
        val observer = viewTreeObserver
        val listener = object : ViewTreeObserver.OnDrawListener {
            private var callbackPosted = false

            override fun onDraw() {
                if (callbackPosted) return
                callbackPosted = true
                post {
                    if (observer.isAlive) {
                        observer.removeOnDrawListener(this)
                    }
                    action()
                }
            }
        }
        observer.addOnDrawListener(listener)
        invalidate()
    }
}
