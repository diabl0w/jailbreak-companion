package jb.companion

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : BaseViewModel(application) {
    private val _dmcStatus = MutableLiveData<String>()
    val dmcStatus: LiveData<String> = _dmcStatus

    private val _dmcNeedsFix = MutableLiveData<Boolean>()
    val dmcNeedsFix: LiveData<Boolean> = _dmcNeedsFix

    init {
        viewModelScope.launch(Dispatchers.IO) {
            log(Runtime.getRuntime().exec("id").inputStream.bufferedReader().readText().trim())
            EvilPMS().dumpSharedUID { log(it) }
            postDmcState(DmcVault.read())
        }
    }

    fun fixDmc() {
        viewModelScope.launch(Dispatchers.IO) {
            _dmcStatus.postValue("Applying fix...")
            DmcVault.writeAtFlag()
            postDmcState(DmcVault.read())
        }
    }

    private fun postDmcState(r: DmcResult) {
        _dmcStatus.postValue(formatDmcStatus(r))
        _dmcNeedsFix.postValue(r is DmcResult.Available && r.lock == 1 && r.maint == 0 && r.at == 0)
    }

    private fun formatDmcStatus(r: DmcResult): String = when (r) {
        is DmcResult.Available -> {
            val lockText = if (r.lock == 1) "Enabled" else "Disabled"
            val maintText = if (r.maint == 1) "Enabled" else "Disabled"
            val atText = if (r.at == 1) "Enabled" else "Disabled"
            val policy = if (r.lock == 1 && r.maint == 0 && r.at == 0)
                "❌ Download Mode Blocked"
            else
                "✅ Download Mode Allowed"
            "Screen lock: $lockText\nMaintenance Mode: $maintText\nAT+SUDDLMOD: $atText\n$policy"
        }
        is DmcResult.Unsupported -> r.detail
    }
}
