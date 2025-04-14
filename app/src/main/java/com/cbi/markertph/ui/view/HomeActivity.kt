package com.cbi.markertph.ui.view

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cbi.markertph.R
import com.cbi.markertph.data.api.VolleyApiService
import com.cbi.markertph.data.model.BUnitCodeModel
import com.cbi.markertph.data.model.BlokModel
import com.cbi.markertph.data.model.CompanyCodeModel
import com.cbi.markertph.data.model.DeptModel
import com.cbi.markertph.data.model.DivisiModel
import com.cbi.markertph.data.model.DivisionCodeModel
import com.cbi.markertph.data.model.FieldCodeModel
import com.cbi.markertph.data.model.RegionalModel
import com.cbi.markertph.data.model.TPHModel
import com.cbi.markertph.data.model.TPHNewModel
import com.cbi.markertph.data.model.WilayahModel

import com.cbi.markertph.data.repository.TPHRepository
import com.cbi.markertph.databinding.ActivityHomeBinding
import com.cbi.markertph.databinding.PertanyaanSpinnerLayoutBinding
import com.cbi.markertph.ui.adapter.ProgressUploadAdapter
import com.cbi.markertph.utils.FormHelper.InputType
import com.cbi.markertph.ui.viewModel.LocationViewModel
import com.cbi.markertph.ui.viewModel.TPHViewModel
import com.cbi.markertph.utils.*
import com.cbi.markertph.utils.AppUtils.stringXML
import com.cbi.markertph.utils.AppUtils.vibrate
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import android.text.InputType as AndroidInputType

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private lateinit var locationViewModel: LocationViewModel
    private lateinit var tphViewModel: TPHViewModel
    private var prefManager: PrefManager? = null
    private var locationEnable: Boolean = false
    private var isPermissionRationaleShown = false
    private var lat: Double? = null
    private var lon: Double? = null
    private var userInput: String = ""
    private var ancakInput: String = ""
    private var currentAccuracy : Float = 0F

    // Helper classes
    private lateinit var dataLoaderHelper: DataLoaderHelper
    private lateinit var formHelper: FormHelper
    private lateinit var fileDownloadHelper: FileDownloadHelper
    private lateinit var loadingDialog: LoadingDialog
    
    // View bindings map for easy access
    private val viewBindings = mutableMapOf<String, PertanyaanSpinnerLayoutBinding>()

    private var filesToUpdate = mutableListOf<String>()

    private lateinit var dataCacheManager: DataCacheManager

    companion object {
        private const val CHUNK_SIZE = 8192 // 8KB chunks
        private const val DEFAULT_BUFFER_SIZE = 8192 * 4 // Increased buffer size for better performance
        private const val PERMISSION_REQUEST_CODE = 1001
    }
    
    // Callbacks and input mappings 
    private val locationSettingsCallback = object : LocationCallback() {
        override fun onLocationAvailability(locationAvailability: LocationAvailability) {
            super.onLocationAvailability(locationAvailability)

            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

            if (!isGpsEnabled) {
                locationViewModel.refreshLocationStatus()
                locationEnable = false
                AlertDialogUtility.withSingleAction(
                    this@HomeActivity,
                    stringXML(R.string.al_back),
                    stringXML(R.string.al_location_not_ready),
                    stringXML(R.string.al_location_description_failed),
                    "warning.json",
                    R.color.colorRedDark
                ) {}
            }
        }
    }

    private val locationSettingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

                if (!isGpsEnabled) {
                    locationEnable = false
                    locationViewModel.refreshLocationStatus()
                    AlertDialogUtility.withSingleAction(
                        this@HomeActivity,
                        stringXML(R.string.al_back),
                        stringXML(R.string.al_location_not_ready),
                        stringXML(R.string.al_location_description_failed),
                        "warning.json",
                        R.color.colorRedDark
                    ) {}
                } else {
                    // GPS is enabled, start location updates
                    locationEnable = true
                    locationViewModel.startLocationUpdates()
                }
            }
        }
    }
    
    data class ErrorResponse(
        val statusCode: Int,
        val message: String,
        val error: String? = null
    )
    
    private lateinit var inputMappings: List<Triple<PertanyaanSpinnerLayoutBinding, String, InputType>>

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                locationViewModel.startLocationUpdates()
            } else {
//                showSnackbar(getString(R.string.location_permission_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize managers and helpers
        initializeManagers()
        initializeViewBindings()
        initializeViewModel()
        initializeHelpers()
        
        // Check permissions and set up app
        checkPermissions()
        setAppVersion()

        // Register location broadcast receiver
        registerReceiver(
            locationSettingsReceiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        )
        
        // Set up UI listeners
        setupUIListeners()
        
        // Handle back press
        setupBackPressHandler()
    }
    
    private fun initializeManagers() {
        prefManager = PrefManager(this)
        loadingDialog = LoadingDialog(this)
    }
    
    private fun initializeViewBindings() {
        // Store layout bindings in a map for easier access
        viewBindings["UserInput"] = binding.layoutUserInput
        viewBindings["Regional"] = binding.layoutRegional
        viewBindings["Wilayah"] = binding.layoutWilayah
        viewBindings["Estate"] = binding.layoutEstate
        viewBindings["Afdeling"] = binding.layoutAfdeling
        viewBindings["TahunTanam"] = binding.layoutTahunTanam
        viewBindings["Blok"] = binding.layoutBlok
        viewBindings["TPH"] = binding.layoutTPH
        viewBindings["Save"] = binding.layoutTPH // Reference to layout containing save button
        viewBindings["CoordinateCard"] = binding.layoutTPH // Reference to layout containing coordinate card
        viewBindings["CoordinateWrongCard"] = binding.layoutTPH // Reference to layout containing wrong coordinate card
    }

    private fun initializeViewModel() {
        tphViewModel = ViewModelProvider(
            this,
            TPHViewModel.Factory(application, TPHRepository(this))
        )[TPHViewModel::class.java]

        locationViewModel = ViewModelProvider(
            this,
            LocationViewModel.Factory(application, binding.statusLocation, this)
        )[LocationViewModel::class.java]
    }
    
    private fun initializeHelpers() {
        // Initialize data cache manager
        dataCacheManager = DataCacheManager(this)
        
        // Data loader helper
        dataLoaderHelper = DataLoaderHelper(
            this,
            dataCacheManager,
            prefManager!!,
            { isLoading, message ->
                if (isLoading) {
                    loadingDialog.show()
                    loadingDialog.setMessage(message)
                } else {
                    loadingDialog.dismiss()
                }
            }
        )
        
        // Form helper
        formHelper = FormHelper(this, lifecycleScope)
        
        // File download helper
        fileDownloadHelper = FileDownloadHelper(
            this,
            lifecycleScope,
            prefManager!!
        ) {
            // Callback when download completes
            lifecycleScope.launch {
                dataLoaderHelper.loadAllFilesAsync()
            }
        }
    }

    private fun setupUIListeners() {
        // Menu upload click listener
        binding.menuUpload.setOnClickListener{
            this.vibrate()
            startActivity(Intent(this@HomeActivity, UploadDataActivity::class.java))
            finish()
        }

        // Save data button click listener
        binding.mbSaveDataTPH.setOnClickListener {
            handleSaveButtonClick()
        }
    }
    
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitDialog()
            }
        })
    }
    
    private fun showExitDialog() {
        AlertDialogUtility.withTwoActions(
            this,
            stringXML(R.string.al_yes),
            stringXML(R.string.confirmation_dialog_title),
            stringXML(R.string.al_confirm_out),
            "warning.json"
        ) {
            finish()
        }
    }

    private fun getDeviceInfo(context: Context): JSONObject {
        val json = JSONObject()

        val appVersion = context.getString(R.string.app_version)

        json.put("app_version", appVersion)
        json.put("os_version", Build.VERSION.RELEASE)
        json.put("device_model", Build.MODEL)

        return json
    }

    private fun setupLayout() {
        // Get datasets from loader
        val datasets = dataLoaderHelper.getLoadedData()

        inputMappings = listOf(
            Triple(binding.layoutUserInput, getString(R.string.field_nama_user), InputType.EDITTEXT),
            Triple(binding.layoutRegional, getString(R.string.field_regional), InputType.SPINNER),
            Triple(binding.layoutWilayah, getString(R.string.field_wilayah), InputType.SPINNER),
            Triple(binding.layoutEstate, getString(R.string.field_estate), InputType.SPINNER),
            Triple(binding.layoutAfdeling, getString(R.string.field_afdeling), InputType.SPINNER),
            Triple(binding.layoutTahunTanam, getString(R.string.field_tahun_tanam), InputType.SPINNER),
            Triple(binding.layoutBlok, getString(R.string.field_blok), InputType.SPINNER),
            Triple(binding.layoutTPH, getString(R.string.field_tph), InputType.SPINNER)
        )

        // First set up basic layout structure
        inputMappings.forEach { (layoutBinding, key, inputType) ->
            formHelper.updateTextInPertanyaan(layoutBinding, key)
            when (inputType) {
                InputType.EDITTEXT -> formHelper.setupEditTextView(
                    layoutBinding,
                    prefManager?.user_input
                )
                InputType.SPINNER -> formHelper.setupSpinnerView(layoutBinding, emptyList()) // Initialize empty first
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (datasets.regionalList.isNotEmpty()) {
                    val regionalOptions = datasets.regionalList.map { it.nama }
                    
                    setupRegionalSpinner(regionalOptions, datasets)
                }
            }
        }
    }
    
    private fun setupRegionalSpinner(regionalOptions: List<String>, datasets: DataLoaderHelper.DatasetBundle) {
        formHelper.setupSpinnerView(
            binding.layoutRegional,
            regionalOptions
        ) { selectedRegional, position ->
            // When regional is selected
            formHelper.resetViewsBelow(binding.layoutRegional, viewBindings)
            formHelper.handleRegionalSelection(
                selectedRegional,
                position,
                datasets.regionalList,
                datasets.wilayahList,
                binding.layoutWilayah
            )
            
            // Setup wilayah spinner listener
            if (binding.layoutWilayah.root.visibility == View.VISIBLE) {
                setupWilayahSpinner(datasets)
            }
        }
    }
    
    private fun setupWilayahSpinner(datasets: DataLoaderHelper.DatasetBundle) {
        formHelper.setupSpinnerView(
            binding.layoutWilayah,
            binding.layoutWilayah.spHomeMarkerTPH.getItems<String>()
        ) { selectedWilayah, wilayahPosition ->
            // When wilayah is selected
            formHelper.resetViewsBelow(binding.layoutWilayah, viewBindings)
            formHelper.handleWilayahSelection(
                selectedWilayah,
                wilayahPosition,
                datasets.wilayahList,
                datasets.deptList,
                binding.layoutEstate
            )
            
            // Setup estate spinner listener
            if (binding.layoutEstate.root.visibility == View.VISIBLE) {
                setupEstateSpinner(datasets)
            }
        }
    }
    
    private fun setupEstateSpinner(datasets: DataLoaderHelper.DatasetBundle) {
        formHelper.setupSpinnerView(
            binding.layoutEstate,
            binding.layoutEstate.spHomeMarkerTPH.getItems<String>()
        ) { selectedEstate, estatePosition ->
            // When estate is selected
            formHelper.resetViewsBelow(binding.layoutEstate, viewBindings)
            formHelper.handleEstateSelection(
                selectedEstate,
                estatePosition,
                datasets.deptList,
                datasets.divisiList,
                binding.layoutAfdeling
            )
            
            // Setup afdeling spinner listener
            if (binding.layoutAfdeling.root.visibility == View.VISIBLE) {
                setupAfdelingSpinner(datasets)
            }
        }
    }
    
    private fun setupAfdelingSpinner(datasets: DataLoaderHelper.DatasetBundle) {
        formHelper.setupSpinnerView(
            binding.layoutAfdeling,
            binding.layoutAfdeling.spHomeMarkerTPH.getItems<String>()
        ) { selectedAfdeling, afdelingPosition ->
            // When afdeling is selected
            formHelper.resetViewsBelow(binding.layoutAfdeling, viewBindings)
            formHelper.handleAfdelingSelection(
                selectedAfdeling,
                afdelingPosition,
                datasets.divisiList,
                datasets.deptList,
                datasets.blokList,
                binding.layoutTahunTanam
            )
            
            // Setup tahun tanam spinner listener
            if (binding.layoutTahunTanam.root.visibility == View.VISIBLE) {
                setupTahunTanamSpinner(datasets)
            }
        }
    }
    
    private fun setupTahunTanamSpinner(datasets: DataLoaderHelper.DatasetBundle) {
        formHelper.setupSpinnerView(
            binding.layoutTahunTanam,
            binding.layoutTahunTanam.spHomeMarkerTPH.getItems<String>()
        ) { selectedTahunTanam, _ ->
            // When tahun tanam is selected
            formHelper.resetViewsBelow(binding.layoutTahunTanam, viewBindings)
            formHelper.handleTahunTanamSelection(
                selectedTahunTanam,
                datasets.deptList,
                datasets.blokList,
                binding.layoutBlok
            )
            
            // Setup blok spinner listener
            if (binding.layoutBlok.root.visibility == View.VISIBLE) {
                setupBlokSpinner(datasets)
            }
        }
    }
    
    private fun setupBlokSpinner(datasets: DataLoaderHelper.DatasetBundle) {
        formHelper.setupSpinnerView(
            binding.layoutBlok,
            binding.layoutBlok.spHomeMarkerTPH.getItems<String>()
        ) { selectedBlok, position ->
            // When blok is selected
            formHelper.resetViewsBelow(binding.layoutBlok, viewBindings)
            formHelper.handleBlokSelection(
                selectedBlok,
                position,
                datasets.deptList,
                datasets.blokList,
                datasets.tphList,
                binding.layoutTPH,
                binding.mbSaveDataTPH
            )
            
            // Setup TPH spinner listener
            if (binding.layoutTPH.root.visibility == View.VISIBLE) {
                setupTPHSpinner(datasets)
            }
        }
    }
    
    private fun setupTPHSpinner(datasets: DataLoaderHelper.DatasetBundle) {
        formHelper.setupSpinnerView(
            binding.layoutTPH,
            binding.layoutTPH.spHomeMarkerTPH.getItems<String>()
        ) { selectedTPH, position ->
            val materialCardView = findViewById<MaterialCardView>(R.id.cardKoordinatTerdaftar)
            val materialCardViewTPHKoorSalah = findViewById<MaterialCardView>(R.id.cardKoordinatKurangTepat)
            val detectUserInput = findViewById<TextView>(R.id.detect_user_input)
            val detectTanggalInput = findViewById<TextView>(R.id.detect_tanggal_input)
            val detectLatInput = findViewById<TextView>(R.id.detect_lat_input)
            val detectLonInput = findViewById<TextView>(R.id.detect_lon_input)
            
            // Handle TPH selection
            formHelper.handleTPHSelection(
                selectedTPH,
                position,
                datasets.deptList,
                datasets.tphList,
                materialCardView,
                materialCardViewTPHKoorSalah,
                detectUserInput,
                detectTanggalInput,
                detectLatInput,
                detectLonInput
            )
        }
    }

    @SuppressLint("MissingPermission")
    override fun onResume() {
        super.onResume()
        locationViewModel.refreshLocationStatus()
        LocationServices.getFusedLocationProviderClient(this)
            .requestLocationUpdates(
                LocationRequest.create().apply {
                    priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                    interval = 5000 // Check every 5 seconds
                },
                locationSettingsCallback,
                null
            )

        // Set up location observers
        setupLocationObservers()
    }
    
    private fun setupLocationObservers() {
        // Observe permission state
        locationViewModel.locationPermissions.observe(this) { isLocationEnabled ->
            if (!isLocationEnabled) {
                requestLocationPermission()
            } else {
                locationViewModel.startLocationUpdates()
            }
        }

        // Observe location icon state
        locationViewModel.locationIconState.observe(this) { isEnabled ->
            binding.statusLocation.setImageResource(R.drawable.baseline_location_pin_24)
            binding.statusLocation.imageTintList = ColorStateList.valueOf(
                resources.getColor(
                    if (isEnabled) R.color.greenbutton else R.color.colorRed
                )
            )
        }

        // Observe location data
        locationViewModel.locationData.observe(this) { location ->
            locationEnable = true
            lat = location.latitude
            lon = location.longitude
        }

        // Observe location accuracy
        locationViewModel.locationAccuracy.observe(this) { accuracy ->
            binding.accuracyLocation.text = String.format("%.1f m", accuracy)
            currentAccuracy = accuracy
        }
    }

    private fun requestLocationPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )
        ) {
            showSnackbar(stringXML(R.string.location_permission_message))
            isPermissionRationaleShown = true
        } else {
            requestPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onPause() {
        super.onPause()
        locationViewModel.stopLocationUpdates()

        LocationServices.getFusedLocationProviderClient(this)
            .removeLocationUpdates(locationSettingsCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        locationViewModel.stopLocationUpdates()
        try {
            unregisterReceiver(locationSettingsReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
            Log.e("HomeActivity", "Error unregistering receiver: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val deniedPermissions = mutableListOf<String>()
            for (i in permissions.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permissions[i])
                }
            }

            if (deniedPermissions.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "The following permissions are required: ${deniedPermissions.joinToString()}",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                initializeDataLoading()
            }
        }
    }
    
    private fun setAppVersion() {
        val appVersion = AppUtils.getAppVersion(this)
        binding.versionApp.text = appVersion
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13 and above
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            // Android 12 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            initializeDataLoading()
        }
    }
    
    private fun initializeDataLoading() {
        lifecycleScope.launch {
            val shouldDownload = fileDownloadHelper.shouldStartFileDownload()
            if (shouldDownload) {
                Log.d("FileCheck", "Starting file download...")
                fileDownloadHelper.startFileDownload()
            } else {
                lifecycleScope.launch(Dispatchers.IO) {
                    withContext(Dispatchers.Main) {
                        loadingDialog.show()
                        loadingDialog.setMessage("Loading data...")
                    }

                    try {
                        val cachedData = dataCacheManager.getDatasets()

                        if (cachedData != null) {
                            val hasEmptyDatasets =
                                cachedData.regionalList.isEmpty() || cachedData.wilayahList.isEmpty() ||
                                        cachedData.deptList.isEmpty() ||
                                        cachedData.divisiList.isEmpty() ||
                                        cachedData.blokList.isEmpty() ||
                                        cachedData.tphList.isEmpty()

                            if (hasEmptyDatasets) {
                                withContext(Dispatchers.Main) {
                                    loadingDialog.dismiss()
                                    lifecycleScope.launch {
                                        dataLoaderHelper.loadAllFilesAsync()
                                    }
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    loadingDialog.dismiss()
                                    lifecycleScope.launch {
                                        dataLoaderHelper.loadAllFilesAsync()
                                    }
                                }
                            }
                        } else {
                            Log.d("testing", "No cached data found")
                            withContext(Dispatchers.Main) {
                                loadingDialog.dismiss()
                                lifecycleScope.launch {
                                    dataLoaderHelper.loadAllFilesAsync()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("DataLoading", "Error loading data: ${e.message}")
                        withContext(Dispatchers.Main) {
                            loadingDialog.dismiss()
                        }
                    }
                }
            }
        }
    }
    
    private fun handleSaveButtonClick() {
        if (currentAccuracy == null || currentAccuracy > 10.0f) {
            vibrate()
            AlertDialogUtility.withSingleAction(
                this,
                stringXML(R.string.al_back),
                stringXML(R.string.al_location_not_accurate),
                stringXML(R.string.al_location_under_ten_meter),
                "warning.json",
                R.color.colorRedDark
            ) {}
            return
        }

        val typedMappings = inputMappings as List<Triple<PertanyaanSpinnerLayoutBinding, String, FormHelper.InputType>>
        if (formHelper.validateAndShowErrors(typedMappings, locationEnable, lat, lon, currentAccuracy)) {
            AlertDialogUtility.withTwoActions(
                this,
                getString(R.string.al_save),
                getString(R.string.confirmation_dialog_title),
                getString(R.string.confirmation_dialog_description),
                "warning.json"
            ) {
                val app_version = getDeviceInfo(this)
                tphViewModel.insertPanenTBSVM(
                    tanggal = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(
                        Date()
                    ),
                    user_input = formHelper.userInput,
                    regional = formHelper.selectedRegional,
                    regional_id = formHelper.selectedRegionalValue!!,
                    wilayah = formHelper.selectedWilayah,
                    wilayah_id = formHelper.selectedWilayahValue!!,
                    estate = formHelper.selectedEstate,
                    id_estate = formHelper.selectedEstateValue ?: 0,
                    afdeling = formHelper.selectedAfdeling,
                    id_afdeling = formHelper.selectedDivisiValue ?: 0,
                    tahun_tanam = formHelper.selectedTahunTanamValue ?: "",
                    blok = formHelper.selectedBlok,
                    id_blok = formHelper.selectedBlokValue ?: 0,
                    ancak = formHelper.ancakInput,
                    tph = formHelper.selectedTPH,
                    id_tph = formHelper.selectedTPHValue!!,
                    panen_ulang = 0,
                    latitude = lat.toString(),
                    longitude = lon.toString(),
                    app_version = app_version.toString(),
                )

                tphViewModel.insertDBTPH.observe(this) { result ->
                    val (isInserted, errorMessage) = result  // Destructuring the Pair

                    if (isInserted) {
                        AlertDialogUtility.alertDialogAction(
                            this,
                            getString(R.string.al_success_save_local),
                            getString(R.string.al_description_success_save_local),
                            "success.json"
                        ) {}
                    } else {
                        AlertDialogUtility.alertDialogAction(
                            this,
                            getString(R.string.al_failed_save_local),
                            "${getString(R.string.al_description_failed_save_local)} $errorMessage",
                            "warning.json"
                        ) {}

                        // Show error message in Toast if available
                        Toast.makeText(
                            this,
                            errorMessage ?: getString(R.string.toast_failed_save_local),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }
}
// The rest of the implementation will remain in the file