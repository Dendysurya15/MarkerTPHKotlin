package com.cbi.markertph.utils

import android.content.Context
import android.util.Log
import com.cbi.markertph.data.model.*
import com.cbi.markertph.utils.AppUtils.stringXML
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

class DataLoaderHelper(
    private val context: Context,
    private val dataCacheManager: DataCacheManager,
    private val prefManager: PrefManager,
    private val loadingDialogCallback: (Boolean, String) -> Unit
) {
    // Cache for datasets
    private var regionalList: List<RegionalModel> = emptyList()
    private var wilayahList: List<WilayahModel> = emptyList()
    private var deptList: List<DeptModel> = emptyList()
    private var divisiList: List<DivisiModel> = emptyList()
    private var blokList: List<BlokModel> = emptyList()
    private var tphList: List<TPHNewModel>? = null // Lazy-loaded

    companion object {
        private const val CHUNK_SIZE = 8192 // 8KB chunks
        private const val DEFAULT_BUFFER_SIZE = 8192 * 4 // Increased buffer size for better performance
        private const val TAG = "DataLoaderHelper"
    }

    // Method to get loaded data
    fun getLoadedData(): DatasetBundle {
        return DatasetBundle(
            regionalList,
            wilayahList,
            deptList,
            divisiList,
            blokList,
            tphList ?: emptyList()
        )
    }

    suspend fun loadAllFilesAsync() {
        val filesToDownload = AppUtils.ApiCallManager.apiCallList.map { it.first }
        ReleaseLogger.d("LoadFiles", "Starting to load files: ${filesToDownload.joinToString()}")

        loadingDialogCallback(true, context.stringXML(com.cbi.markertph.R.string.fetching_dataset))
        
        try {
            withContext(Dispatchers.IO) {
                filesToDownload.forEachIndexed { index, fileName ->
                    ReleaseLogger.d("LoadFiles", "Processing file $fileName (${index + 1}/${filesToDownload.size})")
                    val file = File(context.getExternalFilesDir(null), fileName)
                    if (file.exists()) {
                        ReleaseLogger.d("LoadFiles", "File exists: ${file.length()} bytes")
                        decompressFile(file, index == filesToDownload.lastIndex)
                    } else {
                        ReleaseLogger.e("LoadFiles", "File not found: $fileName")
                    }
                }
            }

            ReleaseLogger.d("LoadFiles", """
                Data loaded:
                - Regionals: ${regionalList.size}
                - Wilayah: ${wilayahList.size}
                - Dept: ${deptList.size}
                - Divisi: ${divisiList.size}
                - Blok: ${blokList.size}
                - TPH: ${tphList?.size ?: 0}
            """.trimIndent())

            dataCacheManager.saveDatasets(
                regionalList,
                wilayahList,
                deptList,
                divisiList,
                blokList,
                tphList!!
            )
        } catch (e: Exception) {
            ReleaseLogger.e("LoadFiles", "Error loading files", e)
        } finally {
            loadingDialogCallback(false, "")
        }
    }

    private fun decompressFile(file: File, isLastFile: Boolean) {
        try {
            ReleaseLogger.d("Decompress", "Starting decompression of ${file.name}")

            when (file.name) {
                "datasetTPH.zip" -> {
                    ReleaseLogger.d("Decompress", "Processing large TPH file")
                    handleLargeFileChunked(file, isLastFile)
                }
                else -> {
                    GZIPInputStream(file.inputStream()).use { gzipInputStream ->
                        val decompressedData = gzipInputStream.readBytes()
                        ReleaseLogger.d("Decompress", "Decompressed ${file.name}: ${decompressedData.size} bytes")
                        val jsonString = String(decompressedData, Charsets.UTF_8)
                        parseJsonData(jsonString, isLastFile)
                    }
                }
            }
        } catch (e: Exception) {
            ReleaseLogger.e("Decompress", "Error processing ${file.name}", e)
            throw e
        }
    }

    private fun handleLargeFileChunked(file: File, isLastFile: Boolean) {
        try {
            Log.d("HandleLargeFile", "Starting chunked processing of: ${file.name}")
            val startTime = System.currentTimeMillis()

            // Create a temporary file to store decompressed data
            val tempFile = File(file.parent, "temp_decompressed.json")

            // Step 1: Decompress the file
            GZIPInputStream(file.inputStream().buffered(DEFAULT_BUFFER_SIZE)).use { gzipInputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    var totalBytes = 0L
                    var bytesRead: Int

                    while (gzipInputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        if (totalBytes % (10 * 1024 * 1024) == 0L) {
                            Log.d("HandleLargeFile", "Decompressed: ${totalBytes / (1024 * 1024)} MB")
                        }
                    }
                    Log.d("HandleLargeFile", "Total decompressed size: ${totalBytes / (1024 * 1024)} MB")
                }
            }

            // Step 2: Read and parse in a controlled way
            try {
                tempFile.inputStream().bufferedReader().use { reader ->
                    val jsonContent = reader.readText()
                    Log.d("HandleLargeFile", "JSON loaded into memory, size: ${jsonContent.length} chars")

                    val jsonObject = JSONObject(jsonContent)
                    Log.d("HandleLargeFile", "JSON successfully parsed")

                    // Check if this is TPH data
                    if (jsonObject.has("TPHDB")) {
                        Log.d("HandleLargeFile", "Found TPHDB")
                        // Process TPH data directly
                        loadTPHData(jsonObject)
                    } else {
                        // Process other data
                        Log.d("HandleLargeFile", "Processing regular data")
                        parseJsonData(jsonContent, isLastFile)
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.e("HandleLargeFile", "OutOfMemoryError: ${e.message}")
                System.gc() // Request garbage collection
                e.printStackTrace()
            } catch (e: JSONException) {
                Log.e("HandleLargeFile", "JSON parsing error: ${e.message}")
                e.printStackTrace()
            }

            // Clean up
            tempFile.delete()

            val endTime = System.currentTimeMillis()
            Log.d("HandleLargeFile", "Total processing time: ${endTime - startTime} ms")

        } catch (e: Exception) {
            Log.e("HandleLargeFile", "Error in processing: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun parseJsonData(jsonString: String, isLastFile: Boolean) {
        try {
            val jsonObject = JSONObject(jsonString)
            val gson = Gson()

            val keyObject = jsonObject.getJSONObject("key")
            val dateModified = jsonObject.getString("date_modified")

            // Parse RegionalDB
            if (jsonObject.has("RegionalDB")) {
                val companyCodeArray = jsonObject.getJSONArray("RegionalDB")

                val dateModified = jsonObject.getString("date_modified")
                val transformedCompanyCodeArray = transformJsonArray(companyCodeArray, keyObject)
                val regionalList: List<RegionalModel> = gson.fromJson(
                    transformedCompanyCodeArray.toString(),
                    object : TypeToken<List<RegionalModel>>() {}.type
                )

                prefManager.setDateModified("RegionalDB", dateModified) // Store dynamically

                this.regionalList = regionalList
            } else {
                Log.e("ParseJsonData", "RegionalDB key is missing")
            }

            // Parse WilayahDB
            if (jsonObject.has("WilayahDB")) {
                val companyCodeArray = jsonObject.getJSONArray("WilayahDB")
                val transformedCompanyCodeArray = transformJsonArray(companyCodeArray, keyObject)
                val wilayahList: List<WilayahModel> = gson.fromJson(
                    transformedCompanyCodeArray.toString(),
                    object : TypeToken<List<WilayahModel>>() {}.type
                )
                Log.d("ParsedData", "WilayahDB: $wilayahList")
                this.wilayahList = wilayahList
                prefManager.setDateModified("WilayahDB", dateModified) // Store dynamically
            } else {
                Log.e("ParseJsonData", "WilayahDB key is missing")
            }

            if (jsonObject.has("DeptDB")) {
                val bUnitCodeArray = jsonObject.getJSONArray("DeptDB")
                val transformedBUnitCodeArray = transformJsonArray(bUnitCodeArray, keyObject)
                val deptList: List<DeptModel> = gson.fromJson(
                    transformedBUnitCodeArray.toString(),
                    object : TypeToken<List<DeptModel>>() {}.type
                )
                Log.d("ParsedData", "BUnitCode: $deptList")
                this.deptList = deptList
                prefManager.setDateModified("DeptDB", dateModified) // Store dynamically
            } else {
                Log.e("ParseJsonData", "DeptDB key is missing")
            }

            // Parse DivisionCodeDB
            if (jsonObject.has("DivisiDB")) {
                val divisionCodeArray = jsonObject.getJSONArray("DivisiDB")
                val transformedDivisionCodeArray = transformJsonArray(divisionCodeArray, keyObject)
                val divisiList: List<DivisiModel> = gson.fromJson(
                    transformedDivisionCodeArray.toString(),
                    object : TypeToken<List<DivisiModel>>() {}.type
                )
                Log.d("ParsedData", "DivisionCode: $divisiList")
                this.divisiList = divisiList
                prefManager.setDateModified("DivisiDB", dateModified) // Store dynamically
            } else {
                Log.e("ParseJsonData", "DivisiDB key is missing")
            }

            // Parse BlokDB
            if (jsonObject.has("BlokDB")) {
                val fieldCodeArray = jsonObject.getJSONArray("BlokDB")
                val transformedFieldCodeArray = transformJsonArrayInChunks(fieldCodeArray, keyObject)
                val blokList: List<BlokModel> = gson.fromJson(
                    transformedFieldCodeArray.toString(),
                    object : TypeToken<List<BlokModel>>() {}.type
                )
                Log.d("ParsedData", "FieldCode: $blokList")
                this.blokList = blokList
                prefManager.setDateModified("BlokDB", dateModified) // Store dynamically
            } else {
                Log.e("ParseJsonData", "BlokDB key is missing")
            }

        } catch (e: JSONException) {
            Log.e("ParseJsonData", "Error parsing JSON: ${e.message}")
        }
    }

    private fun loadTPHData(jsonObject: JSONObject) {
        try {
            if (jsonObject.has("TPHDB")) {
                Log.d("testing", "masuk sini ges")
                val tphArray = jsonObject.getJSONArray("TPHDB")
                val keyObject = jsonObject.getJSONObject("key")
                val chunkSize = 50 // Adjust the chunk size as needed

                // Create a mutable list to accumulate all TPHNewModel objects
                val accumulatedTPHList = mutableListOf<TPHNewModel>()

                // Process the TPHDB array in chunks
                for (i in 0 until tphArray.length() step chunkSize) {
                    val chunk = JSONArray()
                    for (j in i until (i + chunkSize).coerceAtMost(tphArray.length())) {
                        chunk.put(tphArray.getJSONObject(j))
                    }

                    // Transform the current chunk
                    val transformedChunk = transformJsonArray(chunk, keyObject)

                    // Parse the transformed chunk into a list of TPHNewModel
                    val chunkList: List<TPHNewModel> = Gson().fromJson(
                        transformedChunk.toString(),
                        object : TypeToken<List<TPHNewModel>>() {}.type
                    )

                    accumulatedTPHList.addAll(chunkList)

                    Log.d("LoadTPHData", "Processed chunk: $i to ${(i + chunkSize - 1).coerceAtMost(tphArray.length() - 1)}")
                }

                // Assign the accumulated list to the lazy-loaded tphList variable
                this.tphList = accumulatedTPHList
                val dateModified = jsonObject.getString("date_modified")

                prefManager.setDateModified("TPHDB", dateModified) // Store dynamically
                Log.d("ParsedData", "Total TPHDB items: ${tphList?.size ?: 0}")
            } else {
                Log.e("LoadTPHData", "TPHDB key is missing")
            }
        } catch (e: JSONException) {
            Log.e("LoadTPHData", "Error processing TPH data: ${e.message}")
        }
    }

    fun transformJsonArray(jsonArray: JSONArray, keyObject: JSONObject): JSONArray {
        val transformedArray = JSONArray()

        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            val transformedItem = JSONObject()

            keyObject.keys().forEach { key ->
                val fieldName = keyObject.getString(key)  // This gets the field name from the key object
                val fieldValue = item.get(key)  // This gets the corresponding value from the item
                transformedItem.put(fieldName, fieldValue)
            }

            transformedArray.put(transformedItem)
        }

        return transformedArray
    }

    fun transformJsonArrayInChunks(jsonArray: JSONArray, keyObject: JSONObject): JSONArray {
        val transformedArray = JSONArray()
        val chunkSize = 30 // Adjust this based on your needs

        try {
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val transformedItem = JSONObject()

                keyObject.keys().forEach { key ->
                    val fieldName = keyObject.getString(key)
                    val fieldValue = item.get(key)
                    transformedItem.put(fieldName, fieldValue)
                }

                transformedArray.put(transformedItem)

                // After each chunk is processed, suggest garbage collection
                if (i % chunkSize == 0) {
                    System.gc()
                }
            }
        } catch (e: Exception) {
            Log.e("Transform", "Error transforming array: ${e.message}")
        }

        return transformedArray
    }

    // Data class to hold loaded datasets
    data class DatasetBundle(
        val regionalList: List<RegionalModel>,
        val wilayahList: List<WilayahModel>,
        val deptList: List<DeptModel>,
        val divisiList: List<DivisiModel>,
        val blokList: List<BlokModel>,
        val tphList: List<TPHNewModel>
    )
} 