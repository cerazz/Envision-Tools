package com.example.envisiontools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.envisiontools.ble.EnvisionProtocol
import com.example.envisiontools.viewmodel.EnvisionViewModel

@Composable
fun DeviceScreen(viewModel: EnvisionViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Commands", "Files")

    Column(modifier = modifier.fillMaxSize()) {
        // Status bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Connected",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = uiState.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (uiState.isLoading) CircularProgressIndicator()
                OutlinedButton(
                    onClick = { viewModel.disconnect() },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Disconnect")
                }
            }
        }

        HorizontalDivider()

        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> CommandsTab(viewModel = viewModel, modifier = Modifier.fillMaxSize())
            1 -> FilesTab(viewModel = viewModel, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CommandsTab(viewModel: EnvisionViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Device info section
        uiState.userConfig?.let { config ->
            Text(
                text = "Device: ${config.deviceName}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        uiState.brightness?.let { b ->
            Text(
                text = "Brightness: $b",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        uiState.wmmField?.let { wmm ->
            Text(
                text = "WMM N=%.3f E=%.3f D=%.3f |B|=%.3f".format(wmm.north, wmm.east, wmm.down, wmm.magnitude),
                style = MaterialTheme.typography.bodySmall
            )
        }

        SectionHeader("Sync")
        Button(onClick = { viewModel.syncTime() }, modifier = Modifier.fillMaxWidth()) {
            Text("Sync Time")
        }

        SectionHeader("Query Device")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.fetchBrightness() },
                modifier = Modifier.weight(1f)
            ) { Text("Brightness") }
            Button(
                onClick = { viewModel.fetchUserConfig() },
                modifier = Modifier.weight(1f)
            ) { Text("Config") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.fetchCalibration() },
                modifier = Modifier.weight(1f)
            ) { Text("Calibration") }
            Button(
                onClick = { viewModel.fetchWmmField() },
                modifier = Modifier.weight(1f)
            ) { Text("WMM Field") }
        }

        SectionHeader("Stage 1")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_START_STAGE_ONE, "Stage 1 Start") },
                modifier = Modifier.weight(1f)
            ) { Text("Start") }
            OutlinedButton(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_STOP_STAGE_ONE, "Stage 1 Stop") },
                modifier = Modifier.weight(1f)
            ) { Text("Stop") }
        }

        SectionHeader("Stage 3")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_START_STAGE_THREE, "Stage 3 Start") },
                modifier = Modifier.weight(1f)
            ) { Text("Start") }
            OutlinedButton(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_STOP_STAGE_THREE, "Stage 3 Stop") },
                modifier = Modifier.weight(1f)
            ) { Text("Stop") }
        }

        SectionHeader("Stage 4")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_START_STAGE_FOUR, "Stage 4 Start") },
                modifier = Modifier.weight(1f)
            ) { Text("Start") }
            OutlinedButton(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_STOP_STAGE_FOUR, "Stage 4 Stop") },
                modifier = Modifier.weight(1f)
            ) { Text("Stop") }
        }

        SectionHeader("Stage 5")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_START_STAGE_FIVE, "Stage 5 Start") },
                modifier = Modifier.weight(1f)
            ) { Text("Start") }
            OutlinedButton(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_STOP_STAGE_FIVE, "Stage 5 Stop") },
                modifier = Modifier.weight(1f)
            ) { Text("Stop") }
        }

        SectionHeader("Stage 6")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_START_STAGE_SIX, "Stage 6 Start") },
                modifier = Modifier.weight(1f)
            ) { Text("Start") }
            OutlinedButton(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_STOP_STAGE_SIX, "Stage 6 Stop") },
                modifier = Modifier.weight(1f)
            ) { Text("Stop") }
        }

        SectionHeader("Maintenance")
        Button(
            onClick = { viewModel.doFormatPartition() },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Format Partition")
        }
    }
}

@Composable
private fun FilesTab(viewModel: EnvisionViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()
    val fileList = uiState.fileList
    val currentPath = uiState.currentFilePath

    Column(modifier = modifier.padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = currentPath,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { viewModel.listFiles(currentPath) }) {
                Text("Refresh")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        if (fileList == null) {
            Text(
                text = "Tap Refresh to list files.",
                modifier = Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (fileList.isEmpty()) {
            Text(
                text = "Directory is empty.",
                modifier = Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn {
                items(fileList) { entry ->
                    val isDir = (entry.attr and 0x10) != 0
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = {
                            Text(if (isDir) "Directory" else "${entry.size} bytes")
                        },
                        trailingContent = {
                            if (!isDir) {
                                IconButton(
                                    onClick = {
                                        val filePath = if (currentPath.endsWith("/"))
                                            "$currentPath${entry.name}"
                                        else
                                            "$currentPath/${entry.name}"
                                        viewModel.deleteFile(filePath)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete ${entry.name}",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}
