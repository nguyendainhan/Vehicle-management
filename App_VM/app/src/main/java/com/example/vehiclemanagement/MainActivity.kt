package com.example.vehiclemanagement

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.vehiclemanagement.Model.*
import com.example.vehiclemanagement.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    // Scanner state variables
    private var lastScannedPlate: String = ""
    private var lastScanTime: Long = 0
    private val DUPLICATE_TIMEOUT = 5000L
    private var isProcessing = false

    // --- MODE SWITCH ---
    private var isAddMode = false // false = Check mode, true = Add mode

    // Regex for License Plate
    private val plateRegex = Regex("([0-9]{2}[A-Z][0-9A-Z]?|[A-Z]{3})[-]?[0-9]{3,5}")

    // ImageCapture (For taking photos)
    private var imageCapture: ImageCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Button: Manual Input
        binding.btnManualInput.setOnClickListener {
            showManualCheckDialog()
        }

        // 2. Button: Add New Vehicle
        binding.btnAddVehicle.setOnClickListener {
            showAddMethodSelectionDialog()
        }

        // 3. Button: Capture & Scan
        binding.btnCapture.setOnClickListener {
            captureAndScan()
        }

        // 4. Button: History (Fetch from API)
        binding.btnHistory.setOnClickListener {
            fetchHistoryFromApi()
        }

        // 5. Button: Allowed List (Fetch from API + Delete)
        binding.btnList.setOnClickListener {
            showAllowedListDialog()
        }

        // Click status to reset
        binding.tvStatus.setOnClickListener {
            resetScannerState()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        requestPermission()
    }

    // --- LOGIC: SELECT ADD METHOD ---
    private fun showAddMethodSelectionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Add New Vehicle")
            .setMessage("Choose input method:")
            .setPositiveButton("📷 Scan Camera") { _, _ ->
                isAddMode = true
                isProcessing = false
                lastScannedPlate = ""
                binding.tvStatus.text = "🟡 SCAN PLATE TO ADD..."
                binding.tvStatus.setTextColor(Color.YELLOW)
                Toast.makeText(this, "Point camera at the plate", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("⌨️ Type Manually") { _, _ ->
                showAddVehicleForm("")
            }
            .setNeutralButton("Cancel", null)
            .show()
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

            // Analyzer for continuous scanning
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { imageProxy -> processImageProxy(imageProxy) } }

            // Capture for high-res photo
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY) // 1. Ưu tiên chất lượng ảnh cao nhất
                .setFlashMode(ImageCapture.FLASH_MODE_AUTO)                 // 2. Tự động bật đèn Flash nếu tối
                .build()

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer,
                    imageCapture
                )
                val factory = binding.viewFinder.meteringPointFactory
                val point = factory.createPoint(binding.viewFinder.width / 2f, binding.viewFinder.height / 2f)
                val action = FocusMeteringAction.Builder(point).build()
                camera.cameraControl.startFocusAndMetering(action)

                camera.cameraControl.setLinearZoom(0.5f)
            } catch (exc: Exception) {
                Log.e("CAMERA", "Binding failed", exc)
            }
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

    // --- MAIN LOGIC: PROCESS TEXT ---
    private fun processDetectedText(visionText: com.google.mlkit.vision.text.Text) {
        if (isProcessing) return

        for (block in visionText.textBlocks) {
            // Filter out small text noise
            if (block.boundingBox != null && block.boundingBox!!.height() < 20) continue

            for (line in block.text.split("\n")) {
                val cleanLine = normalizePlate(line)
                val matchResult = plateRegex.find(cleanLine)

                if (matchResult != null) {
                    var foundPlate = matchResult.value
                    // Formatting: Ensure hyphen (ABC1234 -> ABC-1234)
                    if (!foundPlate.contains("-")) {
                        if (foundPlate.matches(Regex("^[A-Z]{3}[0-9]+$"))) {
                            foundPlate = foundPlate.substring(0, 3) + "-" + foundPlate.substring(3)
                        }
                    }

                    // 1. ADD MODE
                    if (isAddMode) {
                        isProcessing = true
                        runOnUiThread { showAddVehicleForm(foundPlate) }
                        return
                    }

                    // 2. CHECK MODE
                    val currentTime = System.currentTimeMillis()
                    if (foundPlate == lastScannedPlate && (currentTime - lastScanTime) < DUPLICATE_TIMEOUT) return

                    lastScannedPlate = foundPlate
                    lastScanTime = currentTime
                    isProcessing = true

                    runOnUiThread {
                        binding.tvPlate.text = foundPlate
                        binding.tvStatus.text = "Checking Server..."
                        binding.tvStatus.setTextColor(Color.CYAN)
                        checkPlateWithServer(foundPlate)
                    }
                    return
                }
            }
        }
    }

    private fun normalizePlate(input: String): String {
        var text = input.uppercase().replace(" ", "").replace(".", "-").replace("_", "-")
        // Fix common OCR errors
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
                        binding.tvPlate.text = plate
                        if (result.allowed) {
                            binding.tvStatus.text = "✅ ALLOWED ($action)"
                            binding.tvStatus.setTextColor(Color.GREEN)
                        } else {
                            binding.tvStatus.text = "❌ DENIED ($action)"
                            binding.tvStatus.setTextColor(Color.RED)
                        }
                    }
                } else {
                    runOnUiThread {
                        binding.tvStatus.text = "❌ DENIED (Not Found)"
                        binding.tvStatus.setTextColor(Color.RED)
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

    // --- API: FETCH HISTORY ---
    private fun fetchHistoryFromApi() {
        Toast.makeText(this, "⏳ Loading history...", Toast.LENGTH_SHORT).show()
        RetrofitClient.instance.getScanHistory().enqueue(object : Callback<List<ScanHistoryItem>> {
            override fun onResponse(call: Call<List<ScanHistoryItem>>, response: Response<List<ScanHistoryItem>>) {
                if (response.isSuccessful && response.body() != null) {
                    runOnUiThread { showHistoryDialog(response.body()!!) }
                } else {
                    runOnUiThread { Toast.makeText(this@MainActivity, "No data found", Toast.LENGTH_SHORT).show() }
                }
            }
            override fun onFailure(call: Call<List<ScanHistoryItem>>, t: Throwable) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Error: ${t.message}", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    // --- UI: SHOW HISTORY DIALOG ---
    private fun showHistoryDialog(dataList: List<ScanHistoryItem>) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("🕒 Scan History")

        val listView = ListView(this)
        val adapter = object : ArrayAdapter<ScanHistoryItem>(this, android.R.layout.simple_list_item_2, android.R.id.text1, dataList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val text1 = view.findViewById<TextView>(android.R.id.text1)
                val text2 = view.findViewById<TextView>(android.R.id.text2)

                val item = getItem(position)
                if (item != null) {
                    text1.text = "${item.plate} - ${item.action}"
                    text1.textSize = 16f
                    text1.setTypeface(null, android.graphics.Typeface.BOLD)

                    // Format Timestamp string from server
                    val displayTime = try {
                        // Adjust this format based on exactly what your Node.js sends
                        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
                        inputFormat.timeZone = TimeZone.getTimeZone("UTC")
                        val date = inputFormat.parse(item.time)
                        SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(date!!)
                    } catch (e: Exception) {
                        item.time // Fallback to raw string
                    }

                    if (item.status == "ALLOW") {
                        text1.setTextColor(Color.parseColor("#008000")) // Green
                        text2.text = "✅ Approved - $displayTime"
                    } else {
                        text1.setTextColor(Color.RED)
                        text2.text = "❌ Denied - $displayTime"
                    }
                }
                return view
            }
        }
        listView.adapter = adapter
        builder.setView(listView)
        builder.setPositiveButton("Close", null)
        builder.show()
    }

    // --- API: SHOW LIST & DELETE ---
    private fun showAllowedListDialog() {
        Toast.makeText(this, "Refreshing list...", Toast.LENGTH_SHORT).show()
        RetrofitClient.instance.getVehicles().enqueue(object : Callback<List<Vehicle>> {
            override fun onResponse(call: Call<List<Vehicle>>, response: Response<List<Vehicle>>) {
                if (response.isSuccessful && response.body() != null) {
                    val vehicleList = response.body()!!
                    val builder = AlertDialog.Builder(this@MainActivity)
                    builder.setTitle("📋 Registered Vehicles (${vehicleList.size})")

                    val listView = ListView(this@MainActivity)
                    val adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_list_item_1,
                        vehicleList.map { "${it.plateNumber} - ${it.ownerName ?: "Unknown"}" }
                    )
                    listView.adapter = adapter

                    // DELETE ON LONG PRESS
                    listView.setOnItemLongClickListener { _, _, position, _ ->
                        val selectedVehicle = vehicleList[position]
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Delete Vehicle?")
                            .setMessage("Remove plate ${selectedVehicle.plateNumber}?")
                            .setPositiveButton("Delete") { _, _ -> deleteVehicleFromServer(selectedVehicle.id) }
                            .setNegativeButton("Cancel", null)
                            .show()
                        true
                    }

                    builder.setView(listView)
                    builder.setPositiveButton("Close", null)
                    builder.show()
                }
            }
            override fun onFailure(call: Call<List<Vehicle>>, t: Throwable) {
                Toast.makeText(this@MainActivity, "Failed to load list", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun deleteVehicleFromServer(id: Int) {
        RetrofitClient.instance.deleteVehicle(id).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) {
                    Toast.makeText(this@MainActivity, "✅ Vehicle Deleted!", Toast.LENGTH_SHORT).show()
                    showAllowedListDialog() // Refresh list
                } else {
                    Toast.makeText(this@MainActivity, "❌ Delete Failed", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(call: Call<Void>, t: Throwable) {
                Toast.makeText(this@MainActivity, "Network Error", Toast.LENGTH_SHORT).show()
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

    // --- UI: ADD FORM ---
    private fun showAddVehicleForm(preFilledPlate: String) {
        val context = this
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)

        val inputPlate = EditText(context)
        inputPlate.hint = "Plate Number (e.g. 59A-12345)"
        inputPlate.setText(preFilledPlate)
        layout.addView(inputPlate)

        AlertDialog.Builder(context)
            .setTitle("Add New Vehicle")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val plate = inputPlate.text.toString().uppercase().trim()
                if (plate.isNotEmpty()) {
                    addNewVehicleToServer(plate)
                } else {
                    Toast.makeText(context, "Please enter a plate number", Toast.LENGTH_SHORT).show()
                }
                // Reset state
                isAddMode = false
                isProcessing = false
                binding.tvStatus.text = "Ready to scan..."
                binding.tvStatus.setTextColor(Color.WHITE)
            }
            .setNegativeButton("Cancel") { _, _ ->
                isAddMode = false
                isProcessing = false
                binding.tvStatus.text = "Ready to scan..."
                binding.tvStatus.setTextColor(Color.WHITE)
            }
            .setCancelable(false)
            .show()
    }

    // --- CAMERA: CAPTURE PHOTO ---
    private fun captureAndScan() {
        val imageCapture = imageCapture ?: return

        binding.tvStatus.text = "📸 Processing High-Res Image..."
        binding.tvStatus.setTextColor(Color.CYAN)

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val bitmap = imageProxyToBitmap(imageProxy)
                    imageProxy.close()

                    if (bitmap != null) {
                        val cropWidth = (bitmap.width * 0.8).toInt()
                        val cropHeight = (bitmap.height * 0.3).toInt()
                        val startX = (bitmap.width - cropWidth) / 2
                        val startY = (bitmap.height - cropHeight) / 2
                        val croppedBitmap = Bitmap.createBitmap(bitmap, startX, startY, cropWidth, cropHeight)
                        val image = InputImage.fromBitmap(croppedBitmap, 0)

                        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
                            .addOnSuccessListener { visionText ->
                                processDetectedText(visionText)
                            }
                            .addOnFailureListener {
                                Toast.makeText(baseContext, "Scan Failed", Toast.LENGTH_SHORT).show()
                            }
                    } else {
                        Toast.makeText(baseContext, "Bitmap Error", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CAMERA", "Capture failed", exception)
                }
            }
        )
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val matrix = android.graphics.Matrix()
        matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // --- UI: MANUAL INPUT ---
    private fun showManualCheckDialog() {
        val inputEdit = EditText(this)
        inputEdit.hint = "Example: 59A-12345"
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
            binding.tvStatus.setTextColor(Color.WHITE)
        }, 3000)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}