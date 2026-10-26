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
    groupNames: List<String>,
    private val uiStore: UiStore,
) : Design<ProxyDesign.Request>(context) {
    // 换配置时组集合会变，但页面不重建，所以这里是 var。
    private var groupNames: List<String> = groupNames

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
    private var tabMediator: TabLayoutMediator? = null

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
            binding.locateCard.visibility = View.VISIBLE
        } else {
            binding.tabLayoutView.visibility = View.VISIBLE
            binding.pagesView.visibility = View.VISIBLE
            binding.groupListView.visibility = View.GONE
            binding.locateCard.visibility = View.VISIBLE
        }
        updateUrlTestButtonStatus()
    }

    fun locateCurrentNode() {
        if (groupNames.isEmpty()) return
        if (uiStore.proxyStyle == "list") {
            val located = groupListAdapter?.locateExpandedOrFirstGroup() ?: return
            scrollGroupListToNode(located.first, located.second, located.third)
        } else {
            val currentTab = binding.pagesView.currentItem
            val name = adapter.scrollToSelected(currentTab)
            if (!name.isNullOrBlank()) {
                Toast.makeText(context, "已定位: $name", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun scrollGroupListToNode(groupIndex: Int, proxyIndex: Int, proxyName: String) {
        val lm = binding.groupListView.layoutManager as? LinearLayoutManager ?: return
        val d = context.resources.displayMetrics.density
        val columns = config.proxyLine.coerceIn(1, 3)
        val rowIndex = if (proxyIndex >= 0) proxyIndex / columns else 0
        val estimatedCardHeight = when (config.proxySize) {
            2 -> (44 * d).toInt()
            1 -> (56 * d).toInt()
            else -> (66 * d).toInt()
        }
        val headerOffset = (76 * d).toInt()
        val targetOffset = if (proxyIndex >= 0) {
            -(headerOffset + rowIndex * estimatedCardHeight) + (binding.groupListView.height / 3)
        } else {
            (16 * d).toInt()
        }
        binding.groupListView.post {
            lm.scrollToPositionWithOffset(groupIndex, targetOffset.coerceAtMost((24 * d).toInt()))
        }
        if (proxyName.isNotBlank()) {
            Toast.makeText(context, "已定位: $proxyName", Toast.LENGTH_SHORT).show()
        }
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
            binding.locateCard.visibility = View.GONE
        } else {
            // 分页回调只注册一次：换配置时换的是 adapter，不是 ViewPager2 本身。
            binding.pagesView.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    updateUrlTestButtonStatus()
                }

                override fun onPageSelected(position: Int) {
                    groupNames.getOrNull(position)?.let { uiStore.proxyLastGroup = it }
                }
            })

            rebuildPages()

            binding.tabLayoutView.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) = Unit
                override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) = Unit
                override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                    val pos = tab?.position ?: return
                    adapter.scrollToSelected(pos)
                }
            })

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
                onLocate = { groupIndex, proxyIndex, proxyName ->
                    scrollGroupListToNode(groupIndex, proxyIndex, proxyName)
                },
            )
            groupListAdapter = listAdapter

            val toolbarHeight = context.getPixels(R.dimen.toolbar_height)
            binding.groupListView.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = listAdapter
                // 底部给右下角双 FAB 留出位置，最后一行才不会被按钮压住
                bindInsets(
                    surface,
                    toolbarHeight + (8 * context.resources.displayMetrics.density).toInt(),
                    (88 * context.resources.displayMetrics.density).toInt(),
                )
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

    /** 按当前 groupNames 重建分页与 Tab；换配置时复用，避免整个 Activity 重启。 */
    private fun rebuildPages() {
        tabMediator?.detach()
        tabMediator = null
        binding.tabLayoutView.removeAllTabs()

        binding.pagesView.adapter = ProxyPageAdapter(
            surface,
            config,
            List(groupNames.size) { index ->
                ProxyAdapter(config) { name ->
                    requests.trySend(Request.Select(index, name))
                }
            },
        ) {
            if (it == binding.pagesView.currentItem)
                updateUrlTestButtonStatus()
        }

        TabLayoutMediator(binding.tabLayoutView, binding.pagesView) { tab, index ->
            tab.text = groupNames.getOrNull(index)
        }.also {
            it.attach()
            tabMediator = it
        }
    }

    /**
     * 换配置后原地把组集合换掉。
     *
     * @return `true` 表示已经就地更新；`false` 表示结构变化太大（组集合从空变成非空，
     *         或者反过来），这种情况只能由 Activity 重建页面。
     */
    fun reconcileGroups(newNames: List<String>, newMode: TunnelState.Mode?): Boolean {
        menu.updateMode(newMode)

        if (newNames == groupNames) {
            return true
        }

        // 空 ↔ 非空要换一整套可见性和空状态，交给 Activity 重建更稳妥。
        if (groupNames.isEmpty() || newNames.isEmpty()) {
            return false
        }

        groupNames = newNames

        if (uiStore.proxyLastGroup !in newNames) {
            uiStore.proxyLastGroup = newNames.first()
        }

        rebuildPages()
        groupListAdapter?.replaceGroupNames(newNames)

        // 搜索框里的关键字要重新套到新的组集合上
        val query = binding.searchInput.text?.toString().orEmpty()
        if (query.isNotEmpty()) {
            adapter.setSearchQuery(query)
            groupListAdapter?.setSearchQuery(query)
        }

        applyStyleMode()
        return true
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
