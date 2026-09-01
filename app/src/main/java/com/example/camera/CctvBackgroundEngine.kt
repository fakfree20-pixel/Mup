package com.example.camera

import android.app.Application
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.data.db.AppDatabase
import com.example.data.model.CameraLens
import com.example.data.model.SecurityEvent
import com.example.network.CctvDiscovery
import com.example.network.CctvHttpServer
import com.example.webrtc.WebRtcSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object CctvBackgroundEngine {
    private var isInitialized = false
    val backgroundScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // We will migrate things here
}
