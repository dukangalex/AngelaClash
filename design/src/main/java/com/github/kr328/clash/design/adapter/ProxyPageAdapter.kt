package com.github.kr328.clash.design.adapter

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.component.ProxyPageFactory
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.component.ProxyViewState
import com.github.kr328.clash.design.model.ProxyPageState
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.ui.Surface
import com.github.kr328.clash.design.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProxyPageAdapter(
    private val surface: Surface,
    private val config: ProxyViewConfig,
    private val adapters: List<ProxyAdapter>,
    private val stateChanged: (Int) -> Unit,
) : RecyclerView.Adapter<ProxyPageFactory.Holder>() {
    private val factory = ProxyPageFactory(config)
    private var parent: RecyclerView? = null

    val states = List(adapters.size) { ProxyPageState() }
    private val allGroupStates = MutableList<List<ProxyViewState>>(adapters.size) { emptyList() }
    private val groupSelectedNames = MutableList(adapters.size) { "" }
    private var searchQuery: String = ""

    fun setSearchQuery(query: String) {
        val normalized = query.trim().lowercase()
        if (searchQuery == normalized) return
        searchQuery = normalized
        adapters.indices.forEach { position ->
            val filtered = filterStates(allGroupStates[position])
            adapters[position].states = filtered
            adapters[position].notifyDataSetChanged()
        }
        requestRedrawVisible()
    }

    private fun filterStates(source: List<ProxyViewState>): List<ProxyViewState> {
        if (searchQuery.isEmpty()) return source
        return source.filter {
            it.proxy.name.lowercase().contains(searchQuery) ||
                it.proxy.title.lowercase().contains(searchQuery) ||
                it.proxy.subtitle.lowercase().contains(searchQuery)
        }
    }

    suspend fun updateAdapter(
        position: Int,
        proxies: List<Proxy>,
        selectable: Boolean,
        parent: ProxyState,
        links: Map<String, ProxyState>
    ) {
        val states = withContext(Dispatchers.Default) {
            proxies.map {
                val link = if (it.isGroup) links[it.name] else null

                ProxyViewState(config, it, parent, link)
            }
        }

        withContext(Dispatchers.Main) {
            allGroupStates[position] = states
            groupSelectedNames[position] = parent.now
            val visibleStates = filterStates(states)
            adapters[position].apply {
                this.selectable = selectable
                this.swapDataSet(this::states, visibleStates, false)
            }

            requestRedrawVisible()
        }
    }

    fun scrollToSelected(position: Int): String? {
        if (position !in adapters.indices) return null
        val selectedName = groupSelectedNames[position]
        val list = adapters[position].states
        val targetIdx = list.indexOfFirst {
            it.isSelected || (selectedName.isNotEmpty() && it.proxy.name == selectedName)
        }
        if (targetIdx < 0) return selectedName.ifBlank { null }
        val visible = parent?.firstVisibleView ?: return selectedName.ifBlank { null }
        val rv = factory.fromRoot(visible).recyclerView
        rv.smoothScrollToPosition(targetIdx)
        return list[targetIdx].proxy.name
    }

    fun requestRedrawVisible() {
        val visible = parent?.firstVisibleView ?: return
        factory.fromRoot(visible).recyclerView.invalidateChildren()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProxyPageFactory.Holder {
        val holder = factory.newInstance()

        val toolbarHeight = config.context.getPixels(R.dimen.toolbar_height)
        val tabHeight = config.context.getPixels(R.dimen.tab_layout_height)

        holder.recyclerView.bindInsets(surface, toolbarHeight + tabHeight)
        holder.recyclerView.addScrolledToBottomObserver { view, bottom ->
            val position = view.position
            val state = states[position]

            if (state.bottom != bottom) {
                state.bottom = bottom

                stateChanged(position)
            }
        }

        return holder
    }

    override fun onBindViewHolder(holder: ProxyPageFactory.Holder, position: Int) {
        val adapter = adapters[position]

        states[position].bottom = false

        holder.recyclerView.apply {
            this.position = position
            this.swapAdapter(adapter, false)
        }
    }

    override fun getItemCount(): Int {
        return adapters.size
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.parent = recyclerView

        recyclerView.isFocusable = false
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.parent = null
    }


    private var RecyclerView.position: Int
        get() {
            return tag as? Int ?: -1
        }
        set(value) {
            tag = value
        }
}