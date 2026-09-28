package com.github.kr328.clash

import android.content.Intent
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.design.SettingsDesign
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class SettingsActivity : BaseActivity<SettingsDesign>() {
    override suspend fun main() {
        setTitle(DesignR.string.kernel_config)
        val design = SettingsDesign(this)

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        SettingsDesign.Request.StartGeneral ->
                            openOverride("general")
                        SettingsDesign.Request.StartNetwork ->
                            startActivity(NetworkSettingsActivity::class.intent)
                        SettingsDesign.Request.StartDns ->
                            openOverride("dns")
                        SettingsDesign.Request.StartHosts ->
                            openOverride("hosts")
                        SettingsDesign.Request.StartMetaFeature ->
                            startActivity(MetaFeatureSettingsActivity::class.intent)
                    }
                }
            }
        }
    }

    private fun openOverride(section: String) {
        startActivity(Intent(this, OverrideSettingsActivity::class.java).putExtra(OverrideSettingsActivity.SECTION, section))
    }
}