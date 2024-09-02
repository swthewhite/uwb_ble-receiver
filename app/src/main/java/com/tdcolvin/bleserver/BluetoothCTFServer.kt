package com.tdcolvin.bleserver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbClientSessionScope
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import androidx.core.uwb.rxjava3.controleeSessionScopeSingle
import androidx.core.uwb.rxjava3.rangingResultsObservable
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


const val CTF_SERVICE_UUID = "8c380000-10bd-4fdb-ba21-1922d6cf860d"
const val PASSWORD_CHARACTERISTIC_UUID = "8c380001-10bd-4fdb-ba21-1922d6cf860d"
const val NAME_CHARACTERISTIC_UUID = "8c380002-10bd-4fdb-ba21-1922d6cf860d"

const val PERMISSION_BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
const val PERMISSION_BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"

class BluetoothCTFServer(private val context: Context) {
    private val bluetooth = context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? BluetoothManager ?: throw Exception("This device doesn't support Bluetooth")

    private val serviceUuid = UUID.fromString(CTF_SERVICE_UUID)
    private val passwordCharUuid = UUID.fromString(PASSWORD_CHARACTERISTIC_UUID)
    private val nameCharUuid = UUID.fromString(NAME_CHARACTERISTIC_UUID)

    private var server: BluetoothGattServer? = null
    private var ctfService: BluetoothGattService? = null

    private var advertiseCallback: AdvertiseCallback? = null
    private val isServerListening: MutableStateFlow<Boolean?> = MutableStateFlow(null)

    private val preparedWrites = HashMap<Int, ByteArray>()

    val controllerReceived = MutableStateFlow(emptyList<String>())

    private val uwbCommunicator = UwbControleeCommunicator(context)

    @RequiresPermission(allOf = [PERMISSION_BLUETOOTH_CONNECT, PERMISSION_BLUETOOTH_ADVERTISE])
    suspend fun startServer() = withContext(Dispatchers.IO) {
        if (server != null) {
            return@withContext
        }

        startHandlingIncomingConnections()
        startAdvertising()

        // Listen for controllerReceived updates
        collectControllerReceived()
    }

    @RequiresPermission(allOf = [PERMISSION_BLUETOOTH_CONNECT, PERMISSION_BLUETOOTH_ADVERTISE])
    suspend fun stopServer() = withContext(Dispatchers.IO) {
        if (server == null) {
            return@withContext
        }

        stopAdvertising()
        stopHandlingIncomingConnections()
    }

    @RequiresPermission(PERMISSION_BLUETOOTH_ADVERTISE)
    private suspend fun startAdvertising() {
        val advertiser: BluetoothLeAdvertiser = bluetooth.adapter.bluetoothLeAdvertiser
            ?: throw Exception("This device is not able to advertise")

        if (advertiseCallback != null) {
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .build()

        advertiseCallback = suspendCoroutine { continuation ->
            val advertiseCallback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    super.onStartSuccess(settingsInEffect)
                    continuation.resume(this)
                }

                override fun onStartFailure(errorCode: Int) {
                    super.onStartFailure(errorCode)
                    throw Exception("Unable to start advertising, errorCode: $errorCode")
                }
            }
            advertiser.startAdvertising(settings, data, advertiseCallback)
        }
    }

    @RequiresPermission(PERMISSION_BLUETOOTH_ADVERTISE)
    private fun stopAdvertising() {
        val advertiser: BluetoothLeAdvertiser = bluetooth.adapter.bluetoothLeAdvertiser
            ?: throw Exception("This device is not able to advertise")

        advertiseCallback?.let {
            advertiser.stopAdvertising(it)
            advertiseCallback = null
        }
    }

    @RequiresPermission(PERMISSION_BLUETOOTH_CONNECT)
    private fun startHandlingIncomingConnections() {
        server = bluetooth.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                super.onServiceAdded(status, service)
                isServerListening.value = true
            }

            @RequiresPermission(PERMISSION_BLUETOOTH_CONNECT)
            override fun onCharacteristicReadRequest(
                device: BluetoothDevice?,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic?
            ) {
                super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
                val uwbAddress = uwbCommunicator.getUwbAddress()
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, uwbAddress.toByteArray())
            }

            @RequiresPermission(PERMISSION_BLUETOOTH_CONNECT)
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                super.onCharacteristicWriteRequest(
                    device,
                    requestId,
                    characteristic,
                    preparedWrite,
                    responseNeeded,
                    offset,
                    value
                )

                if (preparedWrite) {
                    val bytes = preparedWrites.getOrDefault(requestId, byteArrayOf())
                    preparedWrites[requestId] = bytes.plus(value)
                } else {
                    controllerReceived.update { it.plus(String(value)) }
                }

                if (responseNeeded) {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf())
                }
            }

            override fun onExecuteWrite(
                device: BluetoothDevice?,
                requestId: Int,
                execute: Boolean
            ) {
                super.onExecuteWrite(device, requestId, execute)
                val bytes = preparedWrites.remove(requestId)
                if (execute && bytes != null) {
                    controllerReceived.update { it.plus(String(bytes)) }
                }
            }
        })

        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val passwordCharacteristic = BluetoothGattCharacteristic(
            passwordCharUuid,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val nameCharacteristic = BluetoothGattCharacteristic(
            nameCharUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(passwordCharacteristic)
        service.addCharacteristic(nameCharacteristic)
        server?.addService(service)
        ctfService = service
    }

    @RequiresPermission(PERMISSION_BLUETOOTH_CONNECT)
    private fun stopHandlingIncomingConnections() {
        ctfService?.let {
            server?.removeService(it)
            ctfService = null
        }
    }

    private fun parseReceivedData(data: String): Pair<String, String> {
        val parts = data.split("/")
        val addressPart = parts[0]
        val channelPart = parts[1]
        return Pair(addressPart, channelPart)
    }

    private suspend fun collectControllerReceived() {
        controllerReceived.collect { receivedDataList ->
            // 가장 최근에 받은 데이터를 사용하여 UWB 연결 설정
            if (receivedDataList.isNotEmpty()) {
                val lastReceivedData = receivedDataList.last()
                val (address, channel) = parseReceivedData(lastReceivedData)
                Log.d("uwb", "me:"+uwbCommunicator.getUwbAddress())
                Log.d("uwb", "controller:$address/$channel")
                uwbCommunicator.startCommunication(address, channel)
            }
        }
    }
}
