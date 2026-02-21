package com.example.envisiontools.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.envisiontools.viewmodel.EnvisionViewModel

@SuppressLint("MissingPermission")
@Composable
fun ScanScreen(viewModel: EnvisionViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()
    val scanResults by viewModel.scanResults.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Envision BLE Tools",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = uiState.statusMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.startScan(context) },
                enabled = !uiState.isScanning
            ) {
                Text("Scan")
            }
            OutlinedButton(
                onClick = { viewModel.stopScan() },
                enabled = uiState.isScanning
            ) {
                Text("Stop")
            }
        }

        if (uiState.isScanning) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator()
                Text("Scanning for ENVISION devices…")
            }
        }

        if (scanResults.isEmpty() && !uiState.isScanning) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No devices found.\nTap Scan to start.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scanResults) { device ->
                    DeviceCard(device = device, onConnect = { viewModel.connect(device, context) })
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceCard(device: BluetoothDevice, onConnect: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onConnect() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = device.name ?: "Unknown",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Tap to connect",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
