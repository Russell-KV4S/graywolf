package com.nw5w.graywolf.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.R as CarAppR
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import com.nw5w.graywolf.GraywolfService

/**
 * Android Auto [CarAppService] entry point. Ensures [GraywolfService] is running when the
 * vehicle head unit connects and launches [GraywolfCarMapScreen].
 */
class GraywolfCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(CarAppR.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        if (!GraywolfService.goListenerReady) {
            try {
                applicationContext.startForegroundService(
                    Intent(applicationContext, GraywolfService::class.java),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to boot GraywolfService from CarAppService: $t")
            }
        }
        return object : Session() {
            override fun onCreateScreen(intent: Intent): Screen {
                return GraywolfCarMapScreen(carContext)
            }
        }
    }

    companion object {
        private const val TAG = "GraywolfCarAppService"
    }
}
