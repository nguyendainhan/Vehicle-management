package com.example.vehiclemanagement

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.vehiclemanagement.Model.ScanRequest
import com.example.vehiclemanagement.Model.ScanResponse
import com.example.vehiclemanagement.Model.Vehicle
import com.example.vehiclemanagement.Model.AddVehicleRequest // <--- IMPORT QUAN TRỌNG
import com.example.vehiclemanagement.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    // Offline list
    private var allowedPlateList: List<String> = ArrayList()
    private var isListLoaded = false

    // Scanner state variables
    private var lastScannedPlate: String = ""
    private var lastScanTime: Long = 0
    private val DUPLICATE_TIMEOUT = 5000L
    private var isProcessing = false

    // --- MODE SWITCH: CHECKING vs ADDING ---
    private var isAddMode = false // false = Check mode, true = Add mode

    // Regex for License Plate
    private val plateRegex = Regex("([0-9]{2}[A-Z][0-9A-Z]?|[A-Z]{3})[-]?[0-9]{3,5}")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load data on startup
        fetchAllowedVehicles()

        // 1. Button: Check Manually (Kiểm tra xe thủ công)
        binding.btnManualInput.setOnClickListener {
            showManualCheckDialog()
        }

        // 2. Button: Add New Vehicle (Thêm xe mới)
        binding.btnAddVehicle.setOnClickListener {
            showAddMethodSelectionDialog()
        }

        // Click status text to refresh list
        binding.tvStatus.setOnClickListener {
            fetchAllowedVehicles()
            Toast.makeText(this, "Updating list...", Toast.LENGTH_SHORT).show()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        requestPermission()
    }

    // --- LOGIC: CHỌN CÁCH THÊM XE ---
    private fun showAddMethodSelectionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Add New Vehicle")
            .setMessage("Choose input method:")
            .setPositiveButton("📷 Scan Camera") { _, _ ->
                // Switch to ADD MODE
                isAddMode = true
                isProcessing = false // Unlock camera
                lastScannedPlate = "" // Reset history

                binding.tvStatus.text = "🟡 SCAN PLATE TO ADD..."
                binding.tvStatus.setTextColor(android.graphics.Color.YELLOW)
                Toast.makeText(this, "Point camera at the plate", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("⌨️ Type Manually") { _, _ ->
                // Open dialog immediately with empty plate
                showAddVehicleForm("")
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    // --- LOGIC: FETCH DATA ---
    private fun fetchAllowedVehicles() {
        RetrofitClient.instance.getVehicles().enqueue(object : Callback<List<Vehicle>> {
            override fun onResponse(call: Call<List<Vehicle>>, response: Response<List<Vehicle>>) {
                if (response.isSuccessful && response.body() != null) {
                    allowedPlateList = response.body()!!.map {
                        it.plateNumber.uppercase().replace(" ", "").replace(".", "-")
                    }
                    isListLoaded = true
                    Log.d("DATA_SYNC", "Downloaded ${allowedPlateList.size} plates.")
                    runOnUiThread {
                        binding.tvStatus.text = "Synced ${allowedPlateList.size} vehicles."
                    }
                }
            }
            override fun onFailure(call: Call<List<Vehicle>>, t: Throwable) {
                Log.e("DATA_SYNC", "Error: ${t.message}")
            }
        })
    }

    // --- CAMERA SETUP ---
    private fun requestPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if(it) startCamera() }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }
            val imageAnalyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                .also { it.setAnalyzer(cameraExecutor) { imageProxy -> processImageProxy(imageProxy) } }
            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
                camera.cameraControl.setLinearZoom(0.4f)
            } catch (exc: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
                .addOnSuccessListener { processDetectedText(it) }
                .addOnCompleteListener { imageProxy.close() }
        } else { imageProxy.close() }
    }

    // --- MAIN LOGIC ---
    private fun processDetectedText(visionText: com.google.mlkit.vision.text.Text) {
        if (isProcessing) return

        for (block in visionText.textBlocks) {
            if (block.boundingBox != null && block.boundingBox!!.height() < 20) continue

            for (line in block.text.split("\n")) {
                val cleanLine = normalizePlate(line)
                val matchResult = plateRegex.find(cleanLine)

                if (matchResult != null) {
                    var foundPlate = matchResult.value
                    // Fix hyphen logic
                    if (!foundPlate.contains("-")) {
                        if (foundPlate.matches(Regex("^[A-Z]{3}[0-9]+$"))) foundPlate = foundPlate.substring(0, 3) + "-" + foundPlate.substring(3)
                    }

                    // ============================================
                    // 1. ADD MODE (CHẾ ĐỘ THÊM XE)
                    // ============================================
                    if (isAddMode) {
                        isProcessing = true // Pause camera
                        runOnUiThread {
                            // Found a plate -> Open Add Form immediately
                            showAddVehicleForm(foundPlate)
                        }
                        return // Stop here
                    }

                    // ============================================
                    // 2. CHECK MODE (CHẾ ĐỘ KIỂM TRA XE RA/VÀO)
                    // ============================================
                    val currentTime = System.currentTimeMillis()
                    if (foundPlate == lastScannedPlate && (currentTime - lastScanTime) < DUPLICATE_TIMEOUT) return

                    if (isListLoaded) {
                        val plateForCheck = foundPlate.replace("-", "")
                        val isAllowed = allowedPlateList.any { it.replace("-", "") == plateForCheck }

                        if (isAllowed) {
                            lastScannedPlate = foundPlate
                            lastScanTime = currentTime
                            isProcessing = true
                            runOnUiThread {
                                binding.tvStatus.text = "Known vehicle: $foundPlate"
                                binding.tvStatus.setTextColor(android.graphics.Color.YELLOW)
                                checkPlateWithServer(foundPlate)
                            }
                        } else {
                            lastScannedPlate = foundPlate
                            lastScanTime = currentTime
                            runOnUiThread {
                                binding.tvPlate.text = "🔍 $foundPlate"
                                binding.tvStatus.text = "⛔ UNREGISTERED (OFFLINE)"
                                binding.tvStatus.setTextColor(android.graphics.Color.RED)
                                resetScannerState()
                            }
                        }
                    } else {
                        // Fallback if list not loaded
                        lastScannedPlate = foundPlate
                        isProcessing = true
                        checkPlateWithServer(foundPlate)
                    }
                    return
                }
            }
        }
    }

    private fun normalizePlate(input: String): String {
        var text = input.uppercase().replace(" ", "").replace(".", "-").replace("_", "-")
        text = text.replace("O", "0").replace("Q", "0").replace("I", "1").replace("L", "1")
        return Regex("[^A-Z0-9-]").replace(text, "")
    }

    // --- API: CHECK VEHICLE ---
    private fun checkPlateWithServer(plate: String) {
        val action = if (binding.rbIn.isChecked) "IN" else "OUT"
        val request = ScanRequest(plate = plate, action = action)
        RetrofitClient.instance.checkLicensePlate(request).enqueue(object : Callback<ScanResponse> {
            override fun onResponse(call: Call<ScanResponse>, response: Response<ScanResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val result = response.body()!!
                    runOnUiThread {
                        binding.tvPlate.text = "🔍 Plate: $plate"
                        if (result.allowed) {
                            binding.tvStatus.text = "✅ ALLOWED ($action)"
                            binding.tvStatus.setTextColor(android.graphics.Color.GREEN)
                        } else {
                            binding.tvStatus.text = "❌ DENIED ($action)"
                            binding.tvStatus.setTextColor(android.graphics.Color.RED)
                        }
                    }
                }
                resetScannerState()
            }
            override fun onFailure(call: Call<ScanResponse>, t: Throwable) {
                runOnUiThread { binding.tvStatus.text = "Network Error" }
                resetScannerState()
            }
        })
    }

    // --- API: ADD VEHICLE ---
    private fun addNewVehicleToServer(plate: String) {
        val request = AddVehicleRequest(plate)
        RetrofitClient.instance.addVehicle(request).enqueue(object : Callback<Vehicle> {
            override fun onResponse(call: Call<Vehicle>, response: Response<Vehicle>) {
                if (response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "✅ Added: $plate", Toast.LENGTH_LONG).show()
                        fetchAllowedVehicles() // Refresh list immediately
                    }
                } else {
                    runOnUiThread { Toast.makeText(this@MainActivity, "❌ Failed! Exists?", Toast.LENGTH_LONG).show() }
                }
            }
            override fun onFailure(call: Call<Vehicle>, t: Throwable) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Error: ${t.message}", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    // --- DIALOG: ADD VEHICLE FORM ---
    private fun showAddVehicleForm(preFilledPlate: String) {
        val context = this
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)

        val inputPlate = EditText(context)
        inputPlate.hint = "Plate Number (e.g. 59A-12345)"
        inputPlate.setText(preFilledPlate) // Auto-fill if scanned
        layout.addView(inputPlate)

        AlertDialog.Builder(context)
            .setTitle("Add New Vehicle")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val plate = inputPlate.text.toString().uppercase().trim()
                if (plate.isNotEmpty() ) {
                    addNewVehicleToServer(plate)
                } else {
                    Toast.makeText(context, "Enter full details!", Toast.LENGTH_SHORT).show()
                }

                // Exit Add Mode
                isAddMode = false
                isProcessing = false
                binding.tvStatus.text = "Ready to scan..."
                binding.tvStatus.setTextColor(android.graphics.Color.WHITE)
            }
            .setNegativeButton("Cancel") { _, _ ->
                isAddMode = false
                isProcessing = false
                binding.tvStatus.text = "Ready to scan..."
                binding.tvStatus.setTextColor(android.graphics.Color.WHITE)
            }
            .setCancelable(false) // User must click buttons
            .show()
    }

    // --- DIALOG: MANUAL CHECK (Kiểm tra tay) ---
    private fun showManualCheckDialog() {
        val inputEdit = EditText(this)
        inputEdit.hint = "Example: RAM-8159"
        AlertDialog.Builder(this)
            .setTitle("Manual Check")
            .setView(inputEdit)
            .setPositiveButton("Check") { _, _ ->
                val text = inputEdit.text.toString().uppercase().trim()
                if (text.isNotEmpty()) {
                    isProcessing = true
                    lastScannedPlate = text
                    lastScanTime = System.currentTimeMillis()
                    checkPlateWithServer(text)
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun resetScannerState() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            isProcessing = false
            binding.tvPlate.text = "---"
            binding.tvStatus.text = "Ready to scan..."
            binding.tvStatus.setTextColor(android.graphics.Color.WHITE)
        }, 3000)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}