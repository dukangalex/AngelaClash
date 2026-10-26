package com.github.kr328.clash.design.model

import com.github.kr328.clash.design.R

/**
 * 首页可以被用户隐藏的区块。
 *
 * key 一旦发布就不能改：它会被写进 SharedPreferences，改了等于把老用户的设置丢掉。
 */
enum class HomeBlock(val key: String, val titleRes: Int) {
    SPEED("speed", R.string.home_block_speed),
    PROFILE("profile", R.string.home_block_profile),
    PROXY("proxy", R.string.home_block_proxy),
    DIAG("diag", R.string.home_block_diag),
    META("meta", R.string.home_block_meta),
    QUICK("quick", R.string.home_block_quick),
}
