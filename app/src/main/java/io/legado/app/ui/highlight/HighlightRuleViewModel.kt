package io.legado.app.ui.highlight

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule

/**
 * 高亮规则数据修改
 * 修改数据要copy,直接修改会导致界面不刷新
 */
class HighlightRuleViewModel(application: Application) : BaseViewModel(application) {

    fun update(vararg rule: HighlightRule) {
        execute {
            appDb.highlightRuleDao.update(*rule)
        }
    }

    fun delete(rule: HighlightRule) {
        execute {
            appDb.highlightRuleDao.delete(rule)
        }
    }

    fun toTop(rule: HighlightRule) {
        execute {
            rule.order = appDb.highlightRuleDao.minOrder - 1
            appDb.highlightRuleDao.update(rule)
        }
    }

    fun toBottom(rule: HighlightRule) {
        execute {
            rule.order = appDb.highlightRuleDao.maxOrder + 1
            appDb.highlightRuleDao.update(rule)
        }
    }

    fun upOrder() {
        execute {
            val rules = appDb.highlightRuleDao.all
            for ((index, rule) in rules.withIndex()) {
                rule.order = index + 1
            }
            appDb.highlightRuleDao.update(*rules.toTypedArray())
        }
    }
}
