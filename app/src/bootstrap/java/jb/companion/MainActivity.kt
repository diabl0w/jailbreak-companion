package jb.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import jb.companion.databinding.ActivityMainBinding

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setTitle(R.string.activity_title)

        viewModel.output.observe(this) {
            binding.outputView.text = it
            binding.outputScroll.post { binding.outputScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }

        viewModel.rootGranted.observe(this) { granted ->
            binding.inject.isEnabled = granted && viewModel.injecting.value != true
        }

        viewModel.injecting.observe(this) { injecting ->
            binding.inject.isEnabled = !injecting && viewModel.rootGranted.value == true
            binding.inject.text = if (injecting) "Injecting..." else "Inject as System App"
        }

        binding.inject.setOnClickListener { viewModel.startInject() }
    }
}
