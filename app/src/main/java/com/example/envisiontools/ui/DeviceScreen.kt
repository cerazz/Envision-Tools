package com.example.envisiontools.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.envisiontools.ble.EnvisionProtocol
import com.example.envisiontools.viewmodel.ConnectionState
import com.example.envisiontools.viewmodel.EnvisionViewModel

/** Resolve a content URI to its human-readable display name. */
private fun getDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) {
                val name = cursor.getString(idx)
                if (!name.isNullOrBlank()) return name
            }
        }
    }
    return uri.lastPathSegment ?: "file.json"
}

@Composable
fun DeviceScreen(
    viewModel: EnvisionViewModel,
    onConnectClick: () -> Unit = {},
    onPickFromMap: (Double, Double) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val isConnected = uiState.connectionState == ConnectionState.CONNECTED
    val isConnecting = uiState.connectionState == ConnectionState.CONNECTING
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Commands", "Files")

    Column(modifier = modifier.fillMaxSize()) {
        // ── Status bar ──────────────────────────────────────────────
        Surface(
            tonalElevation = 4.dp,
            color = if (isConnected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = if (isConnected) "Connected"
                               else if (isConnecting) "Connecting…"
                               else "Not Connected",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isConnected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    if (uiState.statusMessage.isNotBlank()) {
                        Text(
                            text = uiState.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isConnecting) CircularProgressIndicator()
                    if (isConnected) {
                        OutlinedButton(
                            onClick = { viewModel.disconnect() },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("Disconnect") }
                    } else if (!isConnecting) {
                        Button(onClick = onConnectClick) {
                            Icon(
                                imageVector = Icons.Default.WifiOff,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            Text("Scan for Device")
                        }
                    }
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
            0 -> CommandsTab(
                viewModel = viewModel,
                isConnected = isConnected,
                onPickFromMap = onPickFromMap,
                modifier = Modifier.fillMaxSize()
            )
            1 -> FilesTab(
                viewModel = viewModel,
                isConnected = isConnected,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CommandsTab(
    viewModel: EnvisionViewModel,
    isConnected: Boolean,
    onPickFromMap: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // ----- Text-field state -----
    var lat by remember { mutableStateOf("43.2686") }
    var lon by remember { mutableStateOf("5.3955") }
    var targetX by remember { mutableStateOf("80.0") }
    var targetY by remember { mutableStateOf("5.0") }
    var gpsError by remember { mutableStateOf<String?>(null) }
    var azError by remember { mutableStateOf<String?>(null) }
    var altError by remember { mutableStateOf<String?>(null) }

    // ----- File-picker state -----
    var landscapeUri by remember { mutableStateOf<Uri?>(null) }
    var landscapeFileName by remember { mutableStateOf<String?>(null) }
    var poiUri by remember { mutableStateOf<Uri?>(null) }
    var poiFileName by remember { mutableStateOf<String?>(null) }

    val landscapePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            landscapeUri = uri
            landscapeFileName = getDisplayName(context, uri)
        }
    }

    val poiPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            poiUri = uri
            poiFileName = getDisplayName(context, uri)
        }
    }

    // Apply picked location from map picker
    LaunchedEffect(uiState.pickedLocation) {
        uiState.pickedLocation?.let { (pickedLat, pickedLon) ->
            lat = "%.6f".format(pickedLat)
            lon = "%.6f".format(pickedLon)
            viewModel.clearPickedLocation()
        }
    }

    val isBusy = uiState.isLoading || uiState.fullFlowRunning
    val canSend = isConnected && !isBusy

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!isConnected) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "⚠ No device connected — commands are disabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // =========================================================
        // SECTION: Data & Sync
        // =========================================================
        SectionHeader("Data & Sync")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.sendFlushCommand() },
                enabled = canSend,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) { Text("Flush") }
            Button(
                onClick = { viewModel.syncTime() },
                enabled = canSend,
                modifier = Modifier.weight(1f)
            ) { Text("Sync Time") }
        }

        // =========================================================
        // SECTION: GPS Position
        // =========================================================
        SectionHeader("GPS Position")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = lat,
                onValueChange = { lat = it; gpsError = null },
                label = { Text("Latitude") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = lon,
                onValueChange = { lon = it; gpsError = null },
                label = { Text("Longitude") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Use current GPS location
            OutlinedButton(
                onClick = {
                    gpsError = null
                    try {
                        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        val location =
                            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        if (location != null) {
                            lat = "%.6f".format(location.latitude)
                            lon = "%.6f".format(location.longitude)
                        } else {
                            gpsError = "Location unavailable. Enable GPS and try again."
                        }
                    } catch (e: SecurityException) {
                        gpsError = "Location permission denied."
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.GpsFixed,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text("My Location")
            }
            // Open map picker
            OutlinedButton(
                onClick = {
                    val latD = lat.toDoubleOrNull() ?: 43.2686
                    val lonD = lon.toDoubleOrNull() ?: 5.3955
                    onPickFromMap(latD, lonD)
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text("Pick on Map")
            }
        }
        if (gpsError != null) {
            Text(
                text = gpsError!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Button(
            onClick = {
                val latF = lat.toFloatOrNull() ?: return@Button
                val lonF = lon.toFloatOrNull() ?: return@Button
                // Normalise display: ensure a decimal point is visible
                if (!lat.contains('.')) lat = "%.1f".format(latF)
                if (!lon.contains('.')) lon = "%.1f".format(lonF)
                viewModel.sendGpsPosition(latF, lonF)
            },
            enabled = canSend,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Send Position") }

        // =========================================================
        // SECTION: Target
        // =========================================================
        SectionHeader("Target (GoTo)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = targetX,
                onValueChange = {
                    targetX = it
                    val v = it.toFloatOrNull()
                    azError = when {
                        v == null && it.isNotEmpty() -> "Enter a valid number"
                        v != null && (v < 0f || v >= 360f) -> "Azimuth must be in [0, 360)"
                        else -> null
                    }
                },
                label = { Text("Azimuth (x)") },
                singleLine = true,
                isError = azError != null,
                supportingText = azError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = targetY,
                onValueChange = {
                    targetY = it
                    val v = it.toFloatOrNull()
                    altError = when {
                        v == null && it.isNotEmpty() -> "Enter a valid number"
                        v != null && (v < -90f || v > 90f) -> "Altitude must be in [-90, +90]"
                        else -> null
                    }
                },
                label = { Text("Altitude (y)") },
                singleLine = true,
                isError = altError != null,
                supportingText = altError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }
        Button(
            onClick = {
                val xF = targetX.toFloatOrNull() ?: return@Button
                val yF = targetY.toFloatOrNull() ?: return@Button
                if (xF < 0f || xF >= 360f) return@Button
                if (yF < -90f || yF > 90f) return@Button
                // Normalise display
                if (!targetX.contains('.')) targetX = "%.1f".format(xF)
                if (!targetY.contains('.')) targetY = "%.1f".format(yF)
                viewModel.sendTargetPosition(xF, yF)
            },
            enabled = canSend && azError == null && altError == null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Send Target") }

        // =========================================================
        // SECTION: Landscape & POI (standalone upload)
        // =========================================================
        SectionHeader("Landscape & POI")

        // Landscape
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Landscape", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { landscapePicker.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = landscapeFileName ?: "Pick File…",
                            maxLines = 1
                        )
                    }
                    Button(
                        onClick = { landscapeUri?.let { viewModel.loadAndSendLandscape(context, it) } },
                        enabled = canSend && landscapeUri != null
                    ) { Text("Send") }
                }
                OutlinedButton(
                    onClick = { viewModel.loadAndSendLandscapeFromAsset(context, "silouhette_Marseille.json") },
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Example: Marseille") }
                uiState.landscapeProgress?.let { (sent, total) ->
                    LinearProgressIndicator(
                        progress = { if (total > 0) sent.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Lines: $sent / $total",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // POI
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Points of Interest (POI)", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { poiPicker.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = poiFileName ?: "Pick File…",
                            maxLines = 1
                        )
                    }
                    Button(
                        onClick = { poiUri?.let { viewModel.loadAndSendPoi(context, it) } },
                        enabled = canSend && poiUri != null
                    ) { Text("Send") }
                }
                OutlinedButton(
                    onClick = { viewModel.loadAndSendPoiFromAsset(context, "poi_marseille.json") },
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Example: Marseille POI") }
                uiState.poiProgress?.let { (sent, total) ->
                    LinearProgressIndicator(
                        progress = { if (total > 0) sent.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "POI: $sent / $total",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // =========================================================
        // SECTION: Full Flow
        // =========================================================
        SectionHeader("Full Initialisation Flow")
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Flush → Sync Time → Send Position → Landscape → POI",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Uses lat/lon from GPS section and the files picked above.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.fullFlowRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Button(
                    onClick = {
                        val latF = lat.toFloatOrNull() ?: return@Button
                        val lonF = lon.toFloatOrNull() ?: return@Button
                        viewModel.runFullFlow(latF, lonF, landscapeUri, poiUri, context)
                    },
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) { Text("Run Full Flow") }
            }
        }

        // =========================================================
        // SECTION: Query Device
        // =========================================================
        SectionHeader("Query Device")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.fetchBrightness() },
                enabled = canSend,
                modifier = Modifier.weight(1f)
            ) { Text("Brightness") }
            Button(
                onClick = { viewModel.fetchUserConfig() },
                enabled = canSend,
                modifier = Modifier.weight(1f)
            ) { Text("Config") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.fetchCalibration() },
                enabled = canSend,
                modifier = Modifier.weight(1f)
            ) { Text("Calibration") }
            Button(
                onClick = { viewModel.fetchWmmField() },
                enabled = canSend,
                modifier = Modifier.weight(1f)
            ) { Text("WMM Field") }
        }

        // --- brightness inline
        uiState.brightness?.let { b ->
            InfoRow("Brightness", "$b / 255")
        }

        // --- WMM card
        uiState.wmmField?.let { wmm ->
            Spacer(Modifier.height(4.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("WMM Magnetic Field", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
                    InfoRow("North (X)", "%.3f µT".format(wmm.north))
                    InfoRow("East  (Y)", "%.3f µT".format(wmm.east))
                    InfoRow("Down  (Z)", "%.3f µT".format(wmm.down))
                    InfoRow("Total |B|", "%.3f µT".format(wmm.magnitude))
                    InfoRow("Horiz. intensity", "%.3f µT".format(wmm.horizontalIntensity))
                    InfoRow("Inclination",  "%.2f°".format(wmm.inclinationDeg))
                    InfoRow("Declination",  "%.2f°".format(wmm.declinationDeg))
                }
            }
        }

        // --- User config card
        uiState.userConfig?.let { cfg ->
            Spacer(Modifier.height(4.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("User Configuration", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
                    InfoRow("Device name",     cfg.deviceName)
                    InfoRow("Brightness",      "${cfg.brightness}")
                    InfoRow("Auto-brightness", if (cfg.autoBrightness) "ON" else "OFF")
                    InfoRow("Screen timeout",  "${cfg.screenTimeout} s")
                    InfoRow("Orientation",     "${cfg.orientation}")
                    InfoRow("Home latitude",   "%.6f°".format(cfg.homeLatitude))
                    InfoRow("Home longitude",  "%.6f°".format(cfg.homeLongitude))
                    InfoRow("Home altitude",   "%.1f m".format(cfg.homeAltitude))
                    InfoRow("Timezone",        cfg.timezoneLabel)
                    InfoRow("Language",        "${cfg.language}")
                    InfoRow("Date format",     "${cfg.dateFormat}")
                    InfoRow("Coord format",    "${cfg.coordinateFormat}")
                    InfoRow("Temp unit",       if (cfg.temperatureUnit == 0) "°C" else "°F")
                    InfoRow("Magnitude limit", "${cfg.magnitudeLimit}")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
                    Text("Sky Map", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary)
                    InfoRow("Constellation lines", if (cfg.showConstellationLines) "✓" else "✗")
                    InfoRow("Constellation names", if (cfg.showConstellationNames) "✓" else "✗")
                    InfoRow("Deep sky objects",    if (cfg.showDeepSkyObjects) "✓" else "✗")
                    InfoRow("Planets",             if (cfg.showPlanets) "✓" else "✗")
                }
            }
        }

        // --- Calibration card
        uiState.calibration?.let { cal ->
            Spacer(Modifier.height(4.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Calibration Data", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
                    InfoRow("Success",     if (cal.success) "YES" else "NO")
                    InfoRow("Date",        if (cal.calibrationDate > 0L)
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                            .format(java.util.Date(cal.calibrationDate * 1000L))
                    else "not set")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
                    Text("Status flags", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary)
                    InfoRow("Gyro calibrated",  if (cal.gyroCalibrated)  "✓" else "✗")
                    InfoRow("Accel calibrated", if (cal.accelCalibrated) "✓" else "✗")
                    InfoRow("Mag1 calibrated",  if (cal.mag1Calibrated)  "✓" else "✗")
                    InfoRow("Mag2 calibrated",  if (cal.mag2Calibrated)  "✓" else "✗")
                    InfoRow("Mag3 calibrated",  if (cal.mag3Calibrated)  "✓" else "✗")
                    InfoRow("Temp compensation", if (cal.tempCompEnabled) "✓" else "✗")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
                    Text("Offsets", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary)
                    InfoRow("Gyro offset",
                        "[%.4f, %.4f, %.4f]".format(cal.gyroOffset[0], cal.gyroOffset[1], cal.gyroOffset[2]))
                    InfoRow("Accel offset",
                        "[%.4f, %.4f, %.4f]".format(cal.accelOffset[0], cal.accelOffset[1], cal.accelOffset[2]))
                    InfoRow("Accel scale",
                        "[%.4f, %.4f, %.4f]".format(cal.accelScale[0], cal.accelScale[1], cal.accelScale[2]))
                    InfoRow("Mag1 hard iron",
                        "[%.4f, %.4f, %.4f]".format(cal.mag1HardIron[0], cal.mag1HardIron[1], cal.mag1HardIron[2]))
                    InfoRow("Mag2 hard iron",
                        "[%.4f, %.4f, %.4f]".format(cal.mag2HardIron[0], cal.mag2HardIron[1], cal.mag2HardIron[2]))
                    InfoRow("Mag3 hard iron",
                        "[%.4f, %.4f, %.4f]".format(cal.mag3HardIron[0], cal.mag3HardIron[1], cal.mag3HardIron[2]))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
                    Text("IMU → Optics", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary)
                    InfoRow("Pitch",  "%.4f°".format(cal.imuToOpticalPitchDeg))
                    InfoRow("Yaw",    "%.4f°".format(cal.imuToOpticalYawDeg))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
                    Text("Display", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary)
                    InfoRow("Offset X", "${cal.displayXOffset} px")
                    InfoRow("Offset Y", "${cal.displayYOffset} px")
                    InfoRow("Zoom",  "%.4f".format(cal.displayZoom))
                    InfoRow("Tilt",  "%.4f°".format(cal.displayTilt))
                }
            }
        }

        // =========================================================
        // TESTBENCH STAGES
        // =========================================================
        SectionHeader("Stage 1")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_START_STAGE_ONE, "Stage 1 Start") },
                enabled = canSend,
                modifier = Modifier.weight(1f)
            ) { Text("Start") }
            OutlinedButton(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_STOP_STAGE_ONE, "Stage 1 Stop") },
                enabled = canSend,
                modifier = Modifier.weight(1f)
            ) { Text("Stop") }
        }

        SectionHeader("Stage 3")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_START_STAGE_THREE, "Stage 3 Start") },
                enabled = canSend,
                modifier = Modifier.weight(1f)
            ) { Text("Start") }
            OutlinedButton(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_STOP_STAGE_THREE, "Stage 3 Stop") },
                enabled = canSend,
                modifier = Modifier.weight(1f)
            ) { Text("Stop") }
        }

        SectionHeader("Stage 4")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_START_STAGE_FOUR, "Stage 4 Start") },
                enabled = canSend,
                modifier = Modifier.weight(1f)
            ) { Text("Start") }
            OutlinedButton(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_STOP_STAGE_FOUR, "Stage 4 Stop") },
                enabled = canSend,
                modifier = Modifier.weight(1f)
            ) { Text("Stop") }
        }

        SectionHeader("Stage 5")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_START_STAGE_FIVE, "Stage 5 Start") },
                enabled = canSend,
                modifier = Modifier.weight(1f)
            ) { Text("Start") }
            OutlinedButton(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_STOP_STAGE_FIVE, "Stage 5 Stop") },
                enabled = canSend,
                modifier = Modifier.weight(1f)
            ) { Text("Stop") }
        }

        SectionHeader("Stage 6")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_START_STAGE_SIX, "Stage 6 Start") },
                enabled = canSend,
                modifier = Modifier.weight(1f)
            ) { Text("Start") }
            OutlinedButton(
                onClick = { viewModel.stageCommand(EnvisionProtocol.MSG_STOP_STAGE_SIX, "Stage 6 Stop") },
                enabled = canSend,
                modifier = Modifier.weight(1f)
            ) { Text("Stop") }
        }

        // =========================================================
        // SECTION: Maintenance
        // =========================================================
        SectionHeader("Maintenance")
        Button(
            onClick = { viewModel.doFormatPartition() },
            enabled = canSend,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Format Partition")
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FilesTab(
    viewModel: EnvisionViewModel,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
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
            Button(
                onClick = { viewModel.listFiles(currentPath) },
                enabled = isConnected
            ) {
                Text("Refresh")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        if (!isConnected) {
            Text(
                text = "Connect a device to browse files.",
                modifier = Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (fileList == null) {
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
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
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

