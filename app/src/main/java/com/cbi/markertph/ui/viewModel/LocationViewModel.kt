package com.cbi.markertph.ui.viewModel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.location.LocationManager
import android.util.Log
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.cbi.markertph.R
import com.cbi.markertph.utils.AppUtils
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsStatusCodes
import com.google.android.gms.location.SettingsClient

class LocationViewModel(application: Application, private val imageView: ImageView, private val activity: Activity) : AndroidViewModel(application) {

    private val _locationPermissions = MutableLiveData<Boolean>()
    val locationPermissions: LiveData<Boolean>
        get() = _locationPermissions

    private val _locationData = MutableLiveData<Location>()
    val locationData: LiveData<Location>
        get() = _locationData

    private val _locationAccuracy = MutableLiveData<Float>()
    val locationAccuracy: LiveData<Float>
        get() = _locationAccuracy

    private var isStartLocations = false
    private val mFusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(application)
    private val mSettingsClient: SettingsClient =
        LocationServices.getSettingsClient(application)
    private var mLocationSettingsRequest: LocationSettingsRequest? = null
    private var mLocationRequest: LocationRequest? = null

    init {
        _locationPermissions.value = checkLocationPermission()
    }

    fun checkAndUpdateLocationPermissions() {
        _locationPermissions.value = checkLocationPermission()
        if (_locationPermissions.value == true && !isStartLocations) {
            startLocationUpdates()
        }
    }

    // Add a state for the location icon
    private val _locationIconState = MutableLiveData<Boolean>()
    val locationIconState: LiveData<Boolean>
        get() = _locationIconState

    private fun updateLocationIcon(isEnabled: Boolean) {
        _locationIconState.value = isEnabled
        imageView.setImageResource(R.drawable.baseline_location_pin_24)
        imageView.imageTintList = ColorStateList.valueOf(
            activity.resources.getColor(
                if (isEnabled) R.color.greenbutton else R.color.colorRed
            )
        )
    }

    private fun checkLocationPermission(): Boolean {
        val locationManager =
            getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        return ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED && isLocationEnabled
    }

    fun startLocationUpdates() {
        mLocationRequest = LocationRequest.create().apply {
            interval = AppUtils.UPDATE_INTERVAL_IN_MILLISECONDS  // 10 seconds
            fastestInterval = AppUtils.FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS  // 5 seconds
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            smallestDisplacement = 1f  // Add this to update only when moved 1 meter
        }

        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(mLocationRequest!!)
        mLocationSettingsRequest = builder.build()

        checkLocationSettings()
    }

    private fun checkLocationSettings() {
        mSettingsClient.checkLocationSettings(mLocationSettingsRequest!!)
            .addOnSuccessListener {
                if (checkLocationPermission()) {
                    try {
                        Log.i(AppUtils.LOG_LOC, "All location settings are satisfied.")
                        val locationCallback = object : LocationCallback() {
                            override fun onLocationResult(locationResult: LocationResult) {
                                locationResult.lastLocation?.let {
                                    if (it.latitude.toString().isNotEmpty()) {
                                        _locationData.value = it
                                        _locationAccuracy.value = it.accuracy // Add this line
                                        updateLocationIcon(true)  // Update icon here
                                        imageView.setImageResource(R.drawable.baseline_location_pin_24)
                                        imageView.imageTintList =
                                            ColorStateList.valueOf(activity.resources.getColor(R.color.greenbutton))
                                    }
                                }
                            }
                        }

                        mFusedLocationClient.requestLocationUpdates(
                            mLocationRequest!!,
                            locationCallback,
                            null
                        )
                    } finally {
                        isStartLocations = true
                    }
                }
            }
            .addOnFailureListener { e ->
                updateLocationIcon(false)
                imageView.imageTintList =
                    ColorStateList.valueOf(activity.resources.getColor(R.color.colorRed))
                when ((e as ApiException).statusCode) {
                    LocationSettingsStatusCodes.RESOLUTION_REQUIRED -> {
                        Log.i(
                            AppUtils.LOG_LOC,
                            "Location settings are not satisfied. Attempting to upgrade " +
                                    "location settings "
                        )
                        try {
                            val rae = e as ResolvableApiException
                            rae.startResolutionForResult(
                                activity,
                                AppUtils.REQUEST_CHECK_SETTINGS
                            )
                        } catch (sie: IntentSender.SendIntentException) {
                            Log.i(AppUtils.LOG_LOC, "PendingIntent unable to execute request.")
                        }
                    }

                    LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE -> {
                        val errorMessage =
                            "Location settings are inadequate and cannot be fixed here. Fix in Settings."
                        Log.e(AppUtils.LOG_LOC, errorMessage)
                    }
                }
            }
    }

    fun refreshLocationStatus() {
        if (checkLocationPermission() && isStartLocations) {
            updateLocationIcon(true)
        } else {
            updateLocationIcon(false)
        }
    }

    fun stopLocationUpdates() {
        if (isStartLocations) {
            try {
                mFusedLocationClient.removeLocationUpdates(
                    object : LocationCallback() {})
                Log.i(AppUtils.LOG_LOC, "Location stopped.")
            } finally {
                isStartLocations = false
            }
        }
    }

    fun createLocationRequest(): LocationRequest {
        return LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            interval = 5000 // Update every 5 seconds
            fastestInterval = 2000 // But can handle updates as fast as every 2 seconds
            smallestDisplacement = 0f // Min distance for update
        }
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(
        private val application: Application,
        private val imageView: ImageView,
        private val activity: Activity
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LocationViewModel::class.java)) {
                return LocationViewModel(application, imageView, activity) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

}