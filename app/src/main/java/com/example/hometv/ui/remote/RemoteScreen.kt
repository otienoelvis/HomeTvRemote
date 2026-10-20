package com.example.hometv.ui.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hometv.R
import com.example.hometv.domain.model.ConnectionStatus
import com.example.hometv.domain.model.ProtocolType
import com.example.hometv.domain.model.RemoteCommand
import com.example.hometv.domain.model.TvDevice
import kotlin.math.abs
import kotlin.math.sqrt

private val Background = Color(0xFF0B0F19)
private val CardBackground = Color(0xFF161F30)
private val CardBorder = Color(0xFF2A364F)
private val Accent = Color(0xFF00E5FF)
private val TextPrimary = Color(0xFFE2E8F0)
private val TextSecondary = Color(0xFF94A3B8)
private val Danger = Color(0xFFFF3B30)

@Composable
fun RemoteScreen(viewModel: RemoteViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HeaderSection(uiState = uiState, viewModel = viewModel)

            DpadControlSection(onCommand = viewModel::sendCommand)

            ActionButtonsSection(onCommand = viewModel::sendCommand)

            AppLauncherSection(onCommand = viewModel::sendCommand)

            if (uiState.isDiagnosticsVisible) {
                DiagnosticLogSection(
                    logs = uiState.diagnosticLogs,
                    onHide = viewModel::toggleDiagnostics
                )
            }
        }

        if (uiState.isDevicesSheetVisible) {
            DevicesSheet(uiState = uiState, viewModel = viewModel)
        }

        if (uiState.isPairingDialogVisible) {
            PairingPinDialog(
                pinInput = uiState.pairingPinInput,
                deviceName = uiState.pairingDevice?.name ?: uiState.selectedDevice?.name ?: "Android TV",
                onPinChange = viewModel::updatePinInput,
                onSubmit = viewModel::submitPin,
                onDismiss = viewModel::dismissPairingDialog
            )
        }

        if (uiState.isManualIpDialogVisible) {
            ManualIpDialog(
                ipInput = uiState.manualIpInput,
                onIpChange = viewModel::updateManualIpInput,
                onSubmit = viewModel::connectManualIp,
                onDismiss = viewModel::dismissManualIpDialog
            )
        }
    }
}

@Composable
private fun HeaderSection(uiState: RemoteUiState, viewModel: RemoteViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_hometv_logo),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "HomeTv Remote",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = uiState.selectedDevice?.let { "${it.name} • ${it.hostAddress}" }
                            ?: "No TV selected",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(statusColor(uiState.connectionStatus), shape = CircleShape)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (uiState.isScanning) viewModel.stopScan() else viewModel.startScan()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(if (uiState.isScanning) "Stop" else "Scan", color = Accent, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { viewModel.openDevicesSheet() },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Text("Devices (${uiState.discoveredDevices.size})", color = TextPrimary, fontSize = 12.sp)
                }
            }

            Text(
                text = uiState.statusMessage,
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DeviceListSection(
    devices: List<TvDevice>,
    selectedDevice: TvDevice?,
    activeDevice: TvDevice?,
    connectionStatus: ConnectionStatus,
    onSelect: (TvDevice) -> Unit,
    onConnect: (TvDevice) -> Unit,
    onPair: (TvDevice) -> Unit,
    onForget: (TvDevice) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "TVS (${devices.size})",
                color = Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )

            if (devices.isEmpty()) {
                Text(
                    text = "No devices yet. Tap Scan, or add a TV by IP.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            } else {
                devices.forEach { device ->
                    DeviceRow(
                        device = device,
                        selected = device.id == selectedDevice?.id ||
                            (selectedDevice != null && device.hostAddress == selectedDevice.hostAddress),
                        connected = connectionStatus == ConnectionStatus.CONNECTED &&
                            (device.id == activeDevice?.id || device.hostAddress == activeDevice?.hostAddress),
                        onSelect = { onSelect(device) },
                        onConnect = { onConnect(device) },
                        onPair = { onPair(device) },
                        onForget = { onForget(device) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: TvDevice,
    selected: Boolean,
    connected: Boolean,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onPair: () -> Unit,
    onForget: () -> Unit
) {
    val background = if (selected) Color(0xFF22314A) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    when {
                        connected -> Accent
                        device.isOnline -> Color(0xFF4ADE80)
                        else -> Color.Gray
                    },
                    shape = CircleShape
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${device.hostAddress} • ${if (device.isPaired) "paired" else "not paired"} • ${if (device.isOnline) "online" else "offline"}",
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (device.isPaired) {
            if (connected) {
                Text("Connected", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            } else {
                TextButton(onClick = onConnect) {
                    Text("Connect", color = Accent, fontSize = 11.sp)
                }
            }
            TextButton(onClick = onForget) {
                Text("Forget", color = Danger, fontSize = 11.sp)
            }
        } else {
            TextButton(onClick = onPair) {
                Text("Pair", color = Accent, fontSize = 11.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesSheet(uiState: RemoteUiState, viewModel: RemoteViewModel) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = viewModel::dismissDevicesSheet,
        sheetState = sheetState,
        containerColor = CardBackground,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "TVs & settings",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            DeviceListSection(
                devices = uiState.discoveredDevices,
                selectedDevice = uiState.selectedDevice,
                activeDevice = uiState.activeDevice,
                connectionStatus = uiState.connectionStatus,
                onSelect = viewModel::selectDevice,
                onConnect = {
                    viewModel.selectDevice(it)
                    viewModel.connectSelected()
                },
                onPair = {
                    viewModel.selectDevice(it)
                    viewModel.pairSelected()
                },
                onForget = {
                    viewModel.selectDevice(it)
                    viewModel.forgetSelected()
                }
            )

            OutlinedButton(
                onClick = { viewModel.openManualIpDialog() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Accent)
            ) {
                Text("Add TV by IP", color = Accent, fontSize = 12.sp)
            }

            HorizontalDivider(color = CardBorder)

            Text(
                text = "ADVANCED",
                color = Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = uiState.activeProtocol == ProtocolType.OFFICIAL_TLS,
                    onClick = { viewModel.toggleProtocol(ProtocolType.OFFICIAL_TLS) },
                    label = { Text("TLS 6466") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent,
                        selectedLabelColor = Color.Black
                    )
                )
                FilterChip(
                    selected = uiState.activeProtocol == ProtocolType.ADB_FALLBACK,
                    onClick = { viewModel.toggleProtocol(ProtocolType.ADB_FALLBACK) },
                    label = { Text("ADB 5555") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent,
                        selectedLabelColor = Color.Black
                    )
                )
            }

            Text(
                text = "TLS is recommended. ADB requires Developer options and Network debugging on the TV.",
                color = TextSecondary,
                fontSize = 11.sp
            )

            if (uiState.isConnected) {
                OutlinedButton(
                    onClick = {
                        viewModel.disconnect()
                        viewModel.dismissDevicesSheet()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Danger)
                ) {
                    Text("Disconnect", color = Danger, fontSize = 12.sp)
                }
            }

            OutlinedButton(
                onClick = {
                    viewModel.toggleDiagnostics()
                    viewModel.dismissDevicesSheet()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Text(
                    if (uiState.isDiagnosticsVisible) "Hide diagnostic logs" else "Show diagnostic logs",
                    color = TextPrimary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun DpadControlSection(onCommand: (RemoteCommand) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(CardBackground)
                .border(1.dp, CardBorder, RoundedCornerShape(percent = 50))
                .pointerInput(Unit) {
                    detectTapGestures { position ->
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        val dx = position.x - width / 2f
                        val dy = position.y - height / 2f
                        val normalizedX = dx / (width / 2f)
                        val normalizedY = dy / (height / 2f)

                        // Ignore taps outside the oval.
                        if (normalizedX * normalizedX + normalizedY * normalizedY > 1f) {
                            return@detectTapGestures
                        }

                        val distanceFromCenter = sqrt(dx * dx + dy * dy)
                        val command = when {
                            distanceFromCenter < minOf(width, height) * 0.16f ->
                                RemoteCommand.DpadCenter

                            abs(dx) > abs(dy) ->
                                if (dx > 0) RemoteCommand.DpadRight else RemoteCommand.DpadLeft

                            else ->
                                if (dy > 0) RemoteCommand.DpadDown else RemoteCommand.DpadUp
                        }
                        onCommand(command)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val gap = 2.5f
                val sweep = 90f - gap
                val sectors = listOf(
                    (-45f + gap / 2f) to Color(0xFF1A2639),
                    (45f + gap / 2f) to Color(0xFF203049),
                    (135f + gap / 2f) to Color(0xFF1A2639),
                    (225f + gap / 2f) to Color(0xFF203049)
                )

                sectors.forEach { (startAngle, color) ->
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = true,
                        topLeft = Offset.Zero,
                        size = size
                    )
                }

                val radius = minOf(size.width, size.height) * 0.16f
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(color = Color(0xFF2563EB), radius = radius, center = center)
                drawCircle(
                    color = Color(0xFF60A5FA),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            Text(
                text = "▲",
                color = Accent,
                fontSize = 22.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
            )
            Text(
                text = "▼",
                color = Accent,
                fontSize = 22.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            )
            Text(
                text = "◄",
                color = Accent,
                fontSize = 22.sp,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 42.dp)
            )
            Text(
                text = "►",
                color = Accent,
                fontSize = 22.sp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 42.dp)
            )
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "OK",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun ActionButtonsSection(onCommand: (RemoteCommand) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { onCommand(RemoteCommand.Power) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Danger,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("POWER", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { onCommand(RemoteCommand.Home) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF222F43),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("HOME")
            }
            Button(
                onClick = { onCommand(RemoteCommand.Back) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF222F43),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("BACK")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { onCommand(RemoteCommand.VolumeDown) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("VOL -")
            }
            Button(
                onClick = { onCommand(RemoteCommand.Mute) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("MUTE")
            }
            Button(
                onClick = { onCommand(RemoteCommand.VolumeUp) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("VOL +")
            }
        }
    }
}

@Composable
private fun AppLauncherSection(onCommand: (RemoteCommand) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        OutlinedButton(
            onClick = { onCommand(RemoteCommand.YouTube) },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Color(0xFFFF0000))
        ) {
            Text("YouTube", color = Color(0xFFFF0000), fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = { onCommand(RemoteCommand.Netflix) },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Color(0xFFE50914))
        ) {
            Text("Netflix", color = Color(0xFFE50914), fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = { onCommand(RemoteCommand.PrimeVideo) },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Color(0xFF00A8E1))
        ) {
            Text("Prime", color = Color(0xFF00A8E1), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DiagnosticLogSection(logs: List<String>, onHide: () -> Unit) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF070A10)),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "DIAGNOSTIC LOG STREAM",
                    color = Accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onHide) {
                    Text("Hide", color = Accent, fontSize = 11.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(logs) { log ->
                    Text(text = "• $log", color = TextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun PairingPinDialog(
    pinInput: String,
    deviceName: String,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with $deviceName") },
        text = {
            Column {
                Text("Enter the 6-character code shown on the TV (0-9, A-F).")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = onPinChange,
                    label = { Text("Pairing code") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = onSubmit) {
                Text("Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ManualIpDialog(
    ipInput: String,
    onIpChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add TV by IP") },
        text = {
            Column {
                Text("Enter the Android TV IP address (e.g. 192.168.1.50).")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = ipInput,
                    onValueChange = onIpChange,
                    label = { Text("TV IP address") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = onSubmit) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun statusColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.CONNECTED -> Accent
    ConnectionStatus.DISCOVERING,
    ConnectionStatus.FOUND,
    ConnectionStatus.CONNECTING,
    ConnectionStatus.PAIRING -> Color(0xFFFFB300)

    ConnectionStatus.ERROR -> Danger
    else -> Color.Gray
}
