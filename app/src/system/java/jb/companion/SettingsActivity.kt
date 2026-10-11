package jb.companion

import android.app.Activity
import android.os.Bundle
import android.view.MenuItem

@Suppress("DEPRECATION")
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // same system_server PhoneWindow fix as MainActivity — required for every Activity in this process
        val window = Activity::class.java.getMethod("getWindow").invoke(this)
        val useDecorCtx = window.javaClass.getDeclaredField("mUseDecorContext")
        useDecorCtx.isAccessible = true
        useDecorCtx.setBoolean(window, false)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        fragmentManager.beginTransaction()
            .replace(R.id.settingsContainer, SettingsFragment())
            .commit()

        findViewById<android.widget.Button>(R.id.uninstall).setOnClickListener {
            EvilPMS().uninstall(this)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
