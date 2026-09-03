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
    val isSpeakerphoneOn by viewModel.isSpeakerphoneOn.collectAsState()
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
                
                // Secondary Controls Row (Torch, Switch Camera, Audio Only)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp, start = 48.dp, end = 48.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Flashlight
                    IconButton(
                        onClick = { viewModel.sendRemoteCommand("TOGGLE_TORCH") },
                        modifier = Modifier
                            .size(48.dp)
                            .background(if (remoteTelemetry.isTorchOn) CctvSuccessGreen else Color(0x77000000), CircleShape)
                    ) {
                        Icon(Icons.Default.FlashlightOn, contentDescription = "Flashlight", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    
                    // Speakerphone Toggle (हैंड्स-फ्री)
                    IconButton(
                        onClick = { viewModel.toggleSpeakerphone() },
                        modifier = Modifier
                            .size(54.dp)
                            .background(
                                if (isSpeakerphoneOn) CctvSuccessGreen else Color(0x77000000), 
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isSpeakerphoneOn) Icons.Default.VolumeUp else Icons.Default.PhoneInTalk,
                            contentDescription = "Speakerphone",
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
                    .background(Color(0xFF0F1117))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (isAttemptingConnection) {
                    CircularProgressIndicator(
                        color = Color(0xFFCE93D8),
                        modifier = Modifier.size(52.dp),
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = if (language == AppLanguage.HINDI) "कैमरा से कनेक्ट हो रहा है..." else "Connecting to Camera...",
                        color = Color.White,
                        fontSize = 16.sp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Camera",
                        modifier = Modifier.size(72.dp),
                        tint = Color(0xFFCE93D8)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = if (language == AppLanguage.HINDI) "कैमरा से जुड़ें" else "Connect to Camera",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (language == AppLanguage.HINDI) "पुराने फोन का रूम पिन डालें" else "Enter old phone's Room PIN",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    OutlinedTextField(
                        value = roomPinInput,
                        onValueChange = { viewModel.setViewerRoomPinInput(it) },
                        label = { Text("Room PIN") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFCE93D8),
                            unfocusedBorderColor = Color.Gray,
                            cursorColor = Color(0xFFCE93D8)
                        ),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        onClick = { viewModel.connectToCamera(roomPinInput) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF673AB7)
                        )
                    ) {
                        Text(
                            text = if (language == AppLanguage.HINDI) "कनेक्ट करें" else "CONNECT",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        
        // Security Lock Dialog
        if (showSecurityLockDialog) {
            AlertDialog(
                onDismissRequest = { showSecurityLockDialog = false },
                title = {
                    Text(
                        text = if (language == AppLanguage.HINDI) "कैमरा नियंत्रण" else "Camera Controls",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
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
                            placeholder = { Text("e.g. 1234", color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFCE93D8),
                                unfocusedBorderColor = Color.Gray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Button(
                            onClick = {
                                val pinToSet = if (securityPinInput.isNotBlank()) securityPinInput else "1234"
                                viewModel.setRemoteSecurityPin(pinToSet)
                                showSecurityLockDialog = false
                            },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF673AB7))
                        ) {
                            Text(if (language == AppLanguage.HINDI) "🔒 लॉक करें" else "🔒 Lock Camera Screen")
                        }
                        
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        OutlinedButton(
                            onClick = {
                                viewModel.unlockCameraScreenRemotely()
                                showSecurityLockDialog = false
                            },
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
                            Text(if (language == AppLanguage.HINDI) "🔓 अनलॉक करें" else "🔓 Unlock Camera Screen")
                        }
                        
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        Button(
                            onClick = { viewModel.toggleVoiceFilter() },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isVoiceFilterEnabled) Color(0xFF00897B) else Color(0xFF455A64))
                        ) {
                            Text(if (isVoiceFilterEnabled) "🎙️ Filter ON" else "🎙️ Filter OFF")
                        }
                        
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        Button(
                            onClick = {
                                viewModel.remoteToggleBlackout()
                                showSecurityLockDialog = false
                            },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238))
                        ) {
                            Text("🕶️ Stealth Black Screen")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSecurityLockDialog = false }) {
                        Text(if (language == AppLanguage.HINDI) "बंद करें" else "Close", color = Color(0xFFCE93D8))
                    }
                },
                containerColor = Color(0xFF181524)
            )
        }
    }
}
