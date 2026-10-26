package com.github.kr328.clash.design.util

import java.io.File

/**
 * 从配置的 config.yaml 里读出 `rules:` 块，用来在「规则开关」页展示原配置的真实规则。
 *
 * 只做展示，不引入 YAML 依赖：按行扫，进入 rules: 之后收 `- xxx` 行，
 * 遇到第一个顶层 key 或缩进的非列表行就停。订阅配置里的规则一定是块列表写法，
 * 内联写法（`rules: [a, b]`）极少见，遇到就当没读到（页面会给出提示）。
 */
data class ConfigRuleSnapshot(
    val total: Int,
    val items: List<String>,
) {
    companion object {
        val EMPTY = ConfigRuleSnapshot(0, emptyList())
    }
}

/**
 * 从 config.yaml 里读出策略组名字，给「编辑规则」的目标下拉和策略组开关用。
 *
 * 目标必须选配置里真实存在的组，否则内核解析时会直接报
 * `proxy [...] not found`，整份配置加载失败。所以这里宁可少列，也不瞎猜：
 * 只认 `proxy-groups:` 块里 `- name: xxx` 这种写法。
 */
object ConfigGroups {
    private const val LIMIT = 200

    fun read(configFile: File): List<String> {
        if (!configFile.isFile) return emptyList()

        val names = ArrayList<String>()
        runCatching {
            configFile.bufferedReader().useLines { lines ->
                var inGroups = false
                for (raw in lines) {
                    if (!inGroups) {
                        if (raw.isNotEmpty() && raw[0] != ' ' && raw[0] != '\t' && raw.startsWith("proxy-groups:")) {
                            inGroups = true
                        }
                        continue
                    }
                    // 回到顶层的下一个 key：策略组块结束
                    if (raw.isNotEmpty() && raw[0] != ' ' && raw[0] != '\t') return@useLines

                    val trimmed = raw.trim()
                    if (!trimmed.startsWith("- ")) continue
                    val item = trimmed.removePrefix("- ").trim()
                    if (!item.startsWith("name:")) continue
                    val name = item.removePrefix("name:").trim().trim('"', '\'')
                    if (name.isNotEmpty() && name != "GLOBAL" && names.size < LIMIT) names.add(name)
                }
            }
        }

        return names
    }
}

object ConfigRules {
    /** 最多返回这么多条给界面，超出的只统计数量。 */
    private const val DISPLAY_LIMIT = 300

    fun read(configFile: File): ConfigRuleSnapshot {
        if (!configFile.isFile) return ConfigRuleSnapshot.EMPTY

        val items = ArrayList<String>()
        var total = 0

        runCatching {
            configFile.bufferedReader().useLines { lines ->
                var inRules = false

                for (raw in lines) {
                    if (!inRules) {
                        // rules 必须是顶层 key：前面不能有缩进
                        if (raw.isNotEmpty() && raw[0] != ' ' && raw[0] != '\t' && raw.startsWith("rules:")) {
                            val inline = raw.removePrefix("rules:").trim()
                            // 内联写法不展开，直接当作没读到
                            if (inline.startsWith("[")) return@useLines
                            inRules = true
                        }
                        continue
                    }

                    val trimmed = raw.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                    when {
                        trimmed.startsWith("- ") || trimmed == "-" -> {
                            val value = normalize(trimmed.removePrefix("-").trim())
                            if (value.isNotEmpty()) {
                                total++
                                if (items.size < DISPLAY_LIMIT) items.add(value)
                            }
                        }
                        // 回到顶层的下一个 key：规则块结束
                        raw.isNotEmpty() && raw[0] != ' ' && raw[0] != '\t' -> return@useLines
                        // 缩进的非列表行：不是规则块
                        else -> return@useLines
                    }
                }
            }
        }

        return if (total == 0) ConfigRuleSnapshot.EMPTY else ConfigRuleSnapshot(total, items)
    }

    /**
     * 把 YAML 列表项还原成内核看到的规则原文。
     *
     * 「规则开关」页用这条文本当键写进 script-options.json，内核在
     * UnmarshalAndPatch 时按同一个键去匹配 cfg.Rule，而 cfg.Rule 里的值是
     * YAML 解析后的结果：外层引号没了，行尾 `# 注释` 也没了。这页是按行扫的，
     * 所以得自己把这两步补上，否则键对不上，开关按下去像没反应。
     */
    private fun normalize(value: String): String = unquote(stripComment(value).trim()).trim()

    /**
     * 去掉行尾注释。只有「空白 + #」才算注释，规则本身可以带 #，
     * 例如 `DOMAIN-KEYWORD,foo#bar,DIRECT`；引号里的 # 也不算。
     */
    private fun stripComment(value: String): String {
        var quote = '\u0000'
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (quote != '\u0000') {
                if (c == '\\' && quote == '"' && i + 1 < value.length) {
                    i += 2
                    continue
                }
                if (c == quote) quote = '\u0000'
                i++
                continue
            }
            if (c == '"' || c == '\'') {
                quote = c
                i++
                continue
            }
            if (c == '#' && (i == 0 || value[i - 1].isWhitespace())) {
                return value.substring(0, i)
            }
            i++
        }
        return value
    }

    private fun unquote(value: String): String {
        if (value.length >= 2) {
            val first = value.first()
            val last = value.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length - 1)
            }
        }
        return value
    }
}
