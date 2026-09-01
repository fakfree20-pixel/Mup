package com.example.ui.screens

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    
    val isAnyConnected = isConnected || (isViewerWebRtcActive && webRtcConnState == WebRtcConnectionState.CONNECTED)
    val isAttemptingConnection = isConnecting || (isViewerWebRtcActive && (
        webRtcConnState == WebRtcConnectionState.CONNECTING_SIGNALING ||
        webRtcConnState == WebRtcConnectionState.WAITING_PEER ||
        webRtcConnState == WebRtcConnectionState.EXCHANGING_SDP ||
        webRtcConnState == WebRtcConnectionState.CONNECTING_P2P
    ))

    // If there's a saved camera and not connected yet, pre-fill PIN
    LaunchedEffect(savedCameras) {
        if (savedCameras.isNotEmpty() && roomPinInput.isBlank()) {
            val mostRecent = savedCameras.first()
            viewModel.setViewerRoomPinInput(mostRecent.cameraId)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (isAnyConnected) {
            // 1. FULL SCREEN LIVE VIDEO
            if (isViewerWebRtcActive && webRtcSession != null) {
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
                            if (isViewerWebRtcActive) viewModel.disconnectWebRtc() else viewModel.disconnectViewer()
                        },
                        modifier = Modifier.size(44.dp).background(Color(0x77000000), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }

                    // Live Status Pill
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0x99000000),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x44FFFFFF))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(CctvSuccessGreen)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "LIVE • 1080p",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    
                    // Listen to Remote Mic Toggle
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
                        onClick = { viewModel.toggleViewerMic() },
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
    }
}
