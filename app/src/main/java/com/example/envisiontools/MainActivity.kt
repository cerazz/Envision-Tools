package com.example.envisiontools

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.envisiontools.ui.DeviceScreen
import com.example.envisiontools.ui.MapPickerScreen
import com.example.envisiontools.ui.ScanScreen
import com.example.envisiontools.ui.theme.EnvisionToolsTheme
import com.example.envisiontools.viewmodel.ConnectionState
import com.example.envisiontools.viewmodel.EnvisionViewModel

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled; user must grant before scanning */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        enableEdgeToEdge()
        setContent {
            EnvisionToolsTheme {
                val vm: EnvisionViewModel = viewModel()
                val uiState by vm.uiState.collectAsState()
                val navController = rememberNavController()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "device",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("device") {
                            DeviceScreen(
                                viewModel = vm,
                                onConnectClick = { navController.navigate("scan") },
                                onPickFromMap = { lat, lon ->
                                    navController.navigate("map_picker/$lat/$lon")
                                }
                            )
                        }
                        composable("scan") {
                            ScanScreen(viewModel = vm)
                            // Auto-navigate back when connected
                            if (uiState.connectionState == ConnectionState.CONNECTED) {
                                navController.popBackStack()
                            }
                        }
                        composable("map_picker/{lat}/{lon}") { backStackEntry ->
                            val lat = backStackEntry.arguments?.getString("lat")
                                ?.toDoubleOrNull() ?: 43.2965
                            val lon = backStackEntry.arguments?.getString("lon")
                                ?.toDoubleOrNull() ?: 5.3698
                            MapPickerScreen(
                                viewModel = vm,
                                initialLat = lat,
                                initialLon = lon,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}

