package com.asior.czuj

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build

// Przybliżona pozycja tylko do kratki ok. 2 km (sieć komórkowa / Wi-Fi, bez GPS na siłę).
object Where {
    private const val MAX_AGE_MS = 30 * 60_000L

    fun locate(ctx: Context, done: (Location?) -> Unit) {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return done(null)
        try {
            if (Build.VERSION.SDK_INT >= 30 && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ctx.mainExecutor) { done(it ?: recent(lm)) }
            } else {
                done(recent(lm))
            }
        } catch (e: SecurityException) {
            done(null)
        } catch (e: IllegalArgumentException) {
            done(recent(lm))
        }
    }

    private fun recent(lm: LocationManager): Location? = try {
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
            .filter { System.currentTimeMillis() - it.time <= MAX_AGE_MS }
            .maxByOrNull { it.time }
    } catch (e: SecurityException) {
        null
    }
}
