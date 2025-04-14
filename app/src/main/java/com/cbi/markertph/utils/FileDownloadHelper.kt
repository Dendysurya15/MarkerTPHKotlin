package com.cbi.markertph.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cbi.markertph.R
import com.cbi.markertph.data.api.VolleyApiService
import com.cbi.markertph.ui.adapter.ProgressUploadAdapter
import com.cbi.markertph.utils.AppUtils.stringXML
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import android.view.View

class FileDownloadHelper(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val prefManager: PrefManager,
    private val onDownloadComplete: () -> Unit
) {
    private val filesToUpdate = mutableListOf<String>()
    
    fun isInternetAvailable(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

        // Check internet capability and perform ping
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && pingGoogle()
    }

    private fun pingGoogle(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("/system/bin/ping -c 1 www.google.com")
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.e("PingGoogle", "Ping failed: ${e.message}")
            false
        }
    }
    
    fun startFileDownload() {
        if (!isInternetAvailable()) {
            AlertDialogUtility.withSingleAction(
                context,
                context.stringXML(R.string.al_back),
                context.stringXML(R.string.al_no_internet_connection),
                context.stringXML(R.string.al_no_internet_connection_description_download_dataset),
                "network_error.json",
                R.color.colorRedDark
            ) {}
            return
        }

        lifecycleScope.launch {
            // Inflate dialog layout
            val dialogView = LayoutInflater.from(context).inflate(R.layout.list_card_upload, null)
            val alertDialog = AlertDialog.Builder(context)
                .setCancelable(false)
                .setView(dialogView)
                .create()

            alertDialog.show()
            alertDialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            val recyclerView = dialogView.findViewById<RecyclerView>(R.id.features_recycler_view)
            recyclerView?.layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

            // Get saved file list and determine which files need downloading
            val savedFileList = prefManager.getFileList().let { list ->
                if (list.isEmpty() || list.size != AppUtils.ApiCallManager.apiCallList.size) {
                    // If empty or wrong size, create new list with correct size
                    MutableList<String?>(AppUtils.ApiCallManager.apiCallList.size) { index ->
                        // Copy existing values if any, null otherwise
                        list.getOrNull(index)
                    }
                } else {
                    list.toMutableList()
                }
            }

            val filesToDownload = AppUtils.ApiCallManager.apiCallList.filterIndexed { index, pair ->
                val fileName = pair.first
                val file = File(context.getExternalFilesDir(null), fileName)
                val needsDownload = savedFileList.getOrNull(index) == null || !file.exists() || filesToUpdate.contains(fileName)
                Log.d("FileDownload", "File: $fileName, Needs download: $needsDownload")
                needsDownload
            }

            val apiCallsSize = filesToDownload.size
            if (apiCallsSize == 0) {
                Log.d("FileDownload", "No files need downloading")
                alertDialog.dismiss()
                onDownloadComplete()
                return@launch
            }

            val progressList = MutableList(apiCallsSize) { 0 }
            val statusList = MutableList(apiCallsSize) { "Menunggu" }
            val iconList = MutableList(apiCallsSize) { R.id.progress_circular_loading }
            val fileNames = filesToDownload.map { it.first }

            val progressAdapter = ProgressUploadAdapter(progressList, statusList, iconList, fileNames.toMutableList())
            recyclerView?.adapter = progressAdapter

            val titleTextView = dialogView.findViewById<TextView>(R.id.tvTitleProgressBarLayout)
            val counterTextView = dialogView.findViewById<TextView>(R.id.counter_dataset)
            counterTextView.text = "0 / $apiCallsSize"

            lifecycleScope.launch(Dispatchers.Main) {
                var dots = 0
                while (alertDialog.isShowing) {
                    titleTextView.text = "Mengunduh Dataset" + ".".repeat(dots)
                    dots = if (dots >= 4) 1 else dots + 1
                    delay(500)
                }
            }

            for (i in 0 until apiCallsSize) {
                withContext(Dispatchers.Main) {
                    progressAdapter.updateProgress(i, 0, "Menunggu", R.id.progress_circular_loading)
                }
            }

            var completedCount = 0
            val downloadsDir = context.getExternalFilesDir(null)

            for ((index, apiCall) in filesToDownload.withIndex()) {
                val fileName = apiCall.first
                val apiCallFunction = apiCall.second
                val originalIndex = AppUtils.ApiCallManager.apiCallList.indexOfFirst { it.first == fileName }

                withContext(Dispatchers.Main) {
                    progressAdapter.resetProgress(index)
                    progressAdapter.updateProgress(index, 0, "Sedang Mengunduh", R.id.progress_circular_loading)
                }

                for (progress in 0..100 step 10) {
                    withContext(Dispatchers.Main) {
                        progressAdapter.updateProgress(index, progress, "Sedang Mengunduh", R.id.progress_circular_loading)
                    }
                }

                val (isSuccessful, message) = downloadFile(fileName, apiCallFunction, downloadsDir, savedFileList)

                if (isSuccessful) {
                    completedCount++
                    withContext(Dispatchers.Main) {
                        progressAdapter.updateProgress(index, 100, message, R.drawable.baseline_check_24)
                    }
                    savedFileList[originalIndex] = fileName
                } else {
                    withContext(Dispatchers.Main) {
                        progressAdapter.updateProgress(index, 100, message, R.drawable.baseline_close_24)
                    }
                    savedFileList[originalIndex] = null
                }

                withContext(Dispatchers.Main) {
                    counterTextView.text = "$completedCount / $apiCallsSize"
                }
            }
            
            val cleanedList = savedFileList.toMutableList()
            for (i in cleanedList.indices.reversed()) {
                val fileName = cleanedList[i]
                if (fileName != null) {
                    // Check if this fileName appears earlier in the list
                    val firstIndex = cleanedList.indexOf(fileName)
                    if (firstIndex != i) {
                        // If found earlier, remove this duplicate
                        cleanedList.removeAt(i)
                    }
                }
            }

            prefManager.saveFileList(cleanedList)
            val closeText = dialogView.findViewById<TextView>(R.id.close_progress_statement)
            closeText.visibility = View.VISIBLE

            for (i in 3 downTo 1) {
                withContext(Dispatchers.Main) {
                    closeText.text = "Dialog tertutup otomatis dalam ${i} detik"
                    delay(1000)
                }
            }

            alertDialog.dismiss()
            onDownloadComplete()
        }
    }

    private suspend fun downloadFile(
        fileName: String,
        endpoint: String,
        downloadsDir: File?,
        fileList: MutableList<String?>
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val response = VolleyApiService.downloadFile(context, endpoint).await()
            val file = File(downloadsDir, fileName)

            FileOutputStream(file).use { outputStream ->
                outputStream.write(response)
            }

            fileList.add(fileName)
            Pair(true, "Unduh Selesai")
        } catch (e: Exception) {
            Log.e("FileDownload", "Error: ${e.message}")
            fileList.add(null)
            Pair(false, "Unduh Gagal! ${e.message ?: "Unknown error"}")
        }
    }
    
    suspend fun shouldStartFileDownload(): Boolean {
        val savedFileList = prefManager.getFileList()
        val downloadsDir = context.getExternalFilesDir(null)

        if (prefManager.isFirstTimeLaunch) {
            Log.d("FileCheck", "First time launch detected.")
            prefManager.isFirstTimeLaunch = false
            return true
        }

        if (savedFileList.isNotEmpty()) {
            if (savedFileList.contains(null)) {
                Log.e("FileCheck", "Null entries found in savedFileList.")
                return true
            }

            val missingFiles = savedFileList.filterNot { fileName ->
                val file = File(downloadsDir, fileName)
                val exists = file.exists()
                Log.d("FileCheck", "Checking file: ${file.path} -> Exists: $exists")
                fileName != null && exists
            }

            if (missingFiles.isNotEmpty()) {
                Log.e("FileCheck", "Missing files detected: $missingFiles")
                return true
            }
        } else {
            Log.d("FileCheck", "Saved file list is empty.")
            return true
        }

        if (!isInternetAvailable()) {
            Log.d("NetworkCheck", "No internet connection available")
            filesToUpdate.clear()  // Clear any pending updates
            return false
        }

        val shouldDownload = checkServerDates()
        Log.d("ServerDates", "Files to update: $filesToUpdate")
        Log.d("ServerDates", "Should download: $shouldDownload")

        return shouldDownload
    }

    private suspend fun checkServerDates(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("ServerDates", "Starting server date check")
            filesToUpdate.clear()
            Log.d("ServerDates", "Cleared filesToUpdate list")

            val response = VolleyApiService.makeRequest(
                context,
                "getTablesLatestModified"
            ).await()
            Log.d("ServerDates", "API Response received: $response")

            if (response.getInt("statusCode") == 1) {
                val serverData = response.getJSONObject("data")
                Log.d("ServerDates", "Server data: $serverData")

                val localData = prefManager.getAllDateModified()
                Log.d("ServerDates", "Local data: $localData")

                if (localData == null) {
                    Log.d("ServerDates", "Local data is null, returning true")
                    return@withContext true
                }

                val keyMapping = mapOf(
                    AppUtils.ApiCallManager.apiCallList[0].first to Pair("regional", "RegionalDB"),
                    AppUtils.ApiCallManager.apiCallList[1].first to Pair("wilayah", "WilayahDB"),
                    AppUtils.ApiCallManager.apiCallList[2].first to Pair("dept", "DeptDB"),
                    AppUtils.ApiCallManager.apiCallList[3].first to Pair("divisi", "DivisiDB"),
                    AppUtils.ApiCallManager.apiCallList[4].first to Pair("blok", "BlokDB"),
                    AppUtils.ApiCallManager.apiCallList[5].first to Pair("tph", "TPHDB")
                )
                Log.d("ServerDates", "Key mapping created: $keyMapping")

                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                keyMapping.forEach { (filename, keys) ->
                    Log.d("ServerDates", "Checking file: $filename")
                    val (serverKey, localKey) = keys
                    val serverDate = serverData.optString(serverKey)
                    val localDate = localData[localKey]

                    Log.d("ServerDates", "Comparing dates for $filename:")
                    Log.d("ServerDates", "Server date ($serverKey): $serverDate")
                    Log.d("ServerDates", "Local date ($localKey): $localDate")

                    if (serverDate.isNotEmpty() && localDate != null) {
                        val serverDateTime = dateFormat.parse(serverDate)
                        val localDateTime = dateFormat.parse(localDate)

                        Log.d("ServerDates", "Parsed dates - Server: $serverDateTime, Local: $localDateTime")

                        if (serverDateTime != null && localDateTime != null &&
                            serverDateTime.after(localDateTime)) {
                            Log.d("ServerDates", "Update needed for $filename")
                            filesToUpdate.add(filename)
                        }
                    }
                }

                Log.d("ServerDates", "Final filesToUpdate list: $filesToUpdate")
                return@withContext filesToUpdate.isNotEmpty()
            }

            Log.d("ServerDates", "Status code not 1, returning true")
            return@withContext true
        } catch (e: Exception) {
            Log.e("ServerDates", "Error checking server dates", e)
            Log.e("ServerDates", "Stack trace: ${e.stackTrace}")
            return@withContext true
        }
    }
} 