package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider
import org.json.JSONObject
import java.io.File
import java.util.*

data class ChainBinding(
    val entry: String,
    val landingProfile: String,
    val landing: String,
)

class ChainStore(context: Context) {
    private val store = Store(
        PreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

    private var raw by store.string(
        key = "chain_bindings",
        defaultValue = ""
    )

    fun get(profile: UUID): ChainBinding? {
        val item = JSONObject(raw.ifBlank { "{}" }).optJSONObject(profile.toString()) ?: return null
        val entry = item.optString("entry").trim()
        val landing = item.optString("landing").trim()
        val landingProfile = item.optString("landingProfile").trim()
        if (entry.isEmpty() || landing.isEmpty() || landingProfile.isEmpty()) return null
        return ChainBinding(entry, landingProfile, landing)
    }

    fun put(profile: UUID, binding: ChainBinding) {
        val root = JSONObject(raw.ifBlank { "{}" })
        root.put(
            profile.toString(),
            JSONObject()
                .put("entry", binding.entry)
                .put("landingProfile", binding.landingProfile)
                .put("landing", binding.landing)
        )
        raw = root.toString()
    }

    fun remove(profile: UUID) {
        val root = JSONObject(raw.ifBlank { "{}" })
        if (root.remove(profile.toString()) != null) raw = root.toString()
    }

    fun removeProfile(profile: UUID) {
        val root = JSONObject(raw.ifBlank { "{}" })
        val id = profile.toString()
        var changed = root.remove(id) != null
        val keys = root.keys().asSequence().toList()
        for (key in keys) {
            val item = root.optJSONObject(key) ?: continue
            if (item.optString("landingProfile") == id) {
                root.remove(key)
                changed = true
            }
        }
        if (changed) raw = root.toString()
    }

    fun all(): Map<UUID, ChainBinding> {
        val root = JSONObject(raw.ifBlank { "{}" })
        val out = LinkedHashMap<UUID, ChainBinding>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val id = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
            val item = root.optJSONObject(key) ?: continue
            val entry = item.optString("entry").trim()
            val landing = item.optString("landing").trim()
            val landingProfile = item.optString("landingProfile").trim()
            if (entry.isEmpty() || landing.isEmpty() || landingProfile.isEmpty()) continue
            out[id] = ChainBinding(entry, landingProfile, landing)
        }
        return out
    }

    fun summary(profile: UUID): String? {
        val binding = get(profile) ?: return null
        return binding.entry + " → " + binding.landing
    }

    companion object {
        fun write(profileDir: File, binding: ChainBinding?) {
            val file = profileDir.resolve("chain.json")
            if (binding == null) {
                file.delete()
                return
            }
            file.writeText(
                JSONObject()
                    .put("entry", binding.entry)
                    .put("landingProfile", binding.landingProfile)
                    .put("landing", binding.landing)
                    .toString()
            )
        }
    }
}
