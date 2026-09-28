package com.github.kr328.clash

import android.content.pm.PackageManager
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.common.constants.Metadata
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.OverrideSettingsDesign
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.toAppInfo
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class OverrideSettingsActivity : BaseActivity<OverrideSettingsDesign>() {
    override suspend fun main() {
        val configuration = withClash { queryOverride(Clash.OverrideSlot.Persist) }
        val section = intent.getStringExtra(SECTION) ?: "all"
        setTitle(when (section) {
            "dns" -> getString(com.github.kr328.clash.design.R.string.dns)
            "hosts" -> getString(com.github.kr328.clash.design.R.string.hosts)
            "general" -> getString(com.github.kr328.clash.design.R.string.general)
            else -> title
        })

        defer {
            withClash {
                patchOverride(Clash.OverrideSlot.Persist, configuration)
            }
        }

        val design = OverrideSettingsDesign(
            this,
            configuration,
            section,
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        OverrideSettingsDesign.Request.ResetOverride -> {
                            if (design.requestResetConfirm()) {
                                defer {
                                    withClash {
                                        clearOverride(Clash.OverrideSlot.Persist)
                                    }
                                }

                                finish()
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val SECTION = "section"
    }
}