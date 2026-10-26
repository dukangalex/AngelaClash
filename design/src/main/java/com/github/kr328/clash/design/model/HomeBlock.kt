package com.github.kr328.clash.design.model

import com.github.kr328.clash.design.R

/**
 * 首页网格里可以被收起的卡片。
 *
 * `key` 要和 `design_main.xml` 里那张卡的 `app:tileKey` 一致，也要和
 * `HomeGridView` 内部记的 key 一致——三处对不上，布局就存不回来。
 *
 * key 一旦发布就不能改：它会被写进 SharedPreferences，改了等于把老用户的设置丢掉。
 */
enum class HomeBlock(val key: String, val titleRes: Int) {
    SPEED("speed", R.string.home_block_speed),
    PROFILE("profile", R.string.home_block_profile),
    PROXY("proxy", R.string.home_block_proxy),
    DIAG_CONN("diag_conn", R.string.home_block_diag_conn),
    DIAG_SAFETY("diag_safety", R.string.home_block_diag_safety),
    DIAG_POLICY("diag_policy", R.string.home_block_diag_policy),
    DIAG_NETWORK("diag_network", R.string.home_block_diag_network),
    META("meta", R.string.home_block_meta),
    QUICK("quick", R.string.home_block_quick);

    companion object {
        /**
         * 1.4.6 之前只有一块「状态方砖」，存的是 "diag"。现在拆成四张，
         * 老用户勾掉的那一项要摊到四张卡上，否则升级后四张全冒出来。
         */
        const val LEGACY_DIAG = "diag"
    }
}
