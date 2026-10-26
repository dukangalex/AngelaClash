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
                            val value = unquote(trimmed.removePrefix("-").trim())
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
