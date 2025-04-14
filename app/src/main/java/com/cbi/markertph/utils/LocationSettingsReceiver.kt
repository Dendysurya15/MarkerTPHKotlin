package com.cbi.markertph.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager

class LocationSettingsReceiver(private val callback: LocationSettingsCallback) : BroadcastReceiver() {
    
    interface LocationSettingsCallback {
        fun onLocationSettingsChanged(isEnabled: Boolean)
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
            val locationManager = context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val isGpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false
            
            callback.onLocationSettingsChanged(isGpsEnabled)
        }
    }
} 