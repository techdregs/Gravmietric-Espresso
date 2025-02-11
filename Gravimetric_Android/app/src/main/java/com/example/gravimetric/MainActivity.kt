package com.example.gravimetric

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.example.gravimetric.ui.theme.GravimetricTheme
import androidx.activity.viewModels
import android.net.Uri

@ExperimentalMaterial3Api
class MainActivity : ComponentActivity(), ScaleBleManagerCallback {

    private var massReading by mutableStateOf("0.0 g")
    private var targetMassReading by mutableStateOf("40.0")  // Default value
    private var isConnected by mutableStateOf(false)
    private var logMessages by mutableStateOf(listOf<String>())
    private lateinit var bleManager: ScaleBleManager
    private val scaleViewModel: ScaleViewModel by viewModels()
    private var showExportDialog by mutableStateOf(false)


    //CSV Launcher
    private val createCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri == null) {
            logMessage("User canceled creating a new file.")
            return@registerForActivityResult
        }
        saveCsvToUri(uri, append = false)
    }

    private val openCsvLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            logMessage("User canceled choosing an existing file.")
            return@registerForActivityResult
        }
        saveCsvToUri(uri, append = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize the BLE manager and set our callback.
        bleManager = ScaleBleManager(this)
        bleManager.connectionObserver = bleManager
        bleManager.setScaleCallback(this)

        requestPermissions()
        scanForScale()

        setContent {
            GravimetricTheme {
                if (showExportDialog) {
                    ExportCsvDialog(
                        onDismiss = { showExportDialog = false },
                        onCreateNew = {
                            val fileName = "ShotLog_${System.currentTimeMillis()}.csv"
                            createCsvLauncher.launch(fileName)
                        },
                        onAppendExisting = {
                            openCsvLauncher.launch(arrayOf("text/csv"))
                        }
                    )
                }

                MainScreen(
                    mass = massReading,
                    targetMass = targetMassReading,
                    isConnected = isConnected,
                    logMessages = logMessages,
                    shotInProgress = scaleViewModel.shotInProgress,
                    liveMassReadings = scaleViewModel.massReadings,
                    shotLogReadings = scaleViewModel.shotLogEntries,
                    onStartShotLoggingClick = { handleStartShotLogging() },
                    onStopShotLoggingClick = { handleStopShotLogging() },
                    onReconnectClick = { scanForScale() },
                    onTareClick = { sendTareCommand() },
                    onStartShotClick = { startShotCycle() },
                    onStopShotClick = { stopShotCycle() },
                    onReadTargetMassClick = { readTargetMass() },
                    onWriteTargetMassClick = { newTargetMass -> writeTargetMass(newTargetMass) },
                    onExportCsvClick = { showExportDialog = true }
                )
            }
        }
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permResults ->
            permResults.forEach { (perm, granted) ->
                logMessage("$perm granted: $granted")
            }
            if (permResults.any { !it.value }) {
                Toast.makeText(this, "Not all permissions granted!", Toast.LENGTH_LONG).show()
            }
        }
        permissionLauncher.launch(permissions)
    }

    // Placeholder: Implement BLE scanning to find and connect to your scale.
    private fun scanForScale() {
        logMessage("Scanning for LoggingScale...")

        bleManager.scanForDevice { device ->
            logMessage("Scale found: ${device.address}. Connecting...")
            bleManager.connect(device).enqueue()
        }
    }

    // Placeholder: Implement sending a tare command.
    private fun sendTareCommand() {
        if (!isConnected) {
            Log.d("MainActivity", "Device not connected; cannot send tare command")
            logMessage("Device not connected; cannot send tare command")
            return
        }
        Log.d("MainActivity", "Sending tare command")
        bleManager.sendTareCommand()
        logMessage("Sent tare command")
    }

    private fun startShotCycle() {
        Log.d("MainActivity", "Toggling shot cycle not yet implemented")
        bleManager.sendStartCycleCommand()
        logMessage("Sent Shot Start command")
    }

    private fun stopShotCycle() {
        Log.d("MainActivity", "Toggling shot cycle not yet implemented")
        bleManager.sendStopCycleCommand()
        logMessage("Sent Shot Stop command")
    }

    private fun readTargetMass() {
        logMessage("Reading Target Mass from Scale...")
        bleManager.readTargetMass()
    }

    private fun writeTargetMass(newTargetMass: Float) {
        logMessage("Writing Target Mass: $newTargetMass g")
        bleManager.writeTargetMass(newTargetMass)
    }

    private fun logMessage(message: String) {
        logMessages = (logMessages + message).takeLast(10) // Keep last 10 messages
        Log.d("APP_LOG", message) // Also print to Logcat
    }

    private fun handleStartShotLogging() {
        if (!isConnected) {
            logMessage("Cannot start shot logging: device not connected.")
            return
        }
        // 1) Tare the scale
        sendTareCommand()

        // 2) Mark in the ViewModel that a shot has started
        scaleViewModel.startShot()

        // 3) Enable logging on the scale side.
        logMessage("Issuing log command.")
        bleManager.startLogging()

        // 4) Optionally start the actual shot cycle if you want the scale to brew
        bleManager.sendStartCycleCommand()

        logMessage("Shot logging started.")
    }

    private fun handleStopShotLogging() {
        if (!isConnected) {
            logMessage("Cannot stop shot logging: device not connected.")
            // But we can still mark shot as ended in the VM
        }
        logMessage("Shot logging ending in 5s.")

        // 1) Stop shot on scale (if needed):
        bleManager.sendStopCycleCommand()

        // 2) Mark in the ViewModel that shotInProgress = false
        scaleViewModel.stopShot()

        // 3) Wait 5 seconds, then finalize
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            scaleViewModel.endShotLogging()
            // If your Arduino needs a disable logging command:
            // bleManager.sendDisableLoggingCommand()
            bleManager.stopLogging()
            logMessage("Shot logging ended (5s after stop).")
        }, 5_000)
    }

    private fun saveCsvToUri(uri: Uri, append: Boolean) {
        try {
            val existing = if (append) {
                contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }.orEmpty()
            } else {
                ""
            }

            val newData = scaleViewModel.exportCsv(null)

            val combined = if (append) {
                if (existing.isEmpty()) newData
                else existing.trimEnd() + "\n" + newData
            } else {
                newData
            }

            //"wa" for appending and "wt" (or "w") for creating a new file.
            val mode = if (append) "wa" else "wt"

            contentResolver.openOutputStream(uri, mode)?.use { out ->
                out.write(combined.toByteArray())
            }
            logMessage("Saved CSV to $uri (append=$append)")
        } catch (e: Exception) {
            logMessage("Error saving CSV: ${e.message}")
        }
    }


    // BLE Callbacks
    override fun onMassUpdated(mass: Float) {
        runOnUiThread {
            massReading = "$mass g"
            scaleViewModel.addReading(mass, fromLogging = false)
            //logMessage("Mass updated: $mass g")
        }
    }

    override fun onLoggingDataReceived(mass: Float) {
        runOnUiThread {
            scaleViewModel.addReading(mass, fromLogging = true)
            //logMessage("Logging update: $mass g")
        }
    }


    override fun onTargetMassRead(targetMass: Float) {
        runOnUiThread {
            targetMassReading = targetMass.toString()
            logMessage("Target Mass Read: $targetMass g")
        }
    }

    override fun onDeviceConnecting(device: BluetoothDevice) {
        logMessage("Connecting to: ${device.address}...")
    }

    override fun onDeviceConnected(device: BluetoothDevice) {
        runOnUiThread {
            isConnected = true
            logMessage("Connected to: ${device.address}")
        }
    }

    override fun onDeviceReady(device: BluetoothDevice) {
        logMessage("Device ready: ${device.address}")
    }

    override fun onDeviceDisconnecting(device: BluetoothDevice) {
        logMessage("Disconnecting from: ${device.address}...")
    }

    override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
        runOnUiThread {
            isConnected = false
            logMessage("Disconnected from: ${device.address}, reason: $reason")
        }
    }

    override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
        logMessage("Failed to connect to: ${device.address}, reason: $reason")
    }

    override fun onShotStatusChanged(shotInProgress: Boolean) {
        runOnUiThread {
            // When shot state changes to false, call handleStopShotLogging()
            if (!shotInProgress) {
                handleStopShotLogging()
            }
        }
    }

}
