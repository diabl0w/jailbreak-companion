package jb.companion

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

abstract class BaseViewModel(application: Application) : AndroidViewModel(application) {
    private val sb = StringBuilder()
    private val _output = MutableLiveData<String>()
    val output: LiveData<String> = _output

    protected fun log(line: String) {
        Log.i(TAG, line)
        sb.appendLine(line)
        _output.postValue(sb.toString())
    }
}
