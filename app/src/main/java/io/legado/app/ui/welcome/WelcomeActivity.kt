package io.legado.app.ui.welcome

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.postDelayed
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt

open class WelcomeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { true }

        if (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0) {
            finish()
            return
        }
        window.decorView.postDelayed(getPrefInt(PreferKey.welcomeShowTime, 500).toLong()) {
            startMainActivity()
        }
    }

    private fun startMainActivity() {
        val mainIntent = Intent(this, MainActivity::class.java)
        if (getPrefBoolean(PreferKey.defaultToRead) && appDb.bookDao.lastReadBook != null) {
            startActivities(
                arrayOf(
                    mainIntent,
                    Intent(this, ReadBookActivity::class.java)
                )
            )
        } else {
            startActivity(mainIntent)
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
