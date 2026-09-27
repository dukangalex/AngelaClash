package com.github.kr328.clash.service.store

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Bettbox-compatible script display options, plus Angela Clash groups for
 * leak protection, China direct, and strict routing.
 *
 * The script text only declares the switches. Applied behavior lives in the
 * core patcher and the VPN builder, keyed by the option name.
 */
class ScriptDisplayStore(private val context: Context) {
    data class Row(val name: String, val on: Boolean, val summary: String?)
    data class Section(val group: String, val rows: List<Row>)
    data class Snapshot(val enabled: Boolean, val sections: List<Section>, val compatible: String)
    data class VpnHints(
        val captureAll: Boolean,
        val blockBypass: Boolean,
        val blockIpv6: Boolean,
        val hijackDns: Boolean,
    )

    private val dir: File
        get() = context.filesDir.resolve("clash").apply { mkdirs() }
    private val optionFile: File
        get() = dir.resolve(OPTIONS)
    private val scriptFile: File
        get() = dir.resolve(SCRIPT)

    fun ensureScript() {
        if (!scriptFile.exists()) {
            writeAtomic(scriptFile, defaultScript())
        }
    }

    fun scriptText(): String {
        ensureScript()
        return scriptFile.readText()
    }

    fun writeScript(text: String) {
        writeAtomic(scriptFile, text)
        val enabled = readPersisted().enabled
        writePersisted(enabled, effectiveValues())
    }

    fun restoreDefault() {
        writeAtomic(scriptFile, defaultScript())
        val enabled = readPersisted().enabled
        writePersisted(enabled, linkedMapOf())
        writePersisted(enabled, effectiveValues())
    }

    fun snapshot(): Snapshot {
        ensureScript()
        val script = scriptFile.readText()
        val declared = parse(script)
        val saved = readPersisted()
        val sections = listOf(
            section(GROUP_RULE, declared.rule, saved.values),
            section(GROUP_LEAK, declared.leak, saved.values),
            section(GROUP_CN, declared.cn, saved.values),
            section(GROUP_STRICT, declared.strict, saved.values),
        )
        val compatible = when {
            declared.angela -> "angela"
            declared.bettbox -> "bettbox"
            else -> ""
        }
        val values = LinkedHashMap<String, Boolean>()
        for (section in sections) {
            for (row in section.rows) values[key(section.group, row.name)] = row.on
        }
        writePersisted(saved.enabled, values)
        return Snapshot(saved.enabled, sections, compatible)
    }

    fun setEnabled(enabled: Boolean) {
        writePersisted(enabled, effectiveValues())
    }

    fun setOption(group: String, name: String, on: Boolean) {
        val values = effectiveValues()
        values[key(group, name)] = on
        writePersisted(readPersisted().enabled, values)
    }

    fun vpnHints(): VpnHints {
        val saved = readPersisted()
        if (!saved.enabled) return VpnHints(false, false, false, false)
        val values = effectiveValues()
        fun on(group: String, name: String): Boolean = values[key(group, name)] == true
        val strict = on(GROUP_STRICT, "严格路由")
        return VpnHints(
            captureAll = strict,
            blockBypass = strict || on(GROUP_STRICT, "禁止绕过 VPN"),
            blockIpv6 = on(GROUP_LEAK, "关闭 IPv6"),
            hijackDns = on(GROUP_LEAK, "DNS 走代理") || on(GROUP_LEAK, "禁止系统 DNS") || on(GROUP_STRICT, "DNS 遵循规则"),
        )
    }

    private fun effectiveValues(): LinkedHashMap<String, Boolean> {
        ensureScript()
        val declared = parse(scriptFile.readText())
        val saved = readPersisted().values
        val out = LinkedHashMap<String, Boolean>()
        fun take(group: String, declaredMap: LinkedHashMap<String, Boolean>?) {
            val defaults = declaredMap ?: builtins(group)
            for ((name, fallback) in defaults) {
                out[key(group, name)] = saved[key(group, name)] ?: fallback
            }
        }
        take(GROUP_RULE, declared.rule)
        take(GROUP_LEAK, declared.leak)
        take(GROUP_CN, declared.cn)
        take(GROUP_STRICT, declared.strict)
        return out
    }

    private fun section(
        group: String,
        declared: LinkedHashMap<String, Boolean>?,
        saved: Map<String, Boolean>,
    ): Section {
        val defaults = declared ?: builtins(group)
        val rows = defaults.map { (name, fallback) ->
            Row(name, saved[key(group, name)] ?: fallback, SUMMARIES[name])
        }
        return Section(group, rows)
    }

    private fun defaultScript(): String {
        return context.assets.open(ASSET).bufferedReader().use { it.readText() }
    }

    private data class Persisted(val enabled: Boolean, val values: LinkedHashMap<String, Boolean>)

    private fun readPersisted(): Persisted {
        if (!optionFile.exists()) return Persisted(false, linkedMapOf())
        return try {
            val root = JSONObject(optionFile.readText())
            val values = linkedMapOf<String, Boolean>()
            val obj = root.optJSONObject("values")
            if (obj != null) {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    values[k] = obj.optBoolean(k, false)
                }
            }
            Persisted(root.optBoolean("enabled", false), values)
        } catch (_: Exception) {
            Persisted(false, linkedMapOf())
        }
    }

    private fun writePersisted(enabled: Boolean, values: Map<String, Boolean>) {
        val obj = JSONObject()
        obj.put("enabled", enabled)
        val map = JSONObject()
        for ((k, v) in values) map.put(k, v)
        obj.put("values", map)
        writeAtomic(optionFile, obj.toString())
    }

    companion object {
        const val OPTIONS = "script-options.json"
        const val SCRIPT = "script.js"
        private const val ASSET = "angela/default-script.js"
        const val GROUP_RULE = "rule"
        const val GROUP_LEAK = "leak"
        const val GROUP_CN = "cn"
        const val GROUP_STRICT = "strict"

        fun key(group: String, name: String) = "$group\u001f$name"

        private val SUMMARIES = mapOf(
            "AI" to "OpenAI、Claude、Gemini 走独立策略组",
            "Google" to "Google 域名走独立策略组",
            "YouTube" to "YouTube 走独立策略组",
            "Telegram" to "Telegram 走独立策略组",
            "Netflix" to "Netflix 走独立策略组",
            "广告拦截" to "广告域名直接拒绝",
            "DNS 走代理" to "境外 DNS 使用 1.1.1.1 / 8.8.8.8，并按规则出站",
            "禁止系统 DNS" to "去掉 system://，避免查询落到运营商",
            "关闭 IPv6" to "关闭内核和 VPN 的 IPv6，避免地址泄漏",
            "阻断 QUIC" to "拒绝 UDP 443，避免 QUIC 绕过代理",
            "嗅探防泄漏" to "开启嗅探并覆盖目标地址",
            "中国大陆 IP 直连" to "GEOIP 中国大陆走 DIRECT",
            "中国大陆域名直连" to "GEOSITE cn 走 DIRECT",
            "国内 DNS" to "国内域名用 223.5.5.5 解析",
            "局域网直连" to "私有地址直连，不送去代理",
            "严格路由" to "VPN 接管全部流量，不允许绕过",
            "禁止绕过 VPN" to "关闭系统的 VPN 绕过",
            "DNS 遵循规则" to "DNS 出口跟分流规则走",
            "进程严格匹配" to "find-process-mode 设为 strict",
        )

        fun builtins(group: String): LinkedHashMap<String, Boolean> = when (group) {
            GROUP_RULE -> linkedMapOf(
                "AI" to true,
                "Google" to true,
                "YouTube" to true,
                "Telegram" to true,
                "Netflix" to true,
                "广告拦截" to true,
            )
            GROUP_LEAK -> linkedMapOf(
                "DNS 走代理" to true,
                "禁止系统 DNS" to true,
                "关闭 IPv6" to true,
                "阻断 QUIC" to false,
                "嗅探防泄漏" to true,
            )
            GROUP_CN -> linkedMapOf(
                "中国大陆 IP 直连" to true,
                "中国大陆域名直连" to true,
                "国内 DNS" to true,
                "局域网直连" to true,
            )
            else -> linkedMapOf(
                "严格路由" to false,
                "禁止绕过 VPN" to false,
                "DNS 遵循规则" to true,
                "进程严格匹配" to false,
            )
        }

        fun parse(source: String): Declared {
            val head = if (source.length > 4000) source.substring(0, 4000) else source
            return Declared(
                rule = readObject(source, "ruleOptionsEnable"),
                leak = readObject(source, "leakOptionsEnable"),
                cn = readObject(source, "cnDirectOptionsEnable"),
                strict = readObject(source, "strictRouteOptionsEnable"),
                bettbox = head.contains("Compatible_With_Bettbox"),
                angela = head.contains("Compatible_With_AngelaClash"),
            )
        }

        private fun readObject(source: String, name: String): LinkedHashMap<String, Boolean>? {
            val match = Regex("""(?:const|let|var)\s+${Regex.escape(name)}\s*=""").find(source) ?: return null
            var i = match.range.last + 1
            while (i < source.length && source[i].isWhitespace()) i++
            if (i >= source.length || source[i] != '{') return null
            val end = matchBrace(source, i) ?: return null
            return readEntries(source.substring(i + 1, end))
        }

        private fun matchBrace(source: String, open: Int): Int? {
            var depth = 0
            var i = open
            while (i < source.length) {
                val c = source[i]
                if (c == '"' || c == '\'' || c == '`') {
                    i = skipString(source, i)
                    continue
                }
                if (c == '/' && i + 1 < source.length && source[i + 1] == '/') {
                    i = source.indexOf('\n', i).let { if (it < 0) source.length else it + 1 }
                    continue
                }
                if (c == '/' && i + 1 < source.length && source[i + 1] == '*') {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end < 0) source.length else end + 2
                    continue
                }
                if (c == '{') depth++
                if (c == '}') {
                    depth--
                    if (depth == 0) return i
                }
                i++
            }
            return null
        }

        private fun skipString(source: String, start: Int): Int {
            val quote = source[start]
            var i = start + 1
            while (i < source.length) {
                if (source[i] == '\\') {
                    i += 2
                    continue
                }
                if (source[i] == quote) return i + 1
                i++
            }
            return source.length
        }

        private fun readEntries(body: String): LinkedHashMap<String, Boolean> {
            val out = LinkedHashMap<String, Boolean>()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c == '/' && i + 1 < body.length && body[i + 1] == '/') {
                    i = body.indexOf('\n', i).let { if (it < 0) body.length else it + 1 }
                    continue
                }
                if (c == '/' && i + 1 < body.length && body[i + 1] == '*') {
                    val end = body.indexOf("*/", i + 2)
                    i = if (end < 0) body.length else end + 2
                    continue
                }
                if (c == '"' || c == '\'' || c == '`') {
                    val key = readString(body, i) ?: run { i++; continue }
                    i = skipWs(body, key.second)
                    if (i < body.length && body[i] == ':') {
                        val bool = readBool(body, skipWs(body, i + 1))
                        if (bool != null) {
                            out[key.first] = bool.first
                            i = bool.second
                            continue
                        }
                    }
                    i = key.second
                    continue
                }
                if (c.isLetter() || c == '_' || c == '$') {
                    val start = i
                    i++
                    while (i < body.length && (body[i].isLetterOrDigit() || body[i] == '_' || body[i] == '-' || body[i] == '$')) i++
                    val name = body.substring(start, i)
                    val colon = skipWs(body, i)
                    if (colon < body.length && body[colon] == ':') {
                        val bool = readBool(body, skipWs(body, colon + 1))
                        if (bool != null) {
                            out[name] = bool.first
                            i = bool.second
                            continue
                        }
                    }
                    continue
                }
                i++
            }
            return out
        }

        private fun readString(source: String, start: Int): Pair<String, Int>? {
            val quote = source[start]
            val sb = StringBuilder()
            var i = start + 1
            while (i < source.length) {
                val c = source[i]
                if (c == '\\' && i + 1 < source.length) {
                    sb.append(source[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) return sb.toString() to (i + 1)
                sb.append(c)
                i++
            }
            return null
        }

        private fun readBool(source: String, start: Int): Pair<Boolean, Int>? {
            if (source.startsWith("true", start)) return true to (start + 4)
            if (source.startsWith("false", start)) return false to (start + 5)
            return null
        }

        private fun skipWs(source: String, start: Int): Int {
            var i = start
            while (i < source.length && source[i].isWhitespace()) i++
            return i
        }

        private fun writeAtomic(file: File, text: String) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    data class Declared(
        val rule: LinkedHashMap<String, Boolean>?,
        val leak: LinkedHashMap<String, Boolean>?,
        val cn: LinkedHashMap<String, Boolean>?,
        val strict: LinkedHashMap<String, Boolean>?,
        val bettbox: Boolean,
        val angela: Boolean,
    )
}
