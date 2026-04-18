package com.wildguard.app.core.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.wildguard.app.core.model.LocationData

class GpsProvider(private val context: Context) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var callback: LocationCallback? = null

    fun start(onLocation: (LocationData) -> Unit) {
        if (!hasPermission()) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                onLocation(
                    LocationData(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        altitudeGps = if (loc.hasAltitude()) loc.altitude else null,
                        accuracy = loc.accuracy,
                        speedMps = if (loc.hasSpeed()) loc.speed else null,
                        bearingGps = if (loc.hasBearing()) loc.bearing else null,
                        timestamp = loc.time
                    )
                )
            }
        }
        callback = cb

        try {
            client.requestLocationUpdates(request, cb, Looper.getMainLooper())
        } catch (_: SecurityException) {
            // Permission revoked between check and request
        }
    }

    fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
}
