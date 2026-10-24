package com.github.kr328.clash.design

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.adapter.ProxyAdapter
import com.github.kr328.clash.design.adapter.ProxyGroupListAdapter
import com.github.kr328.clash.design.adapter.ProxyPageAdapter
import com.github.kr328.clash.design.component.ProxyMenu
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.databinding.DesignProxyBinding
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindInsets
import com.github.kr328.clash.design.util.getPixels
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProxyDesign(
    context: Context,
    overrideMode: TunnelState.Mode?,
    private val groupNames: List<String>,
    private val uiStore: UiStore,
) : Design<ProxyDesign.Request>(context) {
    sealed class Request {
        object ReloadAll : Request()
        object ReLaunch : Request()

        data class PatchMode(val mode: TunnelState.Mode?) : Request()
        data class Reload(val index: Int) : Request()
        data class Select(val index: Int, val name: String) : Request()
        data class UrlTest(val index: Int) : Request()
        object OpenRules : Request()
    }

    private val binding = DesignProxyBinding
        .inflate(context.layoutInflater, context.root, false)

    private var config = ProxyViewConfig(context, uiStore.proxyLine, uiStore.proxySize)

    private val menu: ProxyMenu by lazy {
        ProxyMenu(context, binding.menuView, overrideMode, uiStore, requests) {
            config.proxyLine = uiStore.proxyLine
            config.proxySize = uiStore.proxySize
            applyStyleMode()
            groupListAdapter?.notifyConfigChanged()
        }
    }

    private val adapter: ProxyPageAdapter
        get() = binding.pagesView.adapter!! as ProxyPageAdapter

    private var groupListAdapter: ProxyGroupListAdapter? = null

    private var urlTesting: Boolean
        get() {
            if (groupNames.isEmpty()) return false
            return if (uiStore.proxyStyle == "list") {
                groupListAdapter?.items?.any { it.urlTesting } == true
            } else {
                adapter.states.getOrNull(binding.pagesView.currentItem)?.urlTesting == true
            }
        }
        set(value) {
            if (groupNames.isEmpty()) return
            if (uiStore.proxyStyle == "list") {
                val targetIdx = groupListAdapter?.items?.firstOrNull { it.expanded }?.index ?: 0
                groupListAdapter?.setUrlTesting(targetIdx, value)
            } else {
                adapter.states.getOrNull(binding.pagesView.currentItem)?.urlTesting = value
            }
        }

    override val root: View = binding.root

    fun requestOpenRules() {
        requests.trySend(Request.OpenRules)
    }

    fun openSettingsSheet() {
        menu.showSettingsSheet()
    }

    fun toggleSearch() {
        val visible = binding.searchCard.visibility == View.VISIBLE
        if (visible) {
            closeSearch()
        } else {
            binding.searchCard.visibility = View.VISIBLE
            binding.searchInput.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun clearOrCloseSearch() {
        val text = binding.searchInput.text?.toString().orEmpty()
        if (text.isNotEmpty()) {
            binding.searchInput.setText("")
        } else {
            closeSearch()
        }
    }

    private fun closeSearch() {
        binding.searchInput.setText("")
        binding.searchCard.visibility = View.GONE
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    suspend fun updateGroup(
        position: Int,
        proxies: List<Proxy>,
        selectable: Boolean,
        parent: ProxyState,
        links: Map<String, ProxyState>,
        groupType: String = if (selectable) "Selector" else "URLTest",
    ) {
        adapter.updateAdapter(position, proxies, selectable, parent, links)
        adapter.states[position].urlTesting = false

        withContext(Dispatchers.Main) {
            groupListAdapter?.updateGroup(
                position = position,
                proxies = proxies,
                selectable = selectable,
                groupType = groupType,
                parent = parent,
                links = links,
            )
            updateUrlTestButtonStatus()
        }
    }

    suspend fun requestRedrawVisible() {
        withContext(Dispatchers.Main) {
            adapter.requestRedrawVisible()
            groupListAdapter?.notifyConfigChanged()
        }
    }

    suspend fun showModeSwitchTips() {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, R.string.mode_switch_tips, Toast.LENGTH_LONG).show()
        }
    }

    private fun applyStyleMode() {
        if (groupNames.isEmpty()) return
        val isList = uiStore.proxyStyle == "list"
        if (isList) {
            binding.tabLayoutView.visibility = View.GONE
            binding.pagesView.visibility = View.GONE
            binding.groupListView.visibility = View.VISIBLE
        } else {
            binding.tabLayoutView.visibility = View.VISIBLE
            binding.pagesView.visibility = View.VISIBLE
            binding.groupListView.visibility = View.GONE
        }
        updateUrlTestButtonStatus()
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.menuView.setOnClickListener {
            menu.showSettingsSheet()
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                if (groupNames.isNotEmpty()) {
                    adapter.setSearchQuery(q)
                    groupListAdapter?.setSearchQuery(q)
                }
            }
        })

        if (groupNames.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE

            binding.searchView.visibility = View.GONE
            binding.urlTestView.visibility = View.GONE
            binding.ruleView.visibility = View.GONE
            binding.tabLayoutView.visibility = View.GONE
            binding.elevationView.visibility = View.GONE
            binding.pagesView.visibility = View.GONE
            binding.groupListView.visibility = View.GONE
        } else {
            binding.pagesView.apply {
                adapter = ProxyPageAdapter(
                    surface,
                    config,
                    List(groupNames.size) { index ->
                        ProxyAdapter(config) { name ->
                            requests.trySend(Request.Select(index, name))
                        }
                    }
                ) {
                    if (it == currentItem)
                        updateUrlTestButtonStatus()
                }

                registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageScrollStateChanged(state: Int) {
                        updateUrlTestButtonStatus()
                    }

                    override fun onPageSelected(position: Int) {
                        uiStore.proxyLastGroup = groupNames[position]
                    }
                })
            }

            TabLayoutMediator(binding.tabLayoutView, binding.pagesView) { tab, index ->
                tab.text = groupNames[index]
            }.attach()

            val listAdapter = ProxyGroupListAdapter(
                context = context,
                config = config,
                groupNames = groupNames,
                onSelect = { groupIndex, proxyName ->
                    requests.trySend(Request.Select(groupIndex, proxyName))
                },
                onUrlTest = { groupIndex ->
                    requests.trySend(Request.UrlTest(groupIndex))
                },
            )
            groupListAdapter = listAdapter

            val toolbarHeight = context.getPixels(R.dimen.toolbar_height)
            binding.groupListView.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = listAdapter
                bindInsets(surface, toolbarHeight + (8 * context.resources.displayMetrics.density).toInt())
            }

            val initialPosition = groupNames.indexOf(uiStore.proxyLastGroup)
            if (initialPosition > 0 && initialPosition < listAdapter.items.size) {
                listAdapter.items.forEachIndexed { idx, item ->
                    item.expanded = idx == initialPosition
                }
            }

            binding.pagesView.post {
                if (initialPosition > 0)
                    binding.pagesView.setCurrentItem(initialPosition, false)
            }

            applyStyleMode()
        }
    }

    fun requestUrlTesting() {
        if (groupNames.isEmpty()) return
        urlTesting = true

        val targetIndex = if (uiStore.proxyStyle == "list") {
            groupListAdapter?.items?.firstOrNull { it.expanded }?.index ?: 0
        } else {
            binding.pagesView.currentItem
        }
        requests.trySend(Request.UrlTest(targetIndex))

        updateUrlTestButtonStatus()
    }

    private fun updateUrlTestButtonStatus() {
        if (urlTesting) {
            binding.urlTestView.visibility = View.GONE
            binding.urlTestProgressView.visibility = View.VISIBLE
        } else {
            binding.urlTestView.visibility = View.VISIBLE
            binding.urlTestProgressView.visibility = View.GONE
        }
    }
}
