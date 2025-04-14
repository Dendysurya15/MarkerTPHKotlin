package com.cbi.markertph.utils

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.cbi.markertph.R
import com.cbi.markertph.ui.viewModel.LocationViewModel
import com.cbi.markertph.utils.AppUtils.stringXML
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationServices
import com.google.android.material.snackbar.Snackbar

class LocationHelper(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val locationViewModel: LocationViewModel,
    private val permissionRequestLauncher: ActivityResultLauncher<String>,
    private val onLocationStatusChanged: (Boolean) -> Unit
) {
    private var locationReceiver: LocationSettingsReceiver? = null
    private var locationCallback: LocationCallback? = null
    private var isPermissionRationaleShown = false
    
    companion object {
        val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ).plus(
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q)
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else
                    emptyArray()
            )
        }
    }
    
    fun initialize(callback: LocationCallback) {
        this.locationCallback = callback
        
        // Register location settings receiver
        locationReceiver = LocationSettingsReceiver(object : LocationSettingsReceiver.LocationSettingsCallback {
            override fun onLocationSettingsChanged(isEnabled: Boolean) {
                if (!isEnabled) {
                    locationViewModel.refreshLocationStatus()
                    onLocationStatusChanged(false)
                    AlertDialogUtility.withSingleAction(
                        context,
                        context.stringXML(R.string.al_back),
                        context.stringXML(R.string.al_location_not_ready),
                        context.stringXML(R.string.al_location_description_failed),
                        "warning.json",
                        R.color.colorRedDark
                    ) {}
                } else {
                    locationViewModel.startLocationUpdates()
                    onLocationStatusChanged(true)
                }
            }
        })
        
        context.registerReceiver(
            locationReceiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        )
        
        // Set up observers
        setupObservers()
    }
    
    private fun setupObservers() {
        locationViewModel.locationPermissions.observe(lifecycleOwner, Observer { isLocationEnabled ->
            if (!isLocationEnabled) {
                requestLocationPermission()
            } else {
                locationViewModel.startLocationUpdates()
            }
        })
        
        locationViewModel.locationData.observe(lifecycleOwner, Observer { location ->
            onLocationStatusChanged(true)
        })
    }
    
    fun startLocationUpdates() {
        locationCallback?.let { callback ->
            LocationServices.getFusedLocationProviderClient(context)
                .requestLocationUpdates(
                    locationViewModel.createLocationRequest(),
                    callback,
                    null
                )
        }
    }
    
    fun stopLocationUpdates() {
        locationCallback?.let { callback ->
            LocationServices.getFusedLocationProviderClient(context)
                .removeLocationUpdates(callback)
        }
    }
    
    fun cleanup() {
        try {
            locationReceiver?.let {
                context.unregisterReceiver(it)
            }
        } catch (e: Exception) {
            // Receiver might not be registered
        }
        stopLocationUpdates()
    }
    
    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (!isPermissionRationaleShown) {
                isPermissionRationaleShown = true
                Snackbar.make(
                    (context as androidx.appcompat.app.AppCompatActivity).findViewById(android.R.id.content),
                    context.stringXML(R.string.location_permission_message),
                    Snackbar.LENGTH_LONG
                ).show()
            }
            
            permissionRequestLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    
    fun areAllPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    fun getRequiredPermissionsNotGranted(): List<String> {
        return REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
} 