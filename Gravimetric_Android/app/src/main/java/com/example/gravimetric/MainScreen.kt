package com.example.gravimetric

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gravimetric.ui.theme.CoffeeBrown
import com.example.gravimetric.ui.theme.EspressoDark
import com.example.gravimetric.ui.theme.LatteCream


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mass: String,
    targetMass: String,
    isConnected: Boolean,
    logMessages: List<String>,
    shotInProgress: Boolean,
    liveMassReadings: List<MassReading>,    //  rolling data
    shotLogReadings: List<ShotLogEntry> = emptyList(),     // shot data
    onStartShotLoggingClick: () -> Unit,
    onStopShotLoggingClick: () -> Unit,
    onExportCsvClick: () -> Unit,
    onReconnectClick: () -> Unit,
    onTareClick: () -> Unit,
    onStartShotClick: () -> Unit,
    onStopShotClick: () -> Unit,
    onReadTargetMassClick: () -> Unit,
    onWriteTargetMassClick: (Float) -> Unit
){
    var targetMassInput by remember { mutableStateOf(targetMass) }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    // Pick which dataset to show in the chart:
    val chartData = if (shotInProgress) {
        shotLogReadings
    } else {
        // Convert each MassReading to ShotLogEntry
        liveMassReadings.map { reading ->
            ShotLogEntry(
                shotId = 0,  // or -1, or something
                timestamp = reading.timestamp,
                shotTimeMs = 0, // or maybe reading.timestamp - firstReadingTimestamp
                mass = reading.mass,
                shotInProgress = reading.shotInProgress
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gravimetric Scale") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CoffeeBrown,
                    titleContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1) Chart at the top, 1/3 screen height
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(screenHeight / 3)
            ) {
                // Pass rolling mass data to MassLineChart
                MassLineChart(
                    dataPoints = chartData,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 2) Main content below
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // --- Row for Mass Reading
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mass Reading:", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = mass,
                        style = MaterialTheme.typography.headlineMedium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- Row for Target Mass
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Target Mass:", style = MaterialTheme.typography.bodyLarge)

                    TextField(
                        value = targetMassInput,
                        onValueChange = { newValue ->
                            // Let user clear or type numeric
                            if (newValue.isEmpty()) {
                                targetMassInput = ""
                            } else {
                                val inputVal = newValue.toFloatOrNull()
                                if (inputVal != null && inputVal in 0.0..99.9) {
                                    targetMassInput = newValue
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 28.sp,
                            textAlign = TextAlign.Center,
                            color = EspressoDark
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .width(120.dp) // So it doesn't push everything off-screen
                            .clip(MaterialTheme.shapes.medium),
                        colors = TextFieldDefaults.colors (
                            focusedContainerColor = LatteCream,
                            unfocusedContainerColor = LatteCream,
                            disabledContainerColor = LatteCream,
                            errorContainerColor = LatteCream,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            errorIndicatorColor = Color.Transparent
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Target Mass Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = onReadTargetMassClick) { Text("Read Target") }
                    Button(onClick = {
                        onWriteTargetMassClick(targetMassInput.toFloatOrNull() ?: 0f)
                    }) { Text("Set Target") }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Control Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = onReconnectClick) { Text("Reconnect") }
                    Button(onClick = onTareClick) { Text("Tare") }
                    if (shotInProgress) {
                        Button(onClick = onStopShotClick) { Text("Stop Shot") }
                    } else {
                        Button(onClick = onStartShotClick) { Text("Start Shot") }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Shot Logging Button(s)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    if (shotInProgress) {
                        // If shot is in progress, show "Stop Shot Logging"
                        Button(onClick = onStopShotLoggingClick) { Text("Stop Shot Logging") }
                    } else {
                        // Otherwise show "Start Shot Logging"
                        Button(onClick = onStartShotLoggingClick) { Text("Start Shot Logging") }
                    }

                    Button(onClick = { onExportCsvClick() }) {
                        Text("Export CSV")
                    }

                }

                Spacer(modifier = Modifier.height(16.dp))

                // Log Activity
                Text("Log Activity:", style = MaterialTheme.typography.bodyMedium)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(LatteCream)
                        //.padding(8.dp)
                ) {
                    Column {
                        logMessages.takeLast(5).forEach { log ->
                            Text(
                                log,
                                style = MaterialTheme.typography.bodySmall,
                                color = EspressoDark
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExportCsvDialog(
    onDismiss: () -> Unit,
    onCreateNew: () -> Unit,
    onAppendExisting: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("Export CSV") },
        text = { Text("Choose an option to export your shot data.") },
        confirmButton = {
            Button(onClick = {
                onCreateNew()
                onDismiss()
            }) {
                Text("Create New")
            }
        },
        dismissButton = {
            Button(onClick = {
                onAppendExisting()
                onDismiss()
            }) {
                Text("Append Existing")
            }
        }
    )
}
