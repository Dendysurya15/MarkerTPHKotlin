package com.cbi.markertph.utils

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.cbi.markertph.R
import com.cbi.markertph.data.model.*
import com.cbi.markertph.databinding.PertanyaanSpinnerLayoutBinding
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.cbi.markertph.utils.AppUtils.vibrate
import com.cbi.markertph.utils.AppUtils.stringXML

class FormHelper(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    // Selection data
    var userInput: String = ""
    var ancakInput: String = ""
    var selectedRegional: String = ""
    var selectedWilayah: String = ""
    var selectedEstate: String = ""
    var selectedAfdeling: String = ""
    var selectedBlok: String = ""
    var selectedTPH: String = ""

    // Selection value data
    var selectedRegionalValue: Int? = null
    var selectedWilayahValue: Int? = null
    var selectedEstateValue: Int? = null
    var selectedDivisiValue: Int? = null
    var selectedBlokValue: Int? = null
    var selectedDivisionCodeValue: Int? = null
    var selectedTahunTanamValue: String? = null
    var selectedFieldCodeValue: Int? = null
    var selectedTPHValue: Int? = null

    // Selection indexes
    var selectedDivisionSpinnerIndex: Int? = null
    var selectedRegionalSpinnerIndex: Int? = null
    var selectedWilayahSpinnerIndex: Int? = null
    var selectedEstateSpinnerIndex: Int? = null
    var selectedBUnitSpinnerIndex: Int? = null
    var selectedFieldCodeSpinnerIndex: Int? = null
    var selectedTPHSpinnerIndex: Int? = null

    enum class InputType {
        SPINNER,
        EDITTEXT
    }

    fun updateTextInPertanyaan(layoutBinding: PertanyaanSpinnerLayoutBinding, text: String) {
        layoutBinding.tvTitleForm.text = text
    }

    fun setupEditTextView(
        layoutBinding: PertanyaanSpinnerLayoutBinding,
        savedUserInput: String? = null,
        savedAncakInput: String? = null
    ) {
        with(layoutBinding) {
            spHomeMarkerTPH.visibility = View.GONE
            etHomeMarkerTPH.visibility = View.VISIBLE

            // Handle saved inputs for user and ancak
            if (layoutBinding.tvTitleForm.text.toString() == context.getString(R.string.field_nama_user)) {
                savedUserInput?.let {
                    if (it.isNotEmpty()) {
                        etHomeMarkerTPH.setText(it)
                        userInput = it
                    }
                }
            }

            // Set enter key behavior
            etHomeMarkerTPH.setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                    true
                } else {
                    false
                }
            }

            // Set text change listener
            etHomeMarkerTPH.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    tvError.visibility = View.GONE
                    MCVSpinner.strokeColor = ContextCompat.getColor(root.context, R.color.graytextdark)

                    when (layoutBinding.tvTitleForm.text.toString()) {
                        context.getString(R.string.field_nama_user) -> {
                            userInput = s.toString()
                            Log.d("EditText", "UserInput updated: $userInput")
                        }
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
    }

    fun setupSpinnerView(
        layoutBinding: PertanyaanSpinnerLayoutBinding,
        data: List<String>,
        datasets: DataLoaderHelper.DatasetBundle? = null,
        onSpinnerItemSelected: (String, Int) -> Unit = { _, _ -> }
    ) {
        with(layoutBinding) {
            spHomeMarkerTPH.visibility = View.VISIBLE
            etHomeMarkerTPH.visibility = View.GONE
            spHomeMarkerTPH.setItems(data)

            spHomeMarkerTPH.setOnItemSelectedListener { _, position, _, item ->
                tvError.visibility = View.GONE
                MCVSpinner.strokeColor = ContextCompat.getColor(root.context, R.color.graytextdark)
                onSpinnerItemSelected(item.toString(), position)
            }
        }
    }

    fun clearSpinnerView(
        layoutBinding: PertanyaanSpinnerLayoutBinding,
        resetSelectedValue: () -> Unit
    ) {
        layoutBinding.root.visibility = View.GONE
        setupSpinnerView(layoutBinding, emptyList()) // Reset spinner with an empty list
        resetSelectedValue() // Reset the associated selected value
    }

    fun resetViewsBelow(triggeredLayout: PertanyaanSpinnerLayoutBinding, viewBindings: Map<String, PertanyaanSpinnerLayoutBinding>) {
        // Find the save button and hide it
        val mbSave = viewBindings["Save"]?.root?.findViewById<MaterialButton>(R.id.mbSaveDataTPH)
        mbSave?.visibility = View.GONE

        // Hide coordinate cards
        val coordinateCard = viewBindings["CoordinateCard"]?.root?.findViewById<MaterialCardView>(R.id.cardKoordinatTerdaftar)
        coordinateCard?.visibility = View.GONE
        
        val coordinateWrongCard = viewBindings["CoordinateWrongCard"]?.root?.findViewById<MaterialCardView>(R.id.cardKoordinatKurangTepat)
        coordinateWrongCard?.visibility = View.GONE

        // Determine which fields to reset based on the triggering layout
        when (triggeredLayout) {
            viewBindings["Regional"] -> {
                resetField(viewBindings["Wilayah"]) { selectedWilayahValue = null }
                resetField(viewBindings["Estate"]) { selectedEstateValue = null }
                resetField(viewBindings["Afdeling"]) { selectedDivisiValue = null }
                resetField(viewBindings["TahunTanam"]) { selectedTahunTanamValue = null }
                resetField(viewBindings["Blok"]) { selectedBlokValue = null }
                resetField(viewBindings["TPH"]) { selectedTPHValue = null; selectedTPH = "" }
            }
            viewBindings["Wilayah"] -> {
                resetField(viewBindings["Estate"]) { selectedEstateValue = null }
                resetField(viewBindings["Afdeling"]) { selectedDivisiValue = null }
                resetField(viewBindings["TahunTanam"]) { selectedTahunTanamValue = null }
                resetField(viewBindings["Blok"]) { selectedBlokValue = null }
                resetField(viewBindings["TPH"]) { selectedTPHValue = null; selectedTPH = "" }
            }
            viewBindings["Estate"] -> {
                resetField(viewBindings["Afdeling"]) { selectedDivisiValue = null }
                resetField(viewBindings["TahunTanam"]) { selectedTahunTanamValue = null }
                resetField(viewBindings["Blok"]) { selectedBlokValue = null }
                resetField(viewBindings["TPH"]) { selectedTPHValue = null; selectedTPH = "" }
            }
            viewBindings["Afdeling"] -> {
                resetField(viewBindings["TahunTanam"]) { selectedTahunTanamValue = null }
                resetField(viewBindings["Blok"]) { selectedBlokValue = null }
                resetField(viewBindings["TPH"]) { selectedTPHValue = null; selectedTPH = "" }
            }
            viewBindings["TahunTanam"] -> {
                resetField(viewBindings["Blok"]) { selectedBlokValue = null }
                resetField(viewBindings["TPH"]) { selectedTPHValue = null; selectedTPH = "" }
            }
            viewBindings["Blok"] -> {
                resetField(viewBindings["TPH"]) { selectedTPHValue = null; selectedTPH = "" }
            }
        }
    }

    private fun resetField(binding: PertanyaanSpinnerLayoutBinding?, resetAction: () -> Unit) {
        binding?.let {
            it.root.visibility = View.GONE
            setupSpinnerView(it, emptyList())
            resetAction()
        }
    }
    
    fun handleRegionalSelection(
        selectedRegional: String,
        position: Int,
        regionalList: List<RegionalModel>,
        wilayahList: List<WilayahModel>,
        wilayahLayout: PertanyaanSpinnerLayoutBinding
    ) {
        this.selectedRegional = selectedRegional
        selectedRegionalSpinnerIndex = position
        
        val selectedRegionalId = regionalList.find { it.nama == selectedRegional }?.id
        selectedRegionalValue = selectedRegionalId
        
        if (selectedRegionalId != null) {
            val filteredWilayahList = wilayahList.filter { it.regional == selectedRegionalId }
            val wilayahNames = filteredWilayahList.map { it.nama }
            
            if (wilayahNames.isNotEmpty()) {
                setupSpinnerView(wilayahLayout, wilayahNames)
                wilayahLayout.root.visibility = View.VISIBLE
            } else {
                wilayahLayout.root.visibility = View.GONE
            }
        } else {
            wilayahLayout.root.visibility = View.GONE
        }
    }
    
    fun handleWilayahSelection(
        selectedWilayah: String,
        position: Int,
        wilayahList: List<WilayahModel>,
        deptList: List<DeptModel>,
        estateLayout: PertanyaanSpinnerLayoutBinding
    ) {
        this.selectedWilayah = selectedWilayah
        selectedWilayahSpinnerIndex = position
        
        val selectedWilayahId = wilayahList.find { 
            it.regional == selectedRegionalValue && it.nama == selectedWilayah 
        }?.id
        
        selectedWilayahValue = selectedWilayahId
        
        if (selectedWilayahId != null) {
            val filteredDeptList = deptList.filter {
                it.regional == selectedRegionalValue && it.wilayah == selectedWilayahValue
            }
            val deptNames = filteredDeptList.map { it.nama }
            
            if (deptNames.isNotEmpty()) {
                setupSpinnerView(estateLayout, deptNames)
                estateLayout.root.visibility = View.VISIBLE
            } else {
                estateLayout.root.visibility = View.GONE
            }
        } else {
            estateLayout.root.visibility = View.GONE
        }
    }
    
    fun handleEstateSelection(
        selectedEstate: String,
        position: Int,
        deptList: List<DeptModel>,
        divisiList: List<DivisiModel>,
        afdelingLayout: PertanyaanSpinnerLayoutBinding
    ) {
        this.selectedEstate = selectedEstate
        selectedEstateSpinnerIndex = position
        
        val selectedEstateId = deptList.find {
            it.regional == selectedRegionalValue &&
            it.wilayah == selectedWilayahValue &&
            it.nama == selectedEstate
        }?.id
        
        selectedEstateValue = selectedEstateId
        
        if (selectedEstateId != null) {
            val filteredDivisiList = divisiList.filter { it.dept == selectedEstateId }
            val divisiNames = filteredDivisiList.map { it.abbr }
            
            if (divisiNames.isNotEmpty()) {
                setupSpinnerView(afdelingLayout, divisiNames)
                afdelingLayout.root.visibility = View.VISIBLE
            } else {
                afdelingLayout.root.visibility = View.GONE
            }
        } else {
            afdelingLayout.root.visibility = View.GONE
        }
    }
    
    fun handleAfdelingSelection(
        selectedAfdeling: String,
        position: Int,
        divisiList: List<DivisiModel>,
        deptList: List<DeptModel>,
        blokList: List<BlokModel>,
        tahunTanamLayout: PertanyaanSpinnerLayoutBinding
    ) {
        this.selectedAfdeling = selectedAfdeling
        selectedDivisionSpinnerIndex = position
        
        val selectedDivisiId = divisiList.find {
            it.abbr == selectedAfdeling && it.dept == selectedEstateValue
        }?.id
        
        selectedDivisiValue = selectedDivisiId
        
        if (selectedDivisiId != null) {
            if (selectedRegionalValue == 3) {
                // Hanya memfilter berdasarkan estateAbbr
                val estateAbbr = deptList.find { it.id == selectedEstateValue }?.abbr
                val filteredBlokList = blokList.filter {
                    it.regional == selectedRegionalValue &&
                    it.dept == selectedEstateValue &&
                    it.dept_abbr == estateAbbr
                }
                
                val tahunTanamList = filteredBlokList.map { it.tahun }.distinct().sorted()
                
                if (tahunTanamList.isNotEmpty()) {
                    setupSpinnerView(tahunTanamLayout, tahunTanamList)
                    tahunTanamLayout.root.visibility = View.VISIBLE
                } else {
                    tahunTanamLayout.root.visibility = View.GONE
                }
            } else {
                // Memfilter berdasarkan divisi saja
                val filteredBlokList = blokList.filter {
                    it.regional == selectedRegionalValue &&
                    it.dept == selectedEstateValue &&
                    it.divisi == selectedDivisiId
                }
                
                val tahunTanamList = filteredBlokList.map { it.tahun }.distinct().sorted()
                
                if (tahunTanamList.isNotEmpty()) {
                    setupSpinnerView(tahunTanamLayout, tahunTanamList)
                    tahunTanamLayout.root.visibility = View.VISIBLE
                } else {
                    tahunTanamLayout.root.visibility = View.GONE
                }
            }
        } else {
            tahunTanamLayout.root.visibility = View.GONE
        }
    }
    
    fun handleTahunTanamSelection(
        selectedTahunTanam: String,
        deptList: List<DeptModel>,
        blokList: List<BlokModel>,
        blokLayout: PertanyaanSpinnerLayoutBinding
    ) {
        selectedTahunTanamValue = selectedTahunTanam
        
        if (selectedRegionalValue == 3) {
            // Filter berdasarkan estateAbbr saja
            val estateAbbr = deptList.find { it.id == selectedEstateValue }?.abbr
            val filteredBlokCodes = blokList.filter {
                it.regional == selectedRegionalValue &&
                it.dept == selectedEstateValue &&
                it.dept_abbr == estateAbbr &&
                it.tahun == selectedTahunTanamValue
            }
            
            if (filteredBlokCodes.isNotEmpty()) {
                val blokNames = filteredBlokCodes.map { it.kode }
                setupSpinnerView(blokLayout, blokNames)
                blokLayout.root.visibility = View.VISIBLE
            } else {
                blokLayout.root.visibility = View.GONE
            }
        } else {
            // Filter berdasarkan divisi saja
            val filteredBlokCodes = blokList.filter {
                it.regional == selectedRegionalValue &&
                it.dept == selectedEstateValue &&
                it.divisi == selectedDivisiValue &&
                it.tahun == selectedTahunTanamValue
            }
            
            if (filteredBlokCodes.isNotEmpty()) {
                val blokNames = filteredBlokCodes.map { it.kode }
                setupSpinnerView(blokLayout, blokNames)
                blokLayout.root.visibility = View.VISIBLE
            } else {
                blokLayout.root.visibility = View.GONE
            }
        }
    }
    
    fun handleBlokSelection(
        selectedBlok: String,
        position: Int,
        deptList: List<DeptModel>,
        blokList: List<BlokModel>,
        tphList: List<TPHNewModel>?,
        tphLayout: PertanyaanSpinnerLayoutBinding,
        saveButton: MaterialButton
    ) {
        this.selectedBlok = selectedBlok
        selectedFieldCodeSpinnerIndex = position
        
        if (selectedRegionalValue == 3) {
            val estateAbbr = deptList.find { it.id == selectedEstateValue }?.abbr
            
            val selectedFieldId = blokList.find { blok ->
                blok.regional == selectedRegionalValue &&
                blok.dept == selectedEstateValue &&
                blok.dept_abbr == estateAbbr &&
                blok.tahun == selectedTahunTanamValue &&
                blok.kode == selectedBlok
            }?.id
            
            selectedBlokValue = selectedFieldId
            
            lifecycleScope.launch {
                val filteredTPH = withContext(Dispatchers.Default) {
                    tphList?.filter { tph ->
                        tph.regional == selectedRegionalValue &&
                        tph.dept == selectedEstateValue &&
                        tph.dept_abbr == estateAbbr &&
                        tph.tahun == selectedTahunTanamValue &&
                        tph.blok == selectedBlokValue
                    }
                }
                
                if (!filteredTPH.isNullOrEmpty()) {
                    val tphNumbers = filteredTPH.map { it.nomor }
                    setupSpinnerView(tphLayout, tphNumbers)
                    tphLayout.root.visibility = View.VISIBLE
                    saveButton.visibility = View.VISIBLE
                } else {
                    tphLayout.root.visibility = View.VISIBLE
                    saveButton.visibility = View.GONE
                }
            }
        } else {
            val selectedFieldId = blokList.find { blok ->
                blok.regional == selectedRegionalValue &&
                blok.dept == selectedEstateValue &&
                blok.divisi == selectedDivisiValue &&
                blok.tahun == selectedTahunTanamValue &&
                blok.kode == selectedBlok
            }?.id
            
            selectedBlokValue = selectedFieldId
            
            lifecycleScope.launch {
                val filteredTPH = withContext(Dispatchers.Default) {
                    tphList?.filter { tph ->
                        tph.regional == selectedRegionalValue &&
                        tph.dept == selectedEstateValue &&
                        tph.divisi == selectedDivisiValue &&
                        tph.tahun == selectedTahunTanamValue &&
                        tph.blok == selectedBlokValue
                    }
                }
                
                if (!filteredTPH.isNullOrEmpty()) {
                    val tphNumbers = filteredTPH.map { it.nomor }
                    setupSpinnerView(tphLayout, tphNumbers)
                    tphLayout.root.visibility = View.VISIBLE
                    saveButton.visibility = View.VISIBLE
                } else {
                    tphLayout.root.visibility = View.VISIBLE
                    saveButton.visibility = View.GONE
                }
            }
        }
    }
    
    fun handleTPHSelection(
        selectedTPH: String,
        position: Int,
        deptList: List<DeptModel>,
        tphList: List<TPHNewModel>?,
        coordinateCard: MaterialCardView,
        coordinateWrongCard: MaterialCardView,
        detectUserInput: TextView,
        detectTanggalInput: TextView,
        detectLatInput: TextView,
        detectLonInput: TextView
    ) {
        this.selectedTPH = selectedTPH
        selectedTPHSpinnerIndex = position
        
        val estateAbbr = deptList.find { it.id == selectedEstateValue }?.abbr
        
        // Filter TPH based on conditions
        val selectedTPHId = if (selectedRegionalValue == 3) {
            // Using estateAbbr
            tphList?.find {
                it.regional == selectedRegionalValue &&
                it.dept == selectedEstateValue &&
                it.dept_abbr == estateAbbr &&
                it.blok == selectedBlokValue &&
                it.tahun == selectedTahunTanamValue &&
                it.nomor == selectedTPH
            }
        } else {
            // Using divisi
            tphList?.find {
                it.regional == selectedRegionalValue &&
                it.dept == selectedEstateValue &&
                it.divisi == selectedDivisiValue &&
                it.blok == selectedBlokValue &&
                it.tahun == selectedTahunTanamValue &&
                it.nomor == selectedTPH
            }
        }
        
        selectedTPHValue = selectedTPHId?.id
        val selectedTPHLat = selectedTPHId?.lat
        val selectedTPHLon = selectedTPHId?.lon
        val selectedTPHUserInput = selectedTPHId?.user_input
        val selectedTPHUpdateDate = selectedTPHId?.update_date?.let { AppUtils.formatDateToIndonesian(it) }
        val selectedTPHStatus = selectedTPHId?.status
        
        val latPattern = "^-?([0-8]?[0-9]|90)\\.\\d+$".toRegex()
        val lonPattern = "^-?((1[0-7][0-9])|([0-9]?[0-9]))\\.\\d+$".toRegex()
        
        // Display coordinate information
        if (selectedTPHLat?.matches(latPattern) == true && selectedTPHLon?.matches(lonPattern) == true) {
            detectLatInput.text = "Latitude: $selectedTPHLat"
            detectLonInput.text = "Longitude: $selectedTPHLon"
            val formattedInput = selectedTPHUserInput.orEmpty().replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            }
            detectUserInput.text = "User Input: $formattedInput"
            detectTanggalInput.text = "Last Update: $selectedTPHUpdateDate"
            coordinateCard.visibility = View.VISIBLE
        } else {
            coordinateCard.visibility = View.GONE
        }
        
        // Show TPH status
        if (selectedTPHStatus == "2") {
            coordinateWrongCard.visibility = View.VISIBLE
        } else {
            coordinateWrongCard.visibility = View.GONE
        }
    }
    
    fun validateAndShowErrors(
        inputMappings: List<Triple<PertanyaanSpinnerLayoutBinding, String, InputType>>,
        locationEnabled: Boolean,
        lat: Double?,
        lon: Double?,
        currentAccuracy: Float
    ): Boolean {
        var isValid = true
        
        // Validate location
        if (!locationEnabled || lat == null || lon == null || lat == 0.0 || lon == 0.0) {
            isValid = false
            context.vibrate()
            AlertDialogUtility.withSingleAction(
                context,
                context.stringXML(R.string.al_back),
                context.stringXML(R.string.al_location_not_ready),
                context.stringXML(R.string.al_location_description_failed),
                "warning.json",
                R.color.colorRedDark
            ) {}
            return false
        }
        
        // Check accuracy
        if (currentAccuracy > 10.0f) {
            context.vibrate()
            AlertDialogUtility.withSingleAction(
                context,
                context.stringXML(R.string.al_back),
                context.stringXML(R.string.al_location_not_accurate),
                context.stringXML(R.string.al_location_under_ten_meter),
                "warning.json",
                R.color.colorRedDark
            ) {}
            return false
        }
        
        // Validate form fields
        val missingFields = mutableListOf<String>()
        
        inputMappings.forEach { (layoutBinding, key, inputType) ->
            val isEmpty = when (inputType) {
                InputType.SPINNER -> {
                    when (layoutBinding.tvTitleForm.text.toString()) {
                        context.getString(R.string.field_estate) -> selectedEstate.isEmpty()
                        context.getString(R.string.field_afdeling) -> selectedAfdeling.isEmpty()
                        context.getString(R.string.field_tahun_tanam) -> selectedTahunTanamValue?.isEmpty() ?: true
                        context.getString(R.string.field_blok) -> selectedBlok.isEmpty()
                        context.getString(R.string.field_tph) -> selectedTPH.isEmpty()
                        else -> layoutBinding.spHomeMarkerTPH.selectedIndex == -1
                    }
                }
                InputType.EDITTEXT -> {
                    when (key) {
                        "User Input" -> userInput.trim().isEmpty()
                        else -> layoutBinding.etHomeMarkerTPH.text.toString().trim().isEmpty()
                    }
                }
            }
            
            if (isEmpty) {
                layoutBinding.tvError.visibility = View.VISIBLE
                layoutBinding.MCVSpinner.strokeColor = ContextCompat.getColor(
                    context,
                    R.color.colorRedDark
                )
                missingFields.add(key)
                isValid = false
            } else {
                layoutBinding.tvError.visibility = View.GONE
                layoutBinding.MCVSpinner.strokeColor = ContextCompat.getColor(
                    context,
                    R.color.graytextdark
                )
            }
        }
        
        if (!isValid) {
            context.vibrate()
            AlertDialogUtility.withSingleAction(
                context,
                context.stringXML(R.string.al_back),
                context.stringXML(R.string.al_data_not_completed),
                "${context.stringXML(R.string.al_pls_complete_data)}",
                "warning.json",
                R.color.colorRedDark
            ) {}
        }
        
        return isValid
    }
} 