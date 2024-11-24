package com.mcal.dtmf

import android.Manifest
import android.Manifest.permission.ANSWER_PHONE_CALLS
import android.Manifest.permission.CALL_PHONE
import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.READ_PHONE_STATE
import android.Manifest.permission.RECORD_AUDIO
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.TelecomManager
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.SlideTransition
import com.mcal.dtmf.data.repositories.main.MainRepository
import com.mcal.dtmf.receiver.BootReceiver
import com.mcal.dtmf.service.DtmfService
import com.mcal.dtmf.ui.main.MainScreen
import com.mcal.dtmf.ui.theme.VoyagerDialogTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val mainRepository: MainRepository by inject()
    private val permissionCode = 100

    private val powerReceiver: BroadcastReceiver = object : BootReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.let { chargePlug ->
                val usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
                val acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC
                val wirelessCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS
                mainRepository.setPower(usbCharge || acCharge || wirelessCharge)
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                mainRepository.setMicKeyClick(0)
                // Если есть звонок, то взаимодействует с ним
                // Иначе - запускает/останавливает таймер и сервис распознавания на 60 секунд
                callAction()
                return true
            }

            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                mainRepository.setMicKeyClick(0)
                if (mainRepository.getConnType() == "Репитер (2 Канала)") {
                    callAction(true)
                }
                return true
            }
            else -> return super.onKeyUp(keyCode, event)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        super.onKeyUp(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                mainRepository.setMicKeyClick(KeyEvent.KEYCODE_HEADSETHOOK)
                return true
            }

            KeyEvent.KEYCODE_VOLUME_UP -> {
                mainRepository.setMicKeyClick(KeyEvent.KEYCODE_VOLUME_UP)
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                mainRepository.setMicKeyClick(KeyEvent.KEYCODE_VOLUME_DOWN)
                return true
            }

            else -> return super.onKeyDown(keyCode, event)
        }
    }



    private fun callAction(hasProblem: Boolean = false) {
        if (mainRepository.getCall() != null && mainRepository.getConnType() == "Репитер (2 Канала") {
            when (mainRepository.getCallState()) {
                Call.STATE_RINGING -> DtmfService.callAnswer(this)
                Call.STATE_ACTIVE,
                Call.STATE_DIALING,
                Call.STATE_CONNECTING -> DtmfService.callEnd(this)
            }
        } else {
            if (mainRepository.getConnType() == "Репитер (2 Канала)") {
                mainRepository.setStartFlashlight(!mainRepository.getStartFlashlight(), hasProblem)
            }

        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        checkPermissions()
        offerReplacingDefaultDialer()
        super.onCreate(savedInstanceState)

        setContent {
            VoyagerDialogTheme {
                MaterialTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        tonalElevation = 0.dp,
                    ) {
                        Navigator(MainScreen()) { nav ->
                            SlideTransition(nav)
                        }
                    }
                }
            }
        }
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        intentFilter.addAction(Intent.ACTION_POWER_CONNECTED)
        intentFilter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerReceiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(powerReceiver, intentFilter)
        }

        // Запускаем корутину для проверки DTMF анализа
        checkDtmfAnalysis()

    }

    // автозапуск дтмф анализа через каждую минуту если по какойто причине остановился
    private fun checkDtmfAnalysis() {
        lifecycleScope.launch {
            while (true) { // Удалена переменная isCheckingDtmf
                if (mainRepository.getConnType() != "Репитер (2 Канала)") {
                    mainRepository.startDtmfIfNotRunning()
                }
                delay(60000) // Задержка на 1 минуту
            }
        }
    }

    private fun offerReplacingDefaultDialer() {
        if (getSystemService(TelecomManager::class.java).defaultDialerPackage != packageName) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val intent =
                    getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_DIALER)
                startActivityForResult(intent, 1)
            } else {
                Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                    .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                    .let(::startActivity)
            }
        }
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        // Запрос на чтение контактов
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_DENIED
        ) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }

        // Запрос разрешения на изменение состояния звука
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.MODIFY_AUDIO_SETTINGS
            ) == PackageManager.PERMISSION_DENIED
        ) {
            permissionsToRequest.add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        }

        // Запрос разрешения на включение режима "Не беспокоить"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_NOTIFICATION_POLICY
                ) == PackageManager.PERMISSION_DENIED
            ) {
                permissionsToRequest.add(Manifest.permission.ACCESS_NOTIFICATION_POLICY)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_DENIED
            ) {
                permissionsToRequest.add(POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(
                this,
                WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_DENIED
        ) {
            permissionsToRequest.add(WRITE_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(
                this,
                CALL_PHONE
            ) == PackageManager.PERMISSION_DENIED
        ) {
            permissionsToRequest.add(CALL_PHONE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    ANSWER_PHONE_CALLS
                ) == PackageManager.PERMISSION_DENIED
            ) {
                permissionsToRequest.add(ANSWER_PHONE_CALLS)
            }
        }
        if (ContextCompat.checkSelfPermission(
                this,
                READ_PHONE_STATE
            ) == PackageManager.PERMISSION_DENIED
        ) {
            permissionsToRequest.add(READ_PHONE_STATE)
        }
        if (ContextCompat.checkSelfPermission(
                this,
                RECORD_AUDIO
            ) == PackageManager.PERMISSION_DENIED
        ) {
            permissionsToRequest.add(RECORD_AUDIO)
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                permissionCode
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(powerReceiver)
    }

}
