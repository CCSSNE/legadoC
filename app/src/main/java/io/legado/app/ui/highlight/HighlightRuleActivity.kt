package io.legado.app.ui.highlight

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.databinding.ActivityHighlightRuleBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 高亮规则管理
 */
class HighlightRuleActivity :
    VMBaseActivity<ActivityHighlightRuleBinding, HighlightRuleViewModel>(),
    HighlightRuleAdapter.CallBack {
    override val binding by viewBinding(ActivityHighlightRuleBinding::inflate)
    override val viewModel by viewModels<HighlightRuleViewModel>()
    private val adapter by lazy { HighlightRuleAdapter(this, this) }
    private var dataInit = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        val itemTouchCallback = ItemTouchCallback(adapter)
        itemTouchCallback.isCanDrag = true
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)
        observeHighlightRuleData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.highlight_rule, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    private fun observeHighlightRuleData() {
        lifecycleScope.launch {
            appDb.highlightRuleDao.flowAll()
                .catch {
                    AppLog.put("高亮规则管理界面更新数据出错", it)
                }
                .flowOn(IO).conflate().collect {
                    if (dataInit) {
                        setResult(RESULT_OK)
                    }
                    adapter.setItems(it, adapter.diffItemCallBack)
                    dataInit = true
                }
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add_highlight_rule ->
                showDialogFragment(HighlightRuleEditDialog())
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun update(vararg rule: HighlightRule) {
        viewModel.update(*rule)
    }

    override fun delete(rule: HighlightRule) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + rule.name)
            noButton()
            yesButton {
                viewModel.delete(rule)
            }
        }
    }

    override fun edit(rule: HighlightRule) {
        showDialogFragment(HighlightRuleEditDialog(rule, 0))
    }

    override fun toTop(rule: HighlightRule) {
        viewModel.toTop(rule)
    }

    override fun toBottom(rule: HighlightRule) {
        viewModel.toBottom(rule)
    }

    override fun upOrder() {
        viewModel.upOrder()
    }
}
