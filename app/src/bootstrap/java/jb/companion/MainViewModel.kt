package jb.companion

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : BaseViewModel(application) {
    private val _injecting = MutableLiveData(false)
    val injecting: LiveData<Boolean> = _injecting

    private val _rootGranted = MutableLiveData(false)
    val rootGranted: LiveData<Boolean> = _rootGranted

    init {
        viewModelScope.launch(Dispatchers.IO) {
            log(Shell.cmd("id").exec().out.joinToString("\n").trim())
            _rootGranted.postValue(Shell.isAppGrantedRoot() == true)
        }
    }

    fun startInject() {
        if (_injecting.value == true) return
        viewModelScope.launch(Dispatchers.IO) {
            _injecting.postValue(true)
            try {
                EvilPMS().inject(getApplication(), ::log)
            } catch (e: Exception) {
                log("[x] ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "startInject failed", e)
            } finally {
                _injecting.postValue(false)
            }
        }
    }
}
