package com.github.kr328.clash

import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.ChainDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.remote.FilesClient
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ChainBinding
import com.github.kr328.clash.service.store.ChainStore
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChainActivity : BaseActivity<ChainDesign>() {
    private lateinit var store: ChainStore
    private lateinit var chainDesign: ChainDesign
    private var profiles: List<Profile> = emptyList()
    private var active: Profile? = null
    private var selected: Profile? = null
    private var entry: String? = null
    private var landingProfile: UUID? = null
    private var landing: String? = null

    override suspend fun main() {
        store = ChainStore(this)
        chainDesign = ChainDesign(this)
        reload()
        chainDesign.render(snapshot())
        setContentDesign(chainDesign)
        warmUpOutlinesAsync()

        while (isActive) {
            select<Unit> {
                chainDesign.requests.onReceive {
                    when (it) {
                        ChainDesign.Request.PickProfile -> pickProfile()
                        ChainDesign.Request.PickEntry -> pickEntry()
                        ChainDesign.Request.PickLanding -> pickLanding()
                        ChainDesign.Request.Save -> save()
                        ChainDesign.Request.Clear -> clear()
                        ChainDesign.Request.Help -> help()
                        ChainDesign.Request.Others -> others()
                    }
                }
            }
        }
    }

    private fun warmUpOutlinesAsync() {
        val snapshotProfiles = profiles
        launch(Dispatchers.IO) {
            for (profile in snapshotProfiles) {
                runCatching { getOrLoadOutline(profile) }
            }
        }
    }

    private suspend fun reload() {
        profiles = withProfile { queryAll() }.filter { it.imported }
        active = profiles.firstOrNull { it.active } ?: withProfile { queryActive() }?.takeIf { it.imported }
        if (selected == null || profiles.none { it.uuid == selected?.uuid }) {
            selected = active ?: profiles.firstOrNull()
        } else {
            selected = profiles.firstOrNull { it.uuid == selected?.uuid }
        }
        applyStored()
    }

    private fun applyStored() {
        val current = selected
        val binding = current?.let { store.get(it.uuid) }
        entry = binding?.entry
        landing = binding?.landing
        landingProfile = binding?.landingProfile?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: current?.uuid
    }

    private fun snapshot(): ChainDesign.State {
        val current = selected
        val others = otherLines()
        return ChainDesign.State(
            profileName = current?.name,
            entry = entry,
            landing = landing,
            landingLabel = landingLabel(),
            bound = current?.let { store.get(it.uuid) } != null,
            otherCount = others.size,
        )
    }

    private fun landingLabel(): String? {
        val name = landing ?: return null
        val owner = profiles.firstOrNull { it.uuid == landingProfile }
        val current = selected
        if (owner != null && current != null && owner.uuid != current.uuid) {
            return owner.name + " / " + name
        }
        return name
    }

    private fun otherLines(): List<String> {
        val current = selected?.uuid
        return store.all().mapNotNull { (id, binding) ->
            if (id == current) return@mapNotNull null
            val name = profiles.firstOrNull { it.uuid == id }?.name ?: return@mapNotNull null
            val landingOwner = profiles.firstOrNull { it.uuid.toString() == binding.landingProfile }?.name
            val landingText = if (landingOwner != null && binding.landingProfile != id.toString()) {
                "$landingOwner / ${binding.landing}"
            } else {
                binding.landing
            }
            "$name：${binding.entry} → $landingText"
        }
    }

    private suspend fun save() {
        val current = selected
        val landingId = landingProfile
        val entryName = entry
        val landingName = landing
        if (current == null || landingId == null) {
            chainDesign.showToast(R.string.chain_no_profile, ToastDuration.Short)
            return
        }
        if (entryName.isNullOrBlank() || landingName.isNullOrBlank()) {
            chainDesign.showToast(R.string.chain_need_both, ToastDuration.Short)
            return
        }
        if (current.uuid == landingId && entryName == landingName) {
            chainDesign.showToast(R.string.chain_same, ToastDuration.Short)
            return
        }
        store.put(current.uuid, ChainBinding(entryName, landingId.toString(), landingName))
        if (active?.uuid == current.uuid) sendProfileChanged(current.uuid)
        chainDesign.render(snapshot())
        chainDesign.showToast(R.string.chain_saved, ToastDuration.Short)
    }

    private suspend fun clear() {
        val current = selected ?: return
        store.remove(current.uuid)
        entry = null
        landing = null
        landingProfile = current.uuid
        if (active?.uuid == current.uuid) sendProfileChanged(current.uuid)
        chainDesign.render(snapshot())
        chainDesign.showToast(R.string.chain_cleared, ToastDuration.Short)
    }

    private suspend fun pickProfile() {
        if (profiles.isEmpty()) {
            chainDesign.showToast(R.string.chain_no_profile, ToastDuration.Short)
            return
        }
        val names = profiles.map { it.name }.toTypedArray()
        val which = pickIndex(getString(R.string.chain_pick_profile), names) ?: return
        selected = profiles[which]
        applyStored()
        chainDesign.render(snapshot())
    }

    private suspend fun pickEntry() {
        val current = selected
        if (current == null) {
            chainDesign.showToast(R.string.chain_no_profile, ToastDuration.Short)
            return
        }
        val hops = try {
            loadHops(current)
        } catch (e: Exception) {
            chainDesign.showToast(e.message ?: getString(R.string.chain_no_hops), ToastDuration.Long)
            return
        }
        val picked = pickHop(getString(R.string.chain_pick_entry), hops, grouped = false) ?: return
        entry = picked.name
        if (landingProfile == current.uuid && landing == entry) landing = null
        chainDesign.render(snapshot())
    }

    private suspend fun pickLanding() {
        val current = selected
        if (current == null) {
            chainDesign.showToast(R.string.chain_no_profile, ToastDuration.Short)
            return
        }
        val hops = ArrayList<Hop>()
        val ordered = listOf(current) + profiles.filter { it.uuid != current.uuid }
        val results = coroutineScope {
            ordered.map { profile ->
                async {
                    runCatching { loadHops(profile) }
                }
            }.awaitAll()
        }
        var warned = false
        for (result in results) {
            val found = result.getOrElse { e ->
                if (!warned) {
                    chainDesign.showToast(e.message ?: getString(R.string.chain_no_hops), ToastDuration.Long)
                    warned = true
                }
                continue
            }
            found.forEach { hop ->
                if (hop.profileId == current.uuid && hop.name == entry) return@forEach
                hops.add(hop)
            }
        }
        val picked = pickHop(getString(R.string.chain_pick_landing), hops, grouped = true) ?: return
        landing = picked.name
        landingProfile = picked.profileId
        chainDesign.render(snapshot())
    }

    private suspend fun loadHops(profile: Profile): List<Hop> {
        val outline = withContext(Dispatchers.IO) { getOrLoadOutline(profile) }
        if (outline.error.isNotBlank()) error(outline.error)
        val groupLabel = getString(R.string.chain_group)
        val providerLabel = getString(R.string.chain_provider)
        val nodeLabel = getString(R.string.chain_node)
        val hops = ArrayList<Hop>(outline.groups.size + outline.providers.size + outline.proxies.size)
        outline.groups.forEach { hops.add(Hop(profile.uuid, profile.name, it, groupLabel)) }
        outline.providers.forEach { hops.add(Hop(profile.uuid, profile.name, it, providerLabel)) }
        outline.proxies.forEach { hops.add(Hop(profile.uuid, profile.name, it, nodeLabel)) }
        return hops
    }

    private fun getOrLoadOutline(profile: Profile): Outline {
        val directDir = filesDir.resolve("imported/${profile.uuid}")
        val directConfig = directDir.resolve("config.yaml")
        val stamp = if (directConfig.isFile) {
            directConfig.lastModified() xor (directConfig.length() shl 16)
        } else {
            profile.updatedAt
        }
        val cached = outlineCache[profile.uuid]
        if (cached != null && cached.first == stamp && cached.second.error.isBlank()) {
            return cached.second
        }
        val loaded = readOutline(profile, directDir.takeIf { directConfig.isFile && directConfig.canRead() })
        if (loaded.error.isBlank()) {
            outlineCache[profile.uuid] = stamp to loaded
        }
        return loaded
    }

    private fun readOutline(profile: Profile, directDir: File?): Outline {
        return try {
            val targetDir = if (directDir != null) {
                directDir
            } else {
                val dir = File(cacheDir, "chain-outline/${profile.uuid}").apply { mkdirs() }
                val target = File(dir, "config.yaml")
                val uri = FilesClient(this).buildDocumentUri("/${profile.uuid}/config.yaml")
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                } ?: return Outline(emptyList(), emptyList(), emptyList(), getString(R.string.chain_no_hops))
                dir
            }
            val raw = synchronized(outlineLock) {
                try {
                    Clash.setAgeSecretKey(profile.ageSecretKey?.takeIf { it.isNotBlank() })
                    Clash.queryProfileOutline(targetDir)
                } finally {
                    Clash.setAgeSecretKey(null)
                }
            }
            parseOutline(raw)
        } catch (e: Exception) {
            Outline(emptyList(), emptyList(), emptyList(), e.message ?: getString(R.string.chain_no_hops))
        }
    }

    private suspend fun help() {
        suspendCancellableCoroutine { cont ->
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.chain_help_title)
                .setMessage(R.string.chain_help_body)
                .setPositiveButton(R.string.chain_got_it) { _, _ ->
                    if (cont.isActive) cont.resume(Unit)
                }
                .setOnCancelListener {
                    if (cont.isActive) cont.resume(Unit)
                }
                .create()
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
    }

    private suspend fun others() {
        val lines = otherLines()
        if (lines.isEmpty()) return
        val message = getString(R.string.chain_others_body) + "\n\n" + lines.joinToString("\n")
        suspendCancellableCoroutine { cont ->
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.chain_others_title)
                .setMessage(message)
                .setPositiveButton(R.string.chain_got_it) { _, _ ->
                    if (cont.isActive) cont.resume(Unit)
                }
                .setOnCancelListener {
                    if (cont.isActive) cont.resume(Unit)
                }
                .create()
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
    }

    private suspend fun pickIndex(title: String, items: Array<String>): Int? {
        return suspendCancellableCoroutine { cont ->
            val dialog = AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(items) { _, which ->
                    if (cont.isActive) cont.resume(which)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    if (cont.isActive) cont.resume(null)
                }
                .setOnCancelListener {
                    if (cont.isActive) cont.resume(null)
                }
                .create()
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
    }

    private suspend fun pickHop(title: String, hops: List<Hop>, grouped: Boolean): Hop? {
        if (hops.isEmpty()) {
            chainDesign.showToast(R.string.chain_no_hops, ToastDuration.Short)
            return null
        }
        return suspendCancellableCoroutine { cont ->
            val density = resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), 0)
            }
            val search = EditText(this).apply {
                hint = getString(R.string.chain_search)
                setSingleLine()
            }
            val list = ListView(this)
            val adapter = HopAdapter(primaryColor(), textColor(), dp(12))
            list.adapter = adapter
            list.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(360),
            )
            fun refresh(query: String) {
                adapter.submit(rowsFor(hops, query, grouped))
            }
            refresh("")
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = refresh(s?.toString().orEmpty())
            })
            root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(list)
            val dialog = AlertDialog.Builder(this)
                .setTitle(title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    if (cont.isActive) cont.resume(null)
                }
                .setOnCancelListener {
                    if (cont.isActive) cont.resume(null)
                }
                .create()
            list.setOnItemClickListener { _, _, position, _ ->
                val row = adapter.getItem(position)
                if (row is PickRow.Item) {
                    dialog.dismiss()
                    if (cont.isActive) cont.resume(row.hop)
                }
            }
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
    }

    private fun rowsFor(hops: List<Hop>, query: String, grouped: Boolean): List<PickRow> {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) hops else hops.filter {
            it.name.lowercase().contains(q) ||
                it.profileName.lowercase().contains(q) ||
                it.kind.lowercase().contains(q)
        }
        if (filtered.isEmpty()) return listOf(PickRow.Empty(getString(R.string.chain_no_hops)))
        if (!grouped) return filtered.map { PickRow.Item(it) }
        val byProfile = filtered.groupBy { it.profileId }
        val order = hops.map { it.profileId to it.profileName }.distinctBy { it.first }
        val rows = ArrayList<PickRow>()
        for ((id, name) in order) {
            val items = byProfile[id].orEmpty()
            if (items.isEmpty()) continue
            rows.add(PickRow.Header(name))
            items.forEach { rows.add(PickRow.Item(it)) }
        }
        return rows
    }

    private fun primaryColor(): Int {
        val typed = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typed, true)
        return if (typed.resourceId != 0) getColor(typed.resourceId) else typed.data
    }

    private fun textColor(): Int {
        val typed = TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, typed, true)
        return if (typed.resourceId != 0) getColor(typed.resourceId) else typed.data
    }

    companion object {
        private val outlineLock = Any()
        private val outlineCache = ConcurrentHashMap<UUID, Pair<Long, Outline>>()
    }
}

private data class Hop(
    val profileId: UUID,
    val profileName: String,
    val name: String,
    val kind: String,
)

private data class Outline(
    val groups: List<String>,
    val proxies: List<String>,
    val providers: List<String>,
    val error: String,
)

private sealed class PickRow {
    data class Header(val title: String) : PickRow()
    data class Item(val hop: Hop) : PickRow()
    data class Empty(val title: String) : PickRow()
}

private fun parseOutline(raw: String): Outline {
    val json = JSONObject(raw)
    return Outline(
        groups = json.optJSONArray("groups").toStrings(),
        proxies = json.optJSONArray("proxies").toStrings(),
        providers = json.optJSONArray("providers").toStrings(),
        error = json.optString("error"),
    )
}

private fun org.json.JSONArray?.toStrings(): List<String> {
    if (this == null) return emptyList()
    return List(length()) { optString(it) }.filter { it.isNotBlank() }
}

private class HopAdapter(
    private val headerColor: Int,
    private val textColor: Int,
    private val pad: Int,
) : BaseAdapter() {
    private var rows: List<PickRow> = emptyList()

    fun submit(next: List<PickRow>) {
        rows = next
        notifyDataSetChanged()
    }

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): PickRow = rows[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = 2

    override fun getItemViewType(position: Int): Int {
        return if (rows[position] is PickRow.Item) 1 else 0
    }

    override fun isEnabled(position: Int): Boolean = rows[position] is PickRow.Item

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = rows[position]
        val text = (convertView as? TextView) ?: TextView(parent.context).apply {
            setPadding(pad, pad, pad, pad)
            textSize = 16f
        }
        when (row) {
            is PickRow.Header -> {
                text.text = row.title
                text.setTextColor(headerColor)
                text.setTypeface(null, android.graphics.Typeface.BOLD)
            }
            is PickRow.Item -> {
                text.text = row.hop.name + " · " + row.hop.kind
                text.setTextColor(textColor)
                text.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
            is PickRow.Empty -> {
                text.text = row.title
                text.setTextColor(headerColor)
                text.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
        return text
    }
}
