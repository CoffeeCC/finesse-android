package org.jellyfin.mobile.carapp

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * "Finesse Video" on the car screen: films and episodes from the Finesse server, played on the
 * surface Android Auto gives navigation apps — and only while the car is parked (see [ParkedGate]).
 *
 * Music in the car is a separate, standard media app (sessionbrowser/LibraryService).
 */
class FinesseVideoService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = VideoListScreen(carContext)
    }
}
