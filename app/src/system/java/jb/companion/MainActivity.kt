package jb.companion

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import android.view.Menu
import android.view.MenuItem
import jb.companion.databinding.ActivityMainBinding

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        /***
         * system_server process may not have properly initialized application
         * contexts, making PhoneWindow.generateDecor -> ContextImpl.initializeTheme
         * throw NPE due to null mResources, and crash system_server on app launch.
         * we prevent this by forcing PhoneWindow to use existing context for decor.
         */
        val window = Activity::class.java.getMethod("getWindow").invoke(this)
        val useDecorCtx = window.javaClass.getDeclaredField("mUseDecorContext")
        useDecorCtx.isAccessible = true
        useDecorCtx.setBoolean(window, false)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setTitle(R.string.activity_title)
        actionBar?.subtitle = "@diabl0w  github/xda"

        viewModel.output.observe(this) {
            binding.outputView.text = it
        }
        viewModel.dmcStatus.observe(this) {
            binding.bdStatusView.text = it
        }
        viewModel.dmcNeedsFix.observe(this) { needsFix ->
            binding.fixDmc.visibility = if (needsFix) View.VISIBLE else View.GONE
        }

        binding.fixDmc.setOnClickListener { viewModel.fixDmc() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
