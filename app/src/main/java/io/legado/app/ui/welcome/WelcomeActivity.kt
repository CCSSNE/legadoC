package io.legado.app.ui.welcome

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.postDelayed
import io.legado.app.base.BaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityWelcomeBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.CenterCropBitmapDrawable
import io.legado.app.utils.FileUtils
import io.legado.app.utils.fullScreen
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding

open class WelcomeActivity : BaseActivity<ActivityWelcomeBinding>() {

    override val binding by viewBinding(ActivityWelcomeBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0) {
            // 避免从桌面启动程序后，会重新实例化入口类的activity
            finish()
        } else {
            val welcomeShowTime = getPrefInt(PreferKey.welcomeShowTime, 500)
            if (welcomeShowTime == 0) {
                startMainActivity()
            } else {
                binding.root.postDelayed(welcomeShowTime.toLong()) { startMainActivity() }
            }
        }
        binding.tvLegado.visibility = View.GONE
        binding.ivBook.visibility = View.GONE
        binding.tvGzh.visibility = View.GONE
    }

    override fun setupSystemBar() {
        fullScreen()
        setStatusBarColorAuto(backgroundColor, true, fullScreen)
        upNavigationBarColor()
    }

    override fun upBackgroundImage() {
        val imagePath = when (ThemeConfig.getTheme()) {
            Theme.Dark -> AppConfig.welcomeImageDark
            Theme.Light -> AppConfig.welcomeImage
            else -> null
        }?.takeIf { path ->
            path.isNotBlank()
                && getPrefBoolean(PreferKey.customWelcome)
                && FileUtils.exist(path)
        } ?: return
        val drawable = try {
            if (imagePath.endsWith(".9.png")) {
                BitmapUtils.decodeNinePatchDrawable(imagePath)
            } else {
                val metrics = resources.displayMetrics
                BitmapUtils.decodeBitmap(imagePath, metrics.widthPixels, metrics.heightPixels)
                    ?.let { CenterCropBitmapDrawable(resources, it) }
            }
        } catch (_: OutOfMemoryError) {
            null
        } ?: return
        ViewCompat.setBackgroundTintList(window.decorView, null)
        window.decorView.background = drawable
    }

    private fun startMainActivity() {
        startActivity<MainActivity>()
        if (getPrefBoolean(PreferKey.defaultToRead) && appDb.bookDao.lastReadBook != null) {
            startActivity<ReadBookActivity>()
        }
        finish()
    }

}

class Launcher1 : WelcomeActivity()
class Launcher2 : WelcomeActivity()
class Launcher3 : WelcomeActivity()
class Launcher4 : WelcomeActivity()
class Launcher5 : WelcomeActivity()
class Launcher6 : WelcomeActivity()
class Launcher7 : WelcomeActivity()
class Launcher8 : WelcomeActivity()
