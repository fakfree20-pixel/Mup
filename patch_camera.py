import sys

new_camera_content = """package com.example.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.strings.AppLanguage
import com.example.ui.viewmodel.CctvViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CameraModeScreen(
    viewModel: CctvViewModel,
    language: AppLanguage,
    onStopCamera: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    
    // Fake states for the UI to look like a real camera
    var isMicOn by remember { mutableStateOf(true) }
    
    LaunchedEffect(Unit) {
        viewModel.startCameraMode(lifecycleOwner, previewViewRef) // Actually starts the background streaming!
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        
        // 1. Full Screen Video Preview (Looks like normal camera)
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    previewViewRef = this
                    viewModel.startCameraMode(lifecycleOwner, this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // 2. Camera Controls Overlay
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
                IconButton(onClick = { onStopCamera() }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
                
                IconButton(onClick = { isMicOn = !isMicOn }) {
                    Icon(
                        imageVector = if (isMicOn) Icons.Default.Mic else Icons.Default.MicOff, 
                        contentDescription = "Mic", 
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
                // Fake Gallery Thumbnail
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.DarkGray)
                )
                
                // Fake Shutter Button
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable { /* Fake snapshot */ },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
                
                // Switch Camera Button
                IconButton(
                    onClick = { viewModel.toggleCameraLens() },
                    modifier = Modifier.size(48.dp).background(Color(0x66000000), CircleShape)
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "Switch", tint = Color.White)
                }
            }
        }
    }
}
"""

with open("app/src/main/java/com/example/ui/screens/CameraModeScreen.kt", "w") as f:
    f.write(new_camera_content)
