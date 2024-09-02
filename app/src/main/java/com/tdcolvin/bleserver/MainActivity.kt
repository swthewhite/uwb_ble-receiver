package com.tdcolvin.bleserver

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tdcolvin.bleserver.ui.theme.BLEServerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.appcompat.app.AppCompatActivity // 변경된 부분
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    private val allPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.UWB_RANGING
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.UWB_RANGING
        )
    }

    private fun haveAllPermissions(context: Context): Boolean {
        return allPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BLEServerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ServerScreen()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun ServerScreen(viewModel: ServerViewModel = viewModel()) {
        val context = LocalContext.current
        var allPermissionsGranted by remember {
            mutableStateOf(haveAllPermissions(context))
        }

        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        Column {
            if (allPermissionsGranted) {
                ServerStatus(
                    serverRunning = uiState.serverRunning,
                    onStartServer = { viewModel.startServer() },
                    onStopServer = { viewModel.stopServer() }
                )
                NamesReceived(names = uiState.namesReceived)

                // UWB Address Display
                UwbAddressDisplay(
                    uwbAddress = uiState.uwbAddress,
                    onFetchUwbAddress = { viewModel.fetchUwbAddress() }
                )
            } else {
                val launcher = rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestMultiplePermissions()) { granted ->
                    allPermissionsGranted = granted.values.all { it }
                }
                Button(onClick = { launcher.launch(allPermissions) }) {
                    Text("Grant Permission")
                }
            }
        }
    }

    @Composable
    fun ServerStatus(serverRunning: Boolean, onStartServer: () -> Unit, onStopServer: () -> Unit) {
        if (serverRunning) {
            Text("Server running")
            Button(onClick = onStopServer) {
                Text("Stop server")
            }
        } else {
            Text("Server not running")
            Button(onClick = onStartServer) {
                Text("Start server")
            }
        }
    }

    @Composable
    fun NamesReceived(names: List<String>) {
        LazyColumn {
            items(names) { name ->
                Text(name)
            }
        }
    }

    @Composable
    fun UwbAddressDisplay(uwbAddress: String, onFetchUwbAddress: () -> Unit) {
        Column {
            Button(onClick = onFetchUwbAddress) {
                Text("Fetch UWB Address")
            }
            Text("UWB Address: $uwbAddress")
        }
    }
}

class ServerViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ServerUIState())
    val uiState = _uiState.asStateFlow()

    private val server: BluetoothCTFServer = BluetoothCTFServer(application)
    private val uwbCommunicator: UwbControleeCommunicator = UwbControleeCommunicator(application)

    init {
        viewModelScope.launch {
            server.controllerReceived.collect { names ->
                _uiState.update { it.copy(namesReceived = names) }
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.UWB_RANGING])
    fun startServer() {
        viewModelScope.launch {
            server.startServer()
            _uiState.update { it.copy(serverRunning = true) }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.UWB_RANGING])
    fun stopServer() {
        viewModelScope.launch {
            server.stopServer()
            _uiState.update { it.copy(serverRunning = false) }
        }
    }

    fun fetchUwbAddress() {
        viewModelScope.launch {
            val uwbAddress = uwbCommunicator.getUwbAddress()
            _uiState.update { it.copy(uwbAddress = uwbAddress) }
        }
    }
}

data class ServerUIState(
    val serverRunning: Boolean = false,
    val namesReceived: List<String> = emptyList(),
    val uwbAddress: String = "Unknown"
)