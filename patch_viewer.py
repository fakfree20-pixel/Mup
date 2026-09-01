import sys

new_viewer_content = """package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.WebRtcVideoPlayer
import com.example.ui.strings.AppLanguage
import com.example.webrtc.WebRtcConnectionState
import com.example.ui.viewmodel.CctvViewModel

@Composable
fun ViewerModeScreen(
    viewModel: CctvViewModel,
    language: AppLanguage,
    onBackToSelection: () -> Unit
) {
    val isConnected by viewModel.cctvClient.isConnected.collectAsState()
    val isConnecting by viewModel.cctvClient.isConnecting.collectAsState()
    val latestFrame by viewModel.cctvClient.latestFrame.collectAsState()
    val webRtcConnState by viewModel.webRtcConnectionState.collectAsState()
    val webRtcVideoTrack by viewModel.remoteVideoTrack.collectAsState()
    val webRtcSession by viewModel.webRtcSession.collectAsState()
    val savedCameras by viewModel.savedCameras.collectAsState()
    
    val isViewerWebRtcActive by viewModel.isViewerWebRtcActive.collectAsState()
    val isRemoteMicOn by viewModel.cctvClient.isRemoteMicListening.collectAsState()
    val isViewerMicOn by viewModel.cctvClient.isTwoWayTalkActive.collectAsState()
    
    val isAnyConnected = isConnected || (isViewerWebRtcActive && webRtcConnState == WebRtcConnectionState.CONNECTED)
    
    LaunchedEffect(savedCameras, isAnyConnected) {
        if (!isAnyConnected && savedCameras.isNotEmpty()) {
            val mostRecent = savedCameras.first()
            viewModel.setViewerRoomPinInput(mostRecent.cameraId)
            viewModel.connectWebRtc(mostRecent.cameraId)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (isAnyConnected) {
            // FULL SCREEN VIDEO PLAYER
            if (isViewerWebRtcActive && webRtcSession != null) {
                WebRtcVideoPlayer(
                    videoTrack = webRtcVideoTrack,
                    eglBase = webRtcSession!!.eglBase,
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
            
            // CAMERA CONTROLS OVERLAY (Like a normal camera)
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { 
                        if (isViewerWebRtcActive) viewModel.disconnectWebRtc() else viewModel.disconnectViewer()
                        onBackToSelection() 
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                    
                    // Remote Mic Toggle
                    IconButton(onClick = { viewModel.toggleRemoteMic() }) {
                        Icon(
                            imageVector = if (isRemoteMicOn) Icons.Default.Mic else Icons.Default.MicOff, 
                            contentDescription = "Remote Mic", 
                            tint = Color.White
                        )
                    }
                }
                
                // Bottom Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp, start = 32.dp, end = 32.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Two Way Talk
                    IconButton(
                        onClick = { viewModel.toggleTwoWayTalk() },
                        modifier = Modifier.size(48.dp).background(if (isViewerMicOn) Color.Green else Color(0x66000000), CircleShape)
                    ) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = "Talk", tint = Color.White)
                    }
                    
                    // Shutter Button (Snapshot)
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable { viewModel.takeRemoteSnapshot() },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .border(2.dp, Color.Black, CircleShape)
                        )
                    }
                    
                    // Remote Switch Camera Button
                    IconButton(
                        onClick = { viewModel.flipRemoteCamera() },
                        modifier = Modifier.size(48.dp).background(Color(0x66000000), CircleShape)
                    ) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "Switch Camera", tint = Color.White)
                    }
                }
            }
        } else {
            // CONNECTING / MANUAL CONNECT SCREEN
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (isConnecting || webRtcConnState == WebRtcConnectionState.CONNECTING_SIGNALING || webRtcConnState == WebRtcConnectionState.WAITING_PEER) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Connecting to camera...", color = Color.White)
                } else {
                    Text(
                        text = "Enter Camera PIN to Connect",
                        color = Color.White,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    val roomPinInput by viewModel.viewerRoomPinInput.collectAsState()
                    OutlinedTextField(
                        value = roomPinInput,
                        onValueChange = { viewModel.setViewerRoomPinInput(it.uppercase()) },
                        placeholder = { Text("e.g. A1B2", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { viewModel.connectWebRtc(roomPinInput) },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text("CONNECT VIDEO", fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
"""

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "w") as f:
    f.write(new_viewer_content)
