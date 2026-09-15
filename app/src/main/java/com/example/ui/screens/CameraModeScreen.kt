package com.example.ui.screens

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.BatteryStatusChip
import com.example.ui.strings.AppLanguage
import com.example.ui.theme.*
import com.example.ui.viewmodel.CctvViewModel

@Composable
fun CameraModeScreen(
    viewModel: CctvViewModel,
    language: AppLanguage,
    onStopCamera: () -> Unit,
    onStartScreenMirroring: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboardManager = LocalClipboardManager.current
    
    val roomPin by viewModel.cameraRoomPin.collectAsState()
    val connectedViewers by viewModel.connectedViewersCount.collectAsState()
    val isVoiceFilterEnabled by viewModel.isVoiceFilterEnabled.collectAsState()
    val cameraTelemetry by viewModel.cameraTelemetry.collectAsState()
    var isBlackScreenSaverActive by remember { mutableStateOf(false) }

    // 1. Keep display awake so Android never suspends the camera hardware while operating
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Start background CCTV service immediately on entering this screen
    LaunchedEffect(Unit) {
        viewModel.startCameraMode(lifecycleOwner, null)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CctvDarkBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // 1. Status Indicator Badge & Battery
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(30.dp),
                        color = CctvCardBgSecondary,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (connectedViewers > 0) Color(0xFFFF1744) else Color(0xFF00E676))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (connectedViewers > 0) {
                                    if (language == AppLanguage.HINDI) "🔴 लाइव स्ट्रीम चालू" else "🔴 Live Streaming ON"
                                } else {
                                    if (language == AppLanguage.HINDI) "💤 स्टैंडबाय (तैयार)" else "💤 Standby (Ready)"
                                },
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Battery Chip
                    BatteryStatusChip(
                        level = cameraTelemetry.batteryLevel,
                        isCharging = cameraTelemetry.isCharging
                    )
                }

                // 2. Large Pairing Code Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CctvCardBorder, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = CctvCardBg)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (language == AppLanguage.HINDI) "पेयरिंग कोड (ROOM PIN)" else "PAIRING CODE (ROOM PIN)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = CctvTextMuted,
                            letterSpacing = 1.5.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Large PIN display
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CctvNavyDark,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(2.dp, CctvNavyPrimary, RoundedCornerShape(12.dp))
                        ) {
                            Text(
                                text = roomPin.chunked(3).joinToString(" "),
                                fontSize = 38.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = CctvIceBlue,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Copy PIN Button
                        FilledTonalButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(roomPin))
                                Toast.makeText(
                                    context,
                                    if (language == AppLanguage.HINDI) "कोड कॉपी हो गया: $roomPin" else "PIN Copied: $roomPin",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = CctvCardBgSecondary,
                                contentColor = CctvTextPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (language == AppLanguage.HINDI) "कोड कॉपी करें" else "Copy PIN",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 3. Voice Isolation DSP Feature Card (The core requested feature)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, if (isVoiceFilterEnabled) Color(0xFF00897B) else CctvCardBorder, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isVoiceFilterEnabled) Color(0xFF004D40).copy(alpha = 0.4f) else CctvCardBg.copy(alpha = 0.6f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(if (isVoiceFilterEnabled) Color(0xFF00897B) else Color(0xFF37474F)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (language == AppLanguage.HINDI) "🎙️ गाड़ी व बाइक का शोर बंद" else "🎙️ Voice Filter (Traffic Noise)",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (language == AppLanguage.HINDI)
                                        "गाड़ियों का भारी शोर हटाकर केवल इंसान की साफ़ आवाज़ सुनाई देगी।"
                                    else
                                        "Removes traffic noise and engine rumble for crystal clear speech.",
                                    fontSize = 12.sp,
                                    color = CctvTextSecondary,
                                    lineHeight = 16.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Switch(
                            checked = isVoiceFilterEnabled,
                            onCheckedChange = { viewModel.toggleVoiceFilter() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF00897B),
                                uncheckedThumbColor = Color.LightGray,
                                uncheckedTrackColor = Color(0xFF263238)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 4. Flashlight / Torch Toggle Card
                val isTorchOn = cameraTelemetry.isTorchOn
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, if (isTorchOn) Color(0xFFFFB300) else CctvCardBorder, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isTorchOn) Color(0xFF5D4037).copy(alpha = 0.4f) else CctvCardBg.copy(alpha = 0.6f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(if (isTorchOn) Color(0xFFFFB300) else Color(0xFF37474F)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                    contentDescription = null,
                                    tint = if (isTorchOn) Color.Black else Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (language == AppLanguage.HINDI) "🔦 फ़्लैश लाइट (Flashlight)" else "🔦 Flashlight",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (language == AppLanguage.HINDI)
                                        "कैमरे की फ्लैश लाइट चालू या बंद करें।"
                                    else
                                        "Turn camera flashlight ON or OFF.",
                                    fontSize = 12.sp,
                                    color = CctvTextSecondary,
                                    lineHeight = 16.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Switch(
                            checked = isTorchOn,
                            onCheckedChange = { viewModel.toggleCameraTorch() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = Color(0xFFFFB300),
                                uncheckedThumbColor = Color.LightGray,
                                uncheckedTrackColor = Color(0xFF263238)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 5. OLED Black Screen Saver (0% Screen Power / Anti-Burn-In)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CctvCardBorder, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = CctvCardBg.copy(alpha = 0.6f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF263238)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DarkMode,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (language == AppLanguage.HINDI) "🌙 ब्लैक स्क्रीन सेवर (बैटरी बचत)" else "🌙 Black Screen Saver",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (language == AppLanguage.HINDI)
                                        "स्क्रीन पूरी तरह काली रहेगी और कैमरा चलता रहेगा (0% स्क्रीन बैटरी खर्च)।"
                                    else
                                        "Screen stays black to save battery while camera streams non-stop.",
                                    fontSize = 12.sp,
                                    color = CctvTextSecondary,
                                    lineHeight = 16.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = { isBlackScreenSaverActive = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = if (language == AppLanguage.HINDI) "चालू करें" else "Enable",
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // 4. Bottom Stop Camera Button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                var showPinDialog by remember { mutableStateOf(false) }
                var enteredPin by remember { mutableStateOf("") }

                OutlinedButton(
                    onClick = {
                        showPinDialog = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = CctvAlertRed
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = CctvAlertRed, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (language == AppLanguage.HINDI) "कैमरा बंद करें (Stop Camera)" else "Stop Camera",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CctvAlertRed
                    )
                }

                if (showPinDialog) {
                    AlertDialog(
                        onDismissRequest = { showPinDialog = false },
                        title = {
                            Text(if (language == AppLanguage.HINDI) "🔒 सुरक्षा कोड (Room PIN) दर्ज करें" else "🔒 Enter Security PIN")
                        },
                        text = {
                            Column {
                                Text(
                                    if (language == AppLanguage.HINDI)
                                        "पुराने फोन से कैमरा बंद करने के लिए रूम पिन दर्ज करें:"
                                    else
                                        "Enter the Room PIN to stop camera mode:"
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = enteredPin,
                                    onValueChange = { enteredPin = it },
                                    singleLine = true,
                                    placeholder = { Text("Enter PIN") }
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (enteredPin.trim() == roomPin.trim()) {
                                        showPinDialog = false
                                        enteredPin = ""
                                        onStopCamera()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            if (language == AppLanguage.HINDI) "❌ गलत पिन! कैमरा बंद नहीं किया जा सकता।" else "❌ Incorrect PIN! Cannot stop camera.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CctvAlertRed)
                            ) {
                                Text(if (language == AppLanguage.HINDI) "कैमरा बंद करें" else "Confirm Stop")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPinDialog = false; enteredPin = "" }) {
                                Text(if (language == AppLanguage.HINDI) "रद्द करें" else "Cancel")
                            }
                        }
                    )
                }
            }
        }

        if (isBlackScreenSaverActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { isBlackScreenSaverActive = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DarkMode,
                        contentDescription = null,
                        tint = Color.DarkGray,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (language == AppLanguage.HINDI)
                            "🔒 ब्लैक स्क्रीन सेवर सक्रिय (0% डिस्प्ले बैटरी खर्च)\nकैमरा 24/7 चालू है\n\nस्क्रीन खोलने के लिए कहीं भी टैप करें"
                        else
                            "🔒 Black Screen Saver Active (0% display power)\nCamera is streaming 24/7\n\nTap anywhere to wake screen",
                        color = Color.Gray,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                }
            }
        }
    }
}
