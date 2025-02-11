package com.example.gravimetric

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.observer.ConnectionObserver
import no.nordicsemi.android.support.v18.scanner.ScanSettings
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanFilter
import no.nordicsemi.android.support.v18.scanner.ScanResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class ScaleBleManager(context: Context) : BleManager(context), ConnectionObserver {

    private var massCharacteristic: BluetoothGattCharacteristic? = null
    private var scaleCallback: ScaleBleManagerCallback? = null
    private var tareCharacteristic: BluetoothGattCharacteristic? = null
    private var targetMassCharacteristic: BluetoothGattCharacteristic? = null
    private var loggingCharacteristic: BluetoothGattCharacteristic? = null
    private var shotStateCharacteristic: BluetoothGattCharacteristic? = null
    private var logStateCharacteristic: BluetoothGattCharacteristic? = null

    private val scanner = BluetoothLeScannerCompat.getScanner()

    fun scanForDevice(onFound: (BluetoothDevice) -> Unit) {
        val filters = emptyList<ScanFilter>()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: "Unknown"

                Log.d("BLE_SCAN", "🔍 Found Device: Name: $name, Address: ${device.address}, UUIDs: ${result.scanRecord?.serviceUuids}")

                try {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                        if (device.name?.contains("LoggingScale") == true)
                        {
                            Log.d("BLE_SCAN", "✅ Found LoggingScale: ${device.address}")
                            scanner.stopScan(this)
                            onFound(device)
                        }
                    } else {
                        Log.w("BLE_SCAN", "Missing BLUETOOTH_SCAN permission.")
                    }
                } catch (e: SecurityException) {
                    Log.e("BLE_SCAN", "SecurityException: ${e.message}")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e("BLE_SCAN", "Scan failed with error: $errorCode")
            }
        }

        scanner.startScan(filters, settings, scanCallback)

        // Stop scanning after 10s timeout
        Handler(Looper.getMainLooper()).postDelayed({
            scanner.stopScan(scanCallback)
        }, 10000)
    }

    override fun initialize() {
        // Subscribe to notifications on the mass characteristic.
        massCharacteristic?.let { charac ->
            setNotificationCallback(charac).with { _: BluetoothDevice, data: Data ->
                val rawBytes = data.value ?: return@with
                Log.d("BLE_DEBUG", "Mass raw bytes: ${rawBytes.joinToString { "%02X".format(it) }}")

                // Parse as little-endian 16-bit
                val shortVal = ByteBuffer
                    .wrap(rawBytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .short

                val mass = shortVal / 10f
                Log.d("BLE_DEBUG", "Parsed mass = $mass g")

                scaleCallback?.onMassUpdated(mass)
            }
            enableNotifications(charac).enqueue()
        }

        loggingCharacteristic?.let { charac ->
            setNotificationCallback(charac).with { _, data ->
                val rawBytes = data.value ?: return@with
                // parse your 16-bit scaled int just like massCharacteristic
                val shortVal = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).short
                val mass = shortVal / 10f
                Log.d("BLE_DEBUG", "Logging feed mass = $mass")

                scaleCallback?.onLoggingDataReceived(mass)
            }
            enableNotifications(charac).enqueue()
        }

        shotStateCharacteristic?.let { charac ->
            setNotificationCallback(charac).with { _: BluetoothDevice, data: Data ->
                val raw = data.value ?: return@with
                // Expecting a single byte: 1 means shot is on, 0 means shot is off.
                if (raw.size >= 1) {
                    val status = raw[0].toInt() != 0
                    Log.d("BLE_DEBUG", "Shot state = $status")
                    scaleCallback?.onShotStatusChanged(status)
                }
            }
            enableNotifications(charac).enqueue()
        }
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        // Discover the scale service and required characteristics.
        val service = gatt.getService(SCALE_SERVICE_UUID)
        massCharacteristic = service?.getCharacteristic(MASS_CHAR_UUID)
        tareCharacteristic = service?.getCharacteristic(TARE_CHAR_UUID)
        loggingCharacteristic = service?.getCharacteristic(LOGGING_CHAR_UUID)
        targetMassCharacteristic = service?.getCharacteristic(TARGET_MASS_CHAR_UUID)
        shotStateCharacteristic = service?.getCharacteristic(SHOTSTATE_CHAR_UUID)
        logStateCharacteristic = service?.getCharacteristic(LOGSTATE_CHAR_UUID)
        return massCharacteristic != null
                && tareCharacteristic != null
                && targetMassCharacteristic != null
                && shotStateCharacteristic != null
                && logStateCharacteristic != null
    }

    override fun onServicesInvalidated() {
        massCharacteristic = null
        tareCharacteristic = null
        targetMassCharacteristic = null
        shotStateCharacteristic = null
        logStateCharacteristic = null
        Log.d("ScaleBleManager", "Services invalidated. All characteristics reset.")
    }


    // For sending commands
    fun sendTareCommand() {
        val tareCommand = byteArrayOf(0x01) // Replace with actual command bytes
        tareCharacteristic?.let { charac ->
            writeCharacteristic(charac, tareCommand, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                .enqueue()
        }
    }

    fun sendStartCycleCommand() {
        val startStopCommand = byteArrayOf(0x01) // Replace with actual command bytes
        shotStateCharacteristic?.let { charac ->
            writeCharacteristic(charac, startStopCommand, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                .enqueue()
        }
    }

    fun sendStopCycleCommand() {
        val startStopCommand = byteArrayOf(0x00) // Replace with actual command bytes
        shotStateCharacteristic?.let { charac ->
            writeCharacteristic(charac, startStopCommand, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                .enqueue()
        }
    }

    fun startLogging() {
        val startLogCmd = byteArrayOf(0x01)
        logStateCharacteristic?.let { charac ->
            writeCharacteristic(charac, startLogCmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                .enqueue()
        }
    }

    fun stopLogging() {
        val stopLogCmd = byteArrayOf(0x00)
        logStateCharacteristic?.let { charac ->
            writeCharacteristic(charac, stopLogCmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                .enqueue()
        }
    }

    fun readTargetMass() {
        targetMassCharacteristic?.let { charac ->
            readCharacteristic(charac).with { _: BluetoothDevice, data: Data ->
                val raw = data.value ?: return@with
                if (raw.size >= 2) {
                    // Parse the 2-byte value as a little-endian short
                    val scaledValue = ByteBuffer.wrap(raw)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .short
                    // Convert to float by dividing by 10
                    val targetMass = scaledValue.toFloat() / 10f
                    scaleCallback?.onTargetMassRead(targetMass)
                } else {
                    scaleCallback?.onTargetMassRead(0f)
                }
            }.enqueue()
        }
    }

    fun writeTargetMass(targetMass: Float) {
        // Scale the value: multiply by 10 and convert to Short
        val scaled = (targetMass * 10).toInt().toShort()
        val buffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(scaled)
        targetMassCharacteristic?.let { charac ->
            writeCharacteristic(charac, buffer.array(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                .enqueue()
        }
    }

    fun setScaleCallback(callback: ScaleBleManagerCallback) {
        this.scaleCallback = callback
    }

    // ConnectionObserver methods...
    override fun onDeviceConnecting(device: BluetoothDevice) { scaleCallback?.onDeviceConnecting(device) }
    override fun onDeviceConnected(device: BluetoothDevice) { scaleCallback?.onDeviceConnected(device) }
    override fun onDeviceReady(device: BluetoothDevice) { scaleCallback?.onDeviceReady(device) }
    override fun onDeviceDisconnecting(device: BluetoothDevice) { scaleCallback?.onDeviceDisconnecting(device) }
    override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) { scaleCallback?.onDeviceDisconnected(device, reason) }
    override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) { scaleCallback?.onDeviceFailedToConnect(device, reason) }

    companion object {
        val SCALE_SERVICE_UUID = java.util.UUID.fromString("1eb9bb89-186e-4d2a-a204-346da73c061c")!!
        val MASS_CHAR_UUID = java.util.UUID.fromString("5977b71a-a58c-40d2-85a4-34071043d9ca")!!
        val TARE_CHAR_UUID = java.util.UUID.fromString("a8f2d9f3-c93a-4479-8208-7287262eacf6")!!
        val TARGET_MASS_CHAR_UUID= java.util.UUID.fromString("bcf25166-c8d1-4421-805f-0d277cbfb82e")!!
        val LOGGING_CHAR_UUID   = java.util.UUID.fromString("9fdd73d8-77e8-4099-816f-a1619834c3f2")!!
        val SHOTSTATE_CHAR_UUID = java.util.UUID.fromString("c4fc31b7-0442-4ed8-861f-08c5e8843eb7")!!
        val LOGSTATE_CHAR_UUID = java.util.UUID.fromString("101305d0-ebd3-4862-b816-a12f7694f498")!!
    }
}

/**
 * Callback interface for BLE events.
 */
interface ScaleBleManagerCallback {
    fun onMassUpdated(mass: Float)
    fun onLoggingDataReceived(mass: Float)
    fun onTargetMassRead(targetMass: Float)
    fun onDeviceConnecting(device: BluetoothDevice) {}
    fun onDeviceConnected(device: BluetoothDevice)
    fun onDeviceReady(device: BluetoothDevice) {}
    fun onDeviceDisconnecting(device: BluetoothDevice) {}
    fun onDeviceDisconnected(device: BluetoothDevice, reason: Int)
    fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int)
    fun onShotStatusChanged(shotInProgress: Boolean)
}
