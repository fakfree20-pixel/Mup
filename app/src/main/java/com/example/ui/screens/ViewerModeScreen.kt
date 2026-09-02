package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.components.BatteryStatusChip
import com.example.ui.components.CameraPhoneBatteryBadge
import com.example.ui.components.WebRtcVideoPlayer
import com.example.ui.strings.AppLanguage
import com.example.ui.theme.*
import com.example.ui.viewmodel.CctvViewModel
import com.example.webrtc.WebRtcConnectionState

@Composable
fun ViewerModeScreen(
    viewModel: CctvViewModel,
    language: AppLanguage,
    onBackToSelection: () -> Unit
) {
    val context = LocalContext.current
    val isConnected by viewModel.cctvClient.isConnected.collectAsState()
    val isConnecting by viewModel.cctvClient.isConnecting.collectAsState()
    val latestFrame by viewModel.cctvClient.latestFrame.collectAsState()
    val webRtcSession = viewModel.viewerWebRtcSession
    val webRtcConnState = webRtcSession?.connectionState?.collectAsState()?.value ?: WebRtcConnectionState.DISCONNECTED
    val webRtcVideoTrack by (webRtcSession?.remoteVideoTrack ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    
    val savedCameras by viewModel.savedCameras.collectAsState()
    val isViewerWebRtcActive by viewModel.isViewerWebRtcActive.collectAsState()
    val isRemoteMicOn by viewModel.cctvClient.isRemoteMicListening.collectAsState()
    val isViewerMicOn by viewModel.isViewerMicTalking.collectAsState()
    val roomPinInput by viewModel.viewerRoomPinInput.collectAsState()
    val webRtcStatus by viewModel.webRtcStatus.collectAsState()
    val isAudioOnlyMode by viewModel.isAudioOnlyMode.collectAsState()
    val isVoiceFilterEnabled by viewModel.isVoiceFilterEnabled.collectAsState()
    val remoteTelemetry by viewModel.remoteTelemetry.collectAsState()

    var showSecurityLockDialog by remember { mutableStateOf(false) }
    var securityPinInput by remember { mutableStateOf("") }


    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleViewerMic()
        }
    }
    
    val isAnyConnected = isConnected || (isViewerWebRtcActive && webRtcConnState == WebRtcConnectionState.CONNECTED)
    val isAttemptingConnection = isConnecting || (isViewerWebRtcActive && (
        webRtcConnState == WebRtcConnectionState.CONNECTING_SIGNALING ||
        webRtcConnState == WebRtcConnectionState.WAITING_PEER ||
        webRtcConnState == WebRtcConnectionState.EXCHANGING_SDP ||
        webRtcConnState == WebRtcConnectionState.CONNECTING_P2P
    ))

    androidx.activity.compose.BackHandler(enabled = isAnyConnected || isAttemptingConnection) {
        viewModel.disconnectViewer()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.disconnectViewer()
        }
    }

    // If there's a saved camera and not connected yet, pre-fill PIN
    LaunchedEffect(savedCameras) {
        if (savedCameras.isNotEmpty() && roomPinInput.isBlank()) {
            val mostRecent = savedCameras.first()
            viewModel.setViewerRoomPinInput(mostRecent.cameraId)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (isAnyConnected) {
            // 1. FULL SCREEN LIVE VIDEO or AUDIO ONLY
            if (isAudioOnlyMode) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF0F1117)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .background(Color(0x2210B981), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = "Audio Only",
                                tint = CctvSuccessGreen,
                                modifier = Modifier.size(60.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "🎧 ऑडियो-ओनली मोड (सिर्फ आवाज)",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "पुराने फोन के सामने हो रही बात साफ सुनी जा रही है।\nवीडियो बंद है ताकि बैटरी और डेटा बचे।",
                            color = Color(0xFFA0AEC0),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(36.dp))
                        Button(
                            onClick = { viewModel.toggleAudioOnlyMode() },
                            colors = ButtonDefaults.buttonColors(containerColor = CctvSuccessGreen),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.height(50.dp).fillMaxWidth(0.7f)
                        ) {
                            Icon(Icons.Default.Videocam, contentDescription = "Turn on video", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("वीडियो चालू करें", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (isViewerWebRtcActive && webRtcSession != null) {
                WebRtcVideoPlayer(
                    videoTrack = webRtcVideoTrack,
                    eglBase = webRtcSession.eglBase,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (latestFrame != null) {
                Image(
                    bitmap = latestFrame!!.asImageBitmap(),
                    contentDescription = "Stream",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            
            // 2. ON-SCREEN CAMERA CONTROLS (Overlaid on video)
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp, start = 16.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Close/Disconnect Button
                    IconButton(
                        onClick = { 
                            viewModel.disconnectViewer()
                        },
                        modifier = Modifier.size(44.dp).background(Color(0x77000000), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }

                    // Center: Live Status Pill & Old Phone Battery Badge
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0x99000000),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x44FFFFFF))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(CctvSuccessGreen)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "LIVE",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // 🔋 Live Camera Phone Battery Level (Old Phone Battery)
                        CameraPhoneBatteryBadge(
                            level = remoteTelemetry.batteryLevel,
                            isCharging = remoteTelemetry.isCharging,
                            showLabel = true,
                            isHindi = (language == AppLanguage.HINDI)
                        )
                    }


                    // Right Controls Row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Voice Isolation Filter Toggle Button
                        IconButton(
                            onClick = { viewModel.toggleVoiceFilter() },
                            modifier = Modifier
                                .size(44.dp)
                                .background(
                                    if (isVoiceFilterEnabled) Color(0xFF10B981).copy(alpha = 0.85f) else Color(0x77000000),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = if (isVoiceFilterEnabled) Icons.Default.GraphicEq else Icons.Default.VolumeOff,
                                contentDescription = "Voice Isolation",
                                tint = Color.White
                            )
                        }

                        // 2. Remote Security Lock Button
                        IconButton(
                            onClick = { showSecurityLockDialog = true },
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color(0xFF673AB7).copy(alpha = 0.85f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Security Lock",
                                tint = Color.White
                            )
                        }

                        // 3. Listen to Remote Mic Toggle
                        IconButton(
                            onClick = { viewModel.toggleRemoteMic() },
                            modifier = Modifier.size(44.dp).background(Color(0x77000000), CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isRemoteMicOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff, 
                                contentDescription = "Listen", 
                                tint = if (isRemoteMicOn) CctvSuccessGreen else Color.White
                            )
                        }
                    }
                }
                
                // Bottom Bar Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 36.dp, start = 28.dp, end = 28.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Two Way Voice Talk (माइक से बोलें)
                    IconButton(
                        onClick = {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED

                            if (hasPermission) {
                                viewModel.toggleViewerMic()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier
                            .size(54.dp)
                            .background(
                                if (isViewerMicOn) CctvSuccessGreen else Color(0x77000000), 
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isViewerMicOn) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = "2-Way Talk",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    
                    // Shutter Button (Snapshot / फोटो खींचें)
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable { viewModel.takeRemoteSnapshot() },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .border(3.dp, Color.Black, CircleShape)
                        )
                    }
                    
                    // Remote Switch Camera (Front/Back)
                    IconButton(
                        onClick = { viewModel.sendRemoteCommand("SWITCH_CAMERA") },
                        modifier = Modifier
                            .size(54.dp)
                            .background(Color(0x77000000), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Cameraswitch, 
                            contentDescription = "Switch Camera", 
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // Audio Only Mode Toggle Button
                    IconButton(
                        onClick = { viewModel.toggleAudioOnlyMode() },
                        modifier = Modifier
                            .size(54.dp)
                            .background(
                                if (isAudioOnlyMode) CctvSuccessGreen else Color(0x77000000), 
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isAudioOnlyMode) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = "Audio Only",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        } else {
            // 3. ENTER ROOM PIN / CONNECT SCREEN
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CctvDarkBg)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (isAttemptingConnection) {
                    CircularProgressIndicator(
                        color = CctvIceBlue,
                        modifier = Modifier.size(52.dp),
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = if (language == AppLanguage.HINDI) "कैमरा से कनेक्ट हो रहा है..." else "Connecting to Camera...",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = webRtcStatus,
                        color = CctvTextMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(28.dp))
                    OutlinedButton(
                        onClick = { viewModel.disconnectWebRtc() },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CctvAlertRed)
                    ) {
                        Text(if (language == AppLanguage.HINDI) "रद्द करें (Cancel)" else "Cancel")
                    }
                } else {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, CctvCardBorder, RoundedCornerShape(24.dp)),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = CctvCardBg)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = null,
                                tint = CctvIceBlue,
                                modifier = Modifier.size(40.dp)
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Text(
                                text = if (language == AppLanguage.HINDI) "कैमरा रूम पिन डालें" else "Enter Camera Room PIN",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            Text(
                                text = if (language == AppLanguage.HINDI) "पुराने फोन में दिख रहा 6-अंकों का कोड यहाँ लिखें" else "Enter the 6-digit PIN from the Old Phone",
                                color = CctvTextSecondary,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                            
                            Spacer(modifier = Modifier.height(20.dp))
                            
                            OutlinedTextField(
                                value = roomPinInput,
                                onValueChange = { viewModel.setViewerRoomPinInput(it.filter { ch -> ch.isLetterOrDigit() }.uppercase()) },
                                placeholder = { 
                                    Text(
                                        "123456", 
                                        color = CctvTextMuted, 
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center
                                    ) 
                                },
                                textStyle = LocalTextStyle.current.copy(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.Center,
                                    letterSpacing = 4.sp
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = CctvNavyHover,
                                    unfocusedBorderColor = CctvCardBorder,
                                    focusedContainerColor = CctvNavyDark,
                                    unfocusedContainerColor = CctvDarkBg
                                ),
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            Spacer(modifier = Modifier.height(24.dp))
                            
                            Button(
                                onClick = { viewModel.connectWebRtc(roomPinInput) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CctvNavyPrimary,
                                    contentColor = CctvIceBlue
                                )
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (language == AppLanguage.HINDI) "लाइव वीडियो चालू करें" else "START LIVE VIDEO",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- MASTER REMOTE SECURITY & PHONE LOCK DIALOG ---
        if (showSecurityLockDialog) {
            AlertDialog(
                onDismissRequest = { showSecurityLockDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = Color(0xFF9C27B0),
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        text = if (language == AppLanguage.HINDI) "🔒 पुराना फोन रिमोट लॉक व सुरक्षा" else "🔒 Remote Security & Lock",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(
                            text = if (language == AppLanguage.HINDI)
                                "नई मोबाइल से यहाँ नया लॉक कोड सेट करें। कोड डालते ही पुराना फोन तुरंत लॉक हो जाएगा ताकि कोई उसे डिलीट या बंद न कर सके।"
                            else
                                "Set a security PIN to remotely lock the camera phone. The old phone will be locked instantly to prevent unauthorized deletion or tampering.",
                            fontSize = 13.sp,
                            color = Color(0xFFCBD5E1),
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // 🔋 Live Battery Status of Old Phone
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF261D3B))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (language == AppLanguage.HINDI) "🔋 पुराने फोन की बैटरी:" else "🔋 Old Phone Battery:",
                                        fontSize = 13.sp,
                                        color = Color(0xFFD1C4E9),
                                        fontWeight = FontWeight.Bold
                                    )
                                    CameraPhoneBatteryBadge(
                                        level = remoteTelemetry.batteryLevel,
                                        isCharging = remoteTelemetry.isCharging,
                                        showLabel = false,
                                        isHindi = (language == AppLanguage.HINDI)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (language == AppLanguage.HINDI) {
                                        if (remoteTelemetry.isCharging) "⚡ चार्जर लगा हुआ है (Charging - ${remoteTelemetry.batteryLevel}%)" else "🔋 बैटरी पर चल रहा है (${remoteTelemetry.batteryLevel}%)"
                                    } else {
                                        if (remoteTelemetry.isCharging) "⚡ Charger connected (Charging - ${remoteTelemetry.batteryLevel}%)" else "🔋 Running on battery (${remoteTelemetry.batteryLevel}%)"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (remoteTelemetry.isCharging) Color(0xFF00E676) else if (remoteTelemetry.batteryLevel <= 20) Color(0xFFFF5252) else Color(0xFFB0BEC5)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))


                        Text(
                            text = if (language == AppLanguage.HINDI) "सुरक्षा पिन (4-6 अंक):" else "Security PIN (4-6 digits):",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = securityPinInput,
                            onValueChange = { if (it.length <= 6 && it.all { ch -> ch.isDigit() }) securityPinInput = it },
                            placeholder = { Text("उदा. 1234 या 5678", color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFAB47BC),
                                unfocusedBorderColor = Color(0xFF4A148C),
                                focusedContainerColor = Color(0xFF1E1B2E),
                                unfocusedContainerColor = Color(0xFF12111A)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Lock Button
                        Button(
                            onClick = {
                                val pinToSet = if (securityPinInput.isNotBlank()) securityPinInput else "1234"
                                viewModel.setRemoteSecurityPin(pinToSet)
                                showSecurityLockDialog = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF673AB7),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (language == AppLanguage.HINDI) "🔒 कोड डालें व फोन तुरंत लॉक करें" else "🔒 Set PIN & Lock Old Phone",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Unlock Button
                        OutlinedButton(
                            onClick = {
                                viewModel.unlockCameraScreenRemotely()
                                showSecurityLockDialog = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF4CAF50)
                            )
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (language == AppLanguage.HINDI) "🔓 पुराना फोन अनलॉक करें" else "🔓 Unlock Camera Remotely",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Voice Isolation Filter Button
                        Button(
                            onClick = {
                                viewModel.toggleVoiceFilter()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isVoiceFilterEnabled) Color(0xFF00897B) else Color(0xFF455A64),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isVoiceFilterEnabled) {
                                    if (language == AppLanguage.HINDI) "🎙️ गाड़ी का शोर बंद (फ़िल्टर ON)" else "🎙️ Traffic Noise Filter (ON)"
                                } else {
                                    if (language == AppLanguage.HINDI) "🎙️ नॉइज़ फ़िल्टर चालू करें" else "🎙️ Turn On Noise Filter"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Stealth Black Screen
                        Button(
                            onClick = {
                                viewModel.remoteToggleBlackout()
                                showSecurityLockDialog = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF263238),
                                contentColor = Color(0xFFB0BEC5)
                            )
                        ) {
                            Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (language == AppLanguage.HINDI) "🕶️ स्टील्थ स्क्रीन (काली स्क्रीन)" else "🕶️ Stealth Black Screen",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSecurityLockDialog = false }) {
                        Text(if (language == AppLanguage.HINDI) "बंद करें" else "Close", color = Color(0xFFCE93D8))
                    }
                },
                containerColor = Color(0xFF181524),
                shape = RoundedCornerShape(24.dp)
            )
        }
    }
}
