package jb.companion

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.preference.PreferenceFragment
import android.preference.SwitchPreference

@Suppress("DEPRECATION")
class SettingsFragment : PreferenceFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences)

        val ctx = activity
        val bootReceiver = ComponentName(ctx, DmcBootReceiver::class.java)
        val bootPref = findPreference("dmc_boot") as SwitchPreference
        bootPref.isPersistent = false
        val state = ctx.packageManager.getComponentEnabledSetting(bootReceiver)
        bootPref.isChecked = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        bootPref.onPreferenceChangeListener = android.preference.Preference.OnPreferenceChangeListener { _, value ->
            ctx.packageManager.setComponentEnabledSetting(
                bootReceiver,
                if (value as Boolean) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            true
        }
    }
}
