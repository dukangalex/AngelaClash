package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignChainBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class ChainDesign(context: Context) : Design<ChainDesign.Request>(context) {
    enum class Request {
        PickProfile,
        PickEntry,
        PickLanding,
        Save,
        Clear,
        Help,
        Others,
    }

    private val binding = DesignChainBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    fun render(state: State) {
        val unsetEntry = context.getString(R.string.chain_pick_entry)
        val unsetLanding = context.getString(R.string.chain_pick_landing)
        binding.profileName = state.profileName ?: context.getString(R.string.chain_not_set)
        binding.entryName = state.entry ?: unsetEntry
        binding.landingName = state.landingLabel ?: unsetLanding
        binding.pathText = context.getString(
            R.string.chain_path,
            state.entry ?: context.getString(R.string.chain_entry),
            state.landingLabel ?: context.getString(R.string.chain_landing),
        )
        binding.statusText = context.getString(if (state.bound) R.string.chain_bound else R.string.chain_not_set)
        binding.saveHint = context.getString(R.string.chain_save_hint, state.profileName ?: "")
        binding.canSave = !state.entry.isNullOrBlank() && !state.landing.isNullOrBlank()
        binding.bound = state.bound
        binding.hasOthers = state.otherCount > 0
        binding.othersText = context.getString(R.string.chain_others, state.otherCount)
    }

    fun request(request: Request) {
        requests.trySend(request)
    }

    init {
        binding.self = this
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
        render(State(null, null, null, null, false, 0))
    }

    data class State(
        val profileName: String?,
        val entry: String?,
        val landing: String?,
        val landingLabel: String?,
        val bound: Boolean,
        val otherCount: Int,
    )
}
