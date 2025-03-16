package com.mcal.dtmf.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.mcal.dtmf.data.repositories.main.MainRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlinx.coroutines.Job

class CallService : InCallService(), KoinComponent {
    private val mainRepository: MainRepository by inject()
    private var callJob: Job? = null

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            mainRepository.setCallState(state)
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val callNumber = call.details.handle?.schemeSpecificPart?.trim() ?: "fun onCallAdded() номер NULL"
        mainRepository.setCall(call)
        val callState: Int = getCallState(call)
        mainRepository.setCallState(callState)

        if (callState == 2) {
            mainRepository.setInput(callNumber.replace("+", ""))
            callJob = CoroutineScope(Dispatchers.IO).launch {
                mainRepository.speakSuperTelephone()
            }
        }
        call.registerCallback(callback)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        val callState: Int = getCallState(call)
        mainRepository.setInput("")
        mainRepository.setCall(null)
        mainRepository.setCallState(callState)
        call.unregisterCallback(callback)
        callJob?.cancel()
    }

    private fun getCallState(call: Call): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details.state
        } else {
            call.state
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DtmfService.stop(this)
        callJob?.cancel()
    }
}