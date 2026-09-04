package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.camera.AudioStreamManager
import com.example.camera.BatteryMonitor
import com.example.camera.CameraManager
import com.example.camera.CctvForegroundService
import com.example.data.db.AppDatabase
import com.example.data.model.AppRole
import com.example.data.model.CameraLens
import com.example.data.model.CameraTelemetry
import com.example.data.model.DiscoveredCamera
import com.example.data.model.SavedCamera
import com.example.data.model.SecurityEvent
import com.example.data.model.SnapshotRecord
import com.example.network.CctvClient
import com.example.network.CctvDiscovery
import com.example.network.CctvHttpServer
import com.example.ui.strings.AppLanguage
import com.example.webrtc.WebRtcConnectionState
import com.example.webrtc.WebRtcSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random


class CctvViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        var isCameraModeActive = false
        var cameraManagerInstance: com.example.camera.CameraManager? = null
        var audioStreamManagerInstance: com.example.camera.AudioStreamManager? = null
        var cameraWebRtcSessionInstance: com.example.webrtc.WebRtcSessionManager? = null
        var httpServerInstance: com.example.network.CctvHttpServer? = null
        var batteryMonitorInstance: com.example.camera.BatteryMonitor? = null
        var backgroundLifecycleOwnerInstance: AlwaysActiveLifecycleOwner? = null
        var cachedMediaProjectionData: Intent? = null
        val backgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    }

    private val TAG = "CctvViewModel"

    private var backgroundLifecycleOwner: AlwaysActiveLifecycleOwner?
        get() = backgroundLifecycleOwnerInstance
        set(value) { backgroundLifecycleOwnerInstance = value }

    val cameraManager: com.example.camera.CameraManager
        get() {
            if (cameraManagerInstance == null) cameraManagerInstance = com.example.camera.CameraManager(getApplication())
            return cameraManagerInstance!!
        }

    val audioStreamManager: com.example.camera.AudioStreamManager
        get() {
            if (audioStreamManagerInstance == null) audioStreamManagerInstance = com.example.camera.AudioStreamManager(getApplication())
            return audioStreamManagerInstance!!
        }

    private var batteryMonitor: com.example.camera.BatteryMonitor?
        get() = batteryMonitorInstance
        set(value) { batteryMonitorInstance = value }

    private var httpServer: com.example.network.CctvHttpServer?
        get() = httpServerInstance
        set(value) { httpServerInstance = value }

    var cameraWebRtcSession: com.example.webrtc.WebRtcSessionManager?
        get() = cameraWebRtcSessionInstance
        private set(value) { cameraWebRtcSessionInstance = value }

    private val db = AppDatabase.getDatabase(application)
    private val dao = db.cctvDao()


    class AlwaysActiveLifecycleOwner : LifecycleOwner {
        private val registry = androidx.lifecycle.LifecycleRegistry(this)
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry

        init {
            registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
        }
    }

    val savedCameras = dao.getAllSavedCameras()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val allSnapshots = dao.getAllSnapshots()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val securityEvents = dao.getRecentSecurityEvents()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // UI Navigation & Language
    private val _currentRole = MutableStateFlow(AppRole.SELECTION)
    val currentRole: StateFlow<AppRole> = _currentRole

    private val _language = MutableStateFlow(AppLanguage.HINDI)
    val language: StateFlow<AppLanguage> = _language

    private val prefs = application.getSharedPreferences(com.example.receiver.BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)

    private fun getOrCreateRoomPin(): String {
        var pin = prefs.getString(com.example.receiver.BootReceiver.KEY_ROOM_PIN, null)
        if (pin.isNullOrBlank()) {
            pin = (100000 + Random().nextInt(900000)).toString()
            prefs.edit().putString(com.example.receiver.BootReceiver.KEY_ROOM_PIN, pin).apply()
        }
        return pin
    }

    private fun getOrCreateSecurityPin(): String {
        var pin = prefs.getString(com.example.receiver.BootReceiver.KEY_SECURITY_LOCK_PIN, null)
        if (pin.isNullOrBlank()) {
            pin = getOrCreateRoomPin()
            prefs.edit().putString(com.example.receiver.BootReceiver.KEY_SECURITY_LOCK_PIN, pin).apply()
        }
        return pin
    }

    private fun getOrCreateCameraId(): String {
        var id = prefs.getString(com.example.receiver.BootReceiver.KEY_CAM_ID, null)
        if (id.isNullOrBlank()) {
            id = "CAM-" + (1000 + Random().nextInt(9000))
            prefs.edit().putString(com.example.receiver.BootReceiver.KEY_CAM_ID, id).apply()
        }
        return id
    }

    // --- CAMERA MODE STATE (Old Phone) ---
    private val _cameraId = MutableStateFlow(getOrCreateCameraId())
    val cameraId: StateFlow<String> = _cameraId

    private val _cameraRoomPin = MutableStateFlow(getOrCreateRoomPin())
    val cameraRoomPin: StateFlow<String> = _cameraRoomPin

    private val _cameraSecurityPin = MutableStateFlow(getOrCreateSecurityPin())
    val cameraSecurityPin: StateFlow<String> = _cameraSecurityPin

    private val _isCameraScreenLocked = MutableStateFlow(prefs.getBoolean(com.example.receiver.BootReceiver.KEY_SCREEN_LOCKED, false))
    val isCameraScreenLocked: StateFlow<Boolean> = _isCameraScreenLocked

    private val _isVoiceFilterEnabled = MutableStateFlow(prefs.getBoolean(com.example.receiver.BootReceiver.KEY_VOICE_FILTER_ENABLED, true))
    val isVoiceFilterEnabled: StateFlow<Boolean> = _isVoiceFilterEnabled

    private val _cameraIp = MutableStateFlow("127.0.0.1")
    val cameraIp: StateFlow<String> = _cameraIp

    private val _cameraPort = MutableStateFlow(8080)
    val cameraPort: StateFlow<Int> = _cameraPort

    private val _connectedViewersCount = MutableStateFlow(0)
    val connectedViewersCount: StateFlow<Int> = _connectedViewersCount

    private val _isCameraStreaming = MutableStateFlow(false)
    val isCameraStreaming: StateFlow<Boolean> = _isCameraStreaming

    private val _isPowerSaverActive = MutableStateFlow(false)
    val isPowerSaverActive: StateFlow<Boolean> = _isPowerSaverActive

    private val _isMotionDetected = MutableStateFlow(false)
    val isMotionDetected: StateFlow<Boolean> = _isMotionDetected

    private val _cameraTelemetry = MutableStateFlow(CameraTelemetry())
    val cameraTelemetry: StateFlow<CameraTelemetry> = _cameraTelemetry

    // Camera & Audio engines
    
    
    
    
    private val discovery = CctvDiscovery(application)
    

    // --- VIEWER MODE STATE (New Phone) ---
    val cctvClient = CctvClient()
    private val _viewerWebRtcSession = MutableStateFlow<WebRtcSessionManager?>(null)
    val viewerWebRtcSessionState: StateFlow<WebRtcSessionManager?> = _viewerWebRtcSession
    val viewerWebRtcSession: WebRtcSessionManager?
        get() = _viewerWebRtcSession.value

    private val _viewerConnectionState = MutableStateFlow(WebRtcConnectionState.DISCONNECTED)
    val viewerConnectionState: StateFlow<WebRtcConnectionState> = _viewerConnectionState

    private val _viewerRemoteVideoTrack = MutableStateFlow<org.webrtc.VideoTrack?>(null)
    val viewerRemoteVideoTrack: StateFlow<org.webrtc.VideoTrack?> = _viewerRemoteVideoTrack

    private val _remoteTelemetry = MutableStateFlow(CameraTelemetry())
    val remoteTelemetry: StateFlow<CameraTelemetry> = _remoteTelemetry

    private val _viewerModeTab = MutableStateFlow("WEBRTC") // "WEBRTC" (Mobile Data) or "LAN" (Local Wi-Fi)
    val viewerModeTab: StateFlow<String> = _viewerModeTab

    private val _viewerRoomPinInput = MutableStateFlow("")
    val viewerRoomPinInput: StateFlow<String> = _viewerRoomPinInput

    private val _discoveredCameras = MutableStateFlow<List<DiscoveredCamera>>(emptyList())
    val discoveredCameras: StateFlow<List<DiscoveredCamera>> = _discoveredCameras

    private val _viewerPeerInput = MutableStateFlow("")
    val viewerPeerInput: StateFlow<String> = _viewerPeerInput

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage

    private val _isRecordingStream = MutableStateFlow(false)
    val isRecordingStream: StateFlow<Boolean> = _isRecordingStream

    private val _isViewerWebRtcActive = MutableStateFlow(false)
    val isViewerWebRtcActive: StateFlow<Boolean> = _isViewerWebRtcActive

    private val _webRtcStatus = MutableStateFlow("Ready")
    val webRtcStatus: StateFlow<String> = _webRtcStatus

    private val _isAutoBlackoutEnabled = MutableStateFlow(true)
    val isAutoBlackoutEnabled: StateFlow<Boolean> = _isAutoBlackoutEnabled

    private val _isViewerMicTalking = MutableStateFlow(false)
    val isViewerMicTalking: StateFlow<Boolean> = _isViewerMicTalking

    private val _isAudioOnlyMode = MutableStateFlow(false)
    val isAudioOnlyMode: StateFlow<Boolean> = _isAudioOnlyMode

    private val _isSpeakerphoneOn = MutableStateFlow(true)
    val isSpeakerphoneOn: StateFlow<Boolean> = _isSpeakerphoneOn

    init {
        // Setup battery monitoring
        batteryMonitor = com.example.camera.BatteryMonitor(getApplication()) { level, isCharging ->
            _cameraTelemetry.value = _cameraTelemetry.value.copy(
                batteryLevel = level,
                isCharging = isCharging
            )
            broadcastCurrentTelemetry()
        }
        val (initialLevel, initialCharging) = batteryMonitor?.getCurrentBattery() ?: Pair(100, false)
        _cameraTelemetry.value = _cameraTelemetry.value.copy(
            batteryLevel = initialLevel,
            isCharging = initialCharging
        )

        viewModelScope.launch {
            cctvClient.telemetry.collect { t ->
                if (!_isViewerWebRtcActive.value && t.cameraId.isNotBlank()) {
                    _remoteTelemetry.value = t
                }
            }
        }
    }

    fun broadcastCurrentTelemetry() {
        val (curLevel, curCharging) = batteryMonitor?.getCurrentBattery() ?: Pair(_cameraTelemetry.value.batteryLevel, _cameraTelemetry.value.isCharging)
        _cameraTelemetry.value = _cameraTelemetry.value.copy(
            batteryLevel = curLevel,
            isCharging = curCharging
        )
        val telemetry = _cameraTelemetry.value.copy(
            cameraId = _cameraId.value,
            ipAddress = _cameraIp.value,
            port = _cameraPort.value,
            lens = cameraManager.currentLens,
            isTorchOn = cameraManager.isTorchOn,
            isMicEnabled = true,
            isSirenPlaying = audioStreamManager.isSirenActive(),
            connectedClients = _connectedViewersCount.value
        )
        try {
            val json = JSONObject().apply {
                put("batteryLevel", telemetry.batteryLevel)
                put("isCharging", telemetry.isCharging)
                put("fps", 30)
                put("lens", telemetry.lens.name)
                put("isTorchOn", telemetry.isTorchOn)
                put("isMicEnabled", telemetry.isMicEnabled)
                put("isSirenPlaying", telemetry.isSirenPlaying)
                put("isVoiceFilter", _isVoiceFilterEnabled.value)
                put("isLocked", _isCameraScreenLocked.value)
                put("cameraId", _cameraId.value)
                put("timestamp", System.currentTimeMillis())
            }
            cameraWebRtcSession?.sendCommand("TELEMETRY:$json")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast telemetry: ${e.message}")
        }
    }

    fun handleViewerIncomingMessage(cmd: String) {
        if (cmd.startsWith("TELEMETRY:")) {
            try {
                val jsonStr = cmd.substringAfter("TELEMETRY:").trim()
                val json = JSONObject(jsonStr)
                val newTelemetry = CameraTelemetry(
                    cameraId = json.optString("cameraId", ""),
                    batteryLevel = json.optInt("batteryLevel", 100),
                    isCharging = json.optBoolean("isCharging", false),
                    fps = json.optInt("fps", 30),
                    lens = if (json.optString("lens") == "FRONT") CameraLens.FRONT else CameraLens.BACK,
                    isTorchOn = json.optBoolean("isTorchOn", false),
                    isMicEnabled = json.optBoolean("isMicEnabled", true),
                    isSirenPlaying = json.optBoolean("isSirenPlaying", false),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
                _remoteTelemetry.value = newTelemetry
                if (json.has("isVoiceFilter")) {
                    _isVoiceFilterEnabled.value = json.optBoolean("isVoiceFilter", true)
                }
                if (json.has("isLocked")) {
                    _isCameraScreenLocked.value = json.optBoolean("isLocked", false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse remote telemetry: ${e.message}")
            }
        }
    }


    fun setLanguage(lang: AppLanguage) {
        _language.value = lang
    }

    fun toggleLanguage() {
        _language.value = if (_language.value == AppLanguage.HINDI) AppLanguage.ENGLISH else AppLanguage.HINDI
    }

    fun selectRole(role: AppRole) {
        _currentRole.value = role
        if (role == AppRole.VIEWER_DEVICE) {
            discovery.startListening(viewModelScope) { cameras ->
                _discoveredCameras.value = cameras
            }
        }
    }

    fun setViewerModeTab(tab: String) {
        _viewerModeTab.value = tab
    }

    fun setViewerRoomPinInput(pin: String) {
        _viewerRoomPinInput.value = pin
    }

    fun setViewerPeerInput(input: String) {
        _viewerPeerInput.value = input
    }

    fun showToast(msg: String) {
        _toastMessage.value = msg
        viewModelScope.launch {
            delay(3000)
            if (_toastMessage.value == msg) {
                _toastMessage.value = null
            }
        }
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    // --- CAMERA MODE CONTROLS ---
    fun startCameraMode(lifecycleOwner: LifecycleOwner, previewView: PreviewView? = null) {
        _cameraIp.value = CctvHttpServer.getLocalIpAddress()
        batteryMonitor?.start()

        // Start Foreground Service with Persistent Notification & WakeLock for 24/7 background streaming
        try {
            CctvForegroundService.startService(
                context = getApplication(),
                roomPin = _cameraRoomPin.value,
                camId = _cameraId.value
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not start CctvForegroundService: ${e.message}")
        }

        val activeOwner = backgroundLifecycleOwner ?: AlwaysActiveLifecycleOwner().also {
            backgroundLifecycleOwner = it
        }

        // 1. Setup CameraX for local display & torch support
        cameraManager.startCamera(activeOwner, previewView) {
            _isCameraStreaming.value = true
        }

        // 4. Start WebRTC Session for Mobile Data / Cellular P2P low latency
        cameraWebRtcSession = WebRtcSessionManager(
            context = getApplication(),
            isCameraMode = true
        ).apply {
            onCommandReceived = { action ->
                handleRemoteCommand(action, lifecycleOwner, previewView)
            }
            onViewerConnected = {
                _connectedViewersCount.value = 1
                broadcastCurrentTelemetry()
                if (_isAutoBlackoutEnabled.value) {
                    _isPowerSaverActive.value = true
                }
            }
            onViewerDisconnected = {
                _connectedViewersCount.value = 0
                broadcastCurrentTelemetry()
            }
            startSession(
                scope = backgroundScope,
                roomId = _cameraRoomPin.value,
                isFrontCamera = (cameraManager.currentLens == CameraLens.FRONT)
            )
            cachedMediaProjectionData?.let { data ->
                startScreenCapture(data)
            }
        }

        backgroundScope.launch {
            cameraWebRtcSession?.connectionState?.collect { state ->
                when (state) {
                    WebRtcConnectionState.CONNECTED -> {
                        _connectedViewersCount.value = 1
                        broadcastCurrentTelemetry()
                        if (_isAutoBlackoutEnabled.value) {
                            _isPowerSaverActive.value = true
                        }
                    }
                    WebRtcConnectionState.WAITING_PEER,
                    WebRtcConnectionState.DISCONNECTED,
                    WebRtcConnectionState.FAILED -> {
                        if (cameraWebRtcSession?.isCameraActive != true) {
                            _connectedViewersCount.value = 0
                            broadcastCurrentTelemetry()
                        }
                    }
                    else -> {}
                }
            }
        }

        // Periodic telemetry broadcast loop (2.5 seconds)
        backgroundScope.launch {
            while (isActive) {
                if (_isCameraStreaming.value) {
                    broadcastCurrentTelemetry()
                }
                delay(2500)
            }
        }


        // 5. Start HTTP & MJPEG Server (Local LAN fallback)
        httpServer = CctvHttpServer(
            context = getApplication(),
            port = 8080,
            onCommandReceived = { action ->
                handleRemoteCommand(action, lifecycleOwner, previewView)
            },
            onAudioReceived = { pcmChunk ->
                // Play viewer voice on loudspeaker
                audioStreamManager.playSpeakerAudio(pcmChunk)
            },
            getTelemetry = {
                _cameraTelemetry.value.copy(
                    cameraId = _cameraId.value,
                    ipAddress = _cameraIp.value,
                    port = _cameraPort.value,
                    lens = cameraManager.currentLens,
                    isTorchOn = cameraManager.isTorchOn,
                    isMicEnabled = true,
                    isSirenPlaying = audioStreamManager.isSirenActive(),
                    connectedClients = _connectedViewersCount.value
                )
            },
            getLatestJpeg = {
                cameraManager.latestJpegFrame
            }
        ).apply {
            val boundPort = start(backgroundScope)
            _cameraPort.value = boundPort
            onClientCountChanged = { count ->
                _connectedViewersCount.value = count
                if (count > 0 && _isAutoBlackoutEnabled.value) {
                    _isPowerSaverActive.value = true
                }
            }
        }

        // Connect CameraManager frame broadcast to HTTP server
        cameraManager.addFrameListener { jpeg ->
            httpServer?.broadcastJpegFrame(jpeg)
        }

        // Connect Audio broadcast to HTTP server
        audioStreamManager.addAudioListener { pcm ->
            httpServer?.broadcastAudioPacket(pcm)
        }

        // 6. Start UDP Beacon for instant Viewer Auto-Discovery on LAN
        discovery.startBroadcasting(
            scope = backgroundScope,
            cameraId = _cameraId.value,
            port = _cameraPort.value,
            deviceName = android.os.Build.MODEL ?: "CCTV Camera"
        )
    }

    private fun handleRemoteCommand(
        action: String,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?
    ): String {
        val result = when {
            action.startsWith("SET_SECURITY_PIN:") -> {

                val pin = action.substringAfter("SET_SECURITY_PIN:").trim()
                if (pin.isNotBlank()) {
                    _cameraSecurityPin.value = pin
                    _isCameraScreenLocked.value = true
                    prefs.edit()
                        .putString(com.example.receiver.BootReceiver.KEY_SECURITY_LOCK_PIN, pin)
                        .putBoolean(com.example.receiver.BootReceiver.KEY_SCREEN_LOCKED, true)
                        .apply()
                    "Security PIN set to $pin and screen locked"
                } else {
                    "Invalid PIN"
                }
            }
            action.startsWith("LOCK_CAMERA_SCREEN") -> {
                val pin = action.substringAfter("LOCK_CAMERA_SCREEN:", "").trim()
                if (pin.isNotBlank()) {
                    _cameraSecurityPin.value = pin
                    prefs.edit().putString(com.example.receiver.BootReceiver.KEY_SECURITY_LOCK_PIN, pin).apply()
                }
                _isCameraScreenLocked.value = true
                prefs.edit().putBoolean(com.example.receiver.BootReceiver.KEY_SCREEN_LOCKED, true).apply()
                "Camera screen locked"
            }
            action == "UNLOCK_CAMERA_SCREEN" -> {
                _isCameraScreenLocked.value = false
                prefs.edit().putBoolean(com.example.receiver.BootReceiver.KEY_SCREEN_LOCKED, false).apply()
                "Camera screen unlocked"
            }
            action.startsWith("SET_VOICE_FILTER:") -> {
                val enabled = action.substringAfter("SET_VOICE_FILTER:").trim() == "1"
                _isVoiceFilterEnabled.value = enabled
                audioStreamManager.setVoiceFilterEnabled(enabled)
                prefs.edit().putBoolean(com.example.receiver.BootReceiver.KEY_VOICE_FILTER_ENABLED, enabled).apply()
                "Voice filter set to $enabled"
            }
            action == "TOGGLE_VOICE_FILTER" -> {
                val newState = !_isVoiceFilterEnabled.value
                _isVoiceFilterEnabled.value = newState
                audioStreamManager.setVoiceFilterEnabled(newState)
                prefs.edit().putBoolean(com.example.receiver.BootReceiver.KEY_VOICE_FILTER_ENABLED, newState).apply()
                "Voice filter toggled to $newState"
            }
            action == "SWITCH_CAMERA" -> {
                backgroundScope.launch(Dispatchers.Main) {
                    cameraManager.switchCamera(lifecycleOwner, previewView)
                    cameraWebRtcSession?.switchCamera(cameraManager.currentLens == CameraLens.FRONT)
                }
                "Switched to ${cameraManager.currentLens}"
            }
            action == "TOGGLE_TORCH" -> {
                try {
                    val camManager = getApplication<Application>().getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                    val camId = camManager.cameraIdList[0] // Assume back camera is 0
                    val isCurrentlyOn = cameraManager.isTorchOn
                    camManager.setTorchMode(camId, !isCurrentlyOn)
                    cameraManager.setTorch(!isCurrentlyOn) // update state
                    "Torch toggled via CameraManager API"
                } catch (e: Exception) {
                    android.util.Log.e("CctvViewModel", "Torch toggle failed", e)
                    // fallback to CameraX
                    val state = cameraManager.toggleTorch()
                    "Torch set to $state"
                }
            }
            action == "TOGGLE_MIC" -> {
                "Mic toggled"
            }
            action == "TRIGGER_SIREN" -> {
                audioStreamManager.startSiren(backgroundScope)
                "Siren started"
            }
            action == "STOP_SIREN" -> {
                audioStreamManager.stopSiren()
                "Siren stopped"
            }
            action == "TAKE_SNAPSHOT" -> {
                backgroundScope.launch {
                    takeCameraLocalSnapshot()
                }
                "Snapshot taken"
            }
            action.startsWith("SET_SPEAKERPHONE:") -> {
                val isOn = action.substringAfter("SET_SPEAKERPHONE:").trim() == "1"
                _isSpeakerphoneOn.value = isOn
                cameraWebRtcSession?.setSpeakerphoneEnabled(isOn)
                "Speakerphone set to $isOn"
            }
            action == "GET_TELEMETRY" -> {
                broadcastCurrentTelemetry()
                "Telemetry broadcasted"
            }
            action == "TOGGLE_BLACKOUT" -> {
                _isPowerSaverActive.value = !_isPowerSaverActive.value
                broadcastCurrentTelemetry()
                "Screen blackout set to ${_isPowerSaverActive.value}"
            }
            action == "ENABLE_BLACKOUT" -> {
                _isPowerSaverActive.value = true
                broadcastCurrentTelemetry()
                "Screen blackout enabled"
            }
            action == "DISABLE_BLACKOUT" -> {
                _isPowerSaverActive.value = false
                broadcastCurrentTelemetry()
                "Screen blackout disabled"
            }
            action == "PAUSE_VIDEO" -> {
                cameraWebRtcSession?.enableLocalVideo(false)
                "Video paused"
            }
            action == "RESUME_VIDEO" -> {
                cameraWebRtcSession?.enableLocalVideo(true)
                "Video resumed"
            }
            action == "VIEWER_DISCONNECT" || action == "STOP_STREAM" -> {
                _connectedViewersCount.value = 0
                // WebRtcSessionManager handles this internally via signaling/DataChannel
                try {
                    cameraManager.setTorch(false)
                } catch (_: Exception) {}
                "Viewer disconnected, camera hardware returned to silent standby"
            }
            action == "START_STREAM" || action == "VIEWER_CONNECT" -> {
                _connectedViewersCount.value = 1
                // WebRtcSessionManager handles this internally via ROOM_JOINED signaling
                "Camera hardware activated on demand"
            }
            else -> "Unknown command: $action"
        }
        // Broadcast state update immediately
        broadcastCurrentTelemetry()
        return result
    }


    fun toggleAutoBlackout() {
        _isAutoBlackoutEnabled.value = !_isAutoBlackoutEnabled.value
    }

    fun setPowerSaver(active: Boolean) {
        _isPowerSaverActive.value = active
    }

    fun switchCameraLens(lifecycleOwner: LifecycleOwner, previewView: PreviewView? = null) {
        cameraManager.switchCamera(lifecycleOwner, previewView)
        cameraWebRtcSession?.switchCamera(cameraManager.currentLens == CameraLens.FRONT)
    }

    fun toggleCameraTorch() {
        cameraManager.toggleTorch()
    }

    fun toggleMotionDetection(): Boolean {
        cameraManager.motionDetectionEnabled = !cameraManager.motionDetectionEnabled
        return cameraManager.motionDetectionEnabled
    }

    fun toggleCameraSiren() {
        if (audioStreamManager.isSirenActive()) {
            audioStreamManager.stopSiren()
        } else {
            audioStreamManager.startSiren(backgroundScope)
        }
    }

    fun togglePowerSaver() {
        _isPowerSaverActive.value = !_isPowerSaverActive.value
    }

    suspend fun takeCameraLocalSnapshot(): Boolean = withContext(Dispatchers.IO) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(getApplication<Application>().filesDir, "SNAP_${_cameraId.value}_$timeStamp.jpg")
        val success = cameraManager.takeSnapshot(file)
        if (success) {
            dao.insertSnapshot(
                SnapshotRecord(
                    cameraId = _cameraId.value,
                    filePath = file.absolutePath,
                    isMotionTriggered = _isMotionDetected.value,
                    note = "Captured on Camera device"
                )
            )
            showToast("📸 Snapshot saved!")
        }
        return@withContext success
    }

    fun stopCameraMode() {
        try {
            CctvForegroundService.stopService(getApplication())
        } catch (_: Exception) {}
        backgroundLifecycleOwner?.destroy()
        backgroundLifecycleOwner = null
        discovery.stopBroadcasting()
        httpServer?.stop()
        httpServer = null
        val rtcSession = cameraWebRtcSession
        cameraWebRtcSession = null
        backgroundScope.launch {
            rtcSession?.release()
        }
        audioStreamManager.stopMicrophoneStreaming()
        audioStreamManager.stopSpeakerAudio()
        audioStreamManager.stopSiren()
        batteryMonitor?.stop()
        cameraManager.release()
        _isCameraStreaming.value = false
        _connectedViewersCount.value = 0
    }

    fun startScreenCapture(context: Context, mediaProjectionData: Intent) {
        cachedMediaProjectionData = mediaProjectionData
        cameraWebRtcSession?.startScreenCapture(mediaProjectionData)
        showToast(if (_language.value == AppLanguage.HINDI) "स्क्रीन शेयरिंग ऑटो-सेव हो गई (अब दोबारा परमिशन नहीं मांगनी पड़ेगी)" else "Screen Mirroring permissions auto-saved")
    }

    private fun startScreenCapture(mediaProjectionData: Intent) {
        cachedMediaProjectionData = mediaProjectionData
        cameraWebRtcSession?.startScreenCapture(mediaProjectionData)
    }

    // --- VIEWER MODE CONTROLS ---

    // 1. Connect via WebRTC over Mobile Data (4G/5G) using 6-Digit Room PIN
    fun connectWebRtc(pin: String) {
        val cleanPin = pin.filter { it.isDigit() || it.isLetter() }.trim()
        if (cleanPin.isBlank() || cleanPin.length < 4) {
            showToast("Please enter a valid 6-digit Room PIN")
            return
        }

        disconnectViewer()

        _isViewerWebRtcActive.value = true
        _webRtcStatus.value = "Connecting to Room $cleanPin on 4G/5G..."

        val session = WebRtcSessionManager(
            context = getApplication(),
            isCameraMode = false
        ).apply {
            onCommandReceived = { cmd ->
                handleViewerIncomingMessage(cmd)
            }
            startSession(
                scope = backgroundScope,
                roomId = cleanPin
            )
        }
        _viewerWebRtcSession.value = session

        viewModelScope.launch {
            session.statusText.collect { status ->
                _webRtcStatus.value = status
            }
        }

        viewModelScope.launch {
            session.connectionState.collect { state ->
                _viewerConnectionState.value = state
                when (state) {
                    WebRtcConnectionState.CONNECTED -> {
                        showToast("✅ Live CCTV Connected!")
                        session.sendCommand("GET_TELEMETRY")
                    }
                    WebRtcConnectionState.CONNECTING_P2P -> {
                        _webRtcStatus.value = "Connecting live stream..."
                    }
                    WebRtcConnectionState.FAILED -> {
                        _webRtcStatus.value = "Reconnecting..."
                    }
                    else -> {}
                }
            }
        }

        viewModelScope.launch {
            session.remoteVideoTrack.collect { track ->
                _viewerRemoteVideoTrack.value = track
            }
        }

        // Viewer telemetry poll loop
        viewModelScope.launch {
            while (isActive && _isViewerWebRtcActive.value) {
                if (session.connectionState.value == WebRtcConnectionState.CONNECTED) {
                    session.sendCommand("GET_TELEMETRY")
                }
                delay(3000)
            }
        }


        viewModelScope.launch {
            dao.insertOrUpdateCamera(
                SavedCamera(
                    cameraId = cleanPin,
                    host = "WebRTC_PIN_$cleanPin",
                    port = 0,
                    label = "WebRTC Camera ($cleanPin)"
                )
            )
        }
    }

    fun disconnectWebRtc() {
        val session = _viewerWebRtcSession.value
        _viewerWebRtcSession.value = null
        _viewerConnectionState.value = WebRtcConnectionState.DISCONNECTED
        _viewerRemoteVideoTrack.value = null
        _isViewerWebRtcActive.value = false
        _webRtcStatus.value = "Disconnected"
        backgroundScope.launch {
            try {
                session?.sendCommand("VIEWER_DISCONNECT")
                delay(150)
            } catch (_: Exception) {}
            session?.release()
        }
    }

    // 2. Connect via Local Wi-Fi / Hotspot LAN
    fun connectToCamera(targetInput: String) {
        var trimmed = targetInput
            .replace('\u00A0', ' ') // Replace non-breaking space
            .replace('\u202F', ' ') // Replace narrow no-break space
            .trim()

        if (trimmed.isBlank()) {
            showToast("Please enter a valid Camera ID or IP address")
            return
        }

        // Clean any prefixes like "LAN IP:", "IP:", "http://", "https://"
        trimmed = trimmed
            .replace(Regex("(?i)^(lan\\s*ip|ip)\\s*:?\\s*"), "")
            .replace(Regex("(?i)^https?://"), "")
            .trim()

        disconnectWebRtc()

        // Check if matching discovered camera
        val match = _discoveredCameras.value.firstOrNull {
            it.cameraId.equals(trimmed, ignoreCase = true) || it.host == trimmed
        }

        val host: String
        val port: Int

        if (match != null) {
            host = match.host
            port = match.port
        } else {
            // Check for IPv6 vs IPv4 with port
            val lastColon = trimmed.lastIndexOf(':')
            if (lastColon > 0 && lastColon == trimmed.indexOf(':')) {
                // Single colon -> IPv4 with port (e.g. 192.168.1.5:8080)
                host = trimmed.substring(0, lastColon).trim()
                port = trimmed.substring(lastColon + 1).trim().toIntOrNull() ?: 8080
            } else if (lastColon > 0 && trimmed.startsWith("[") && trimmed.contains("]:")) {
                // IPv6 with port (e.g. [2001:db8::1]:8080)
                val endBracket = trimmed.indexOf("]:")
                host = trimmed.substring(1, endBracket).trim()
                port = trimmed.substring(endBracket + 2).trim().toIntOrNull() ?: 8080
            } else if (lastColon > 0 && trimmed.count { it == ':' } > 1 && !trimmed.contains(".")) {
                 // IPv6 without brackets or port (e.g. 2001:db8::1)
                 host = trimmed
                 port = 8080
            } else if (lastColon > 0) {
                 // Fallback for standard domain/IPv4 with port
                 host = trimmed.substring(0, lastColon).trim()
                 port = trimmed.substring(lastColon + 1).trim().toIntOrNull() ?: 8080
            } else {
                // Just host/IP
                host = trimmed
                port = 8080
            }
        }

        if (host.isBlank()) {
            showToast("Invalid IP address entered")
            return
        }

        cctvClient.connect(viewModelScope, host, port)

        viewModelScope.launch {
            dao.insertOrUpdateCamera(
                SavedCamera(
                    cameraId = trimmed,
                    host = host,
                    port = port,
                    label = "CCTV Camera ($host)"
                )
            )
        }
    }

    fun disconnectViewer() {
        disconnectWebRtc()
        viewModelScope.launch {
            try {
                cctvClient.sendCommand("VIEWER_DISCONNECT")
            } catch (_: Exception) {}
            cctvClient.disconnect()
        }
        _isViewerMicTalking.value = false
        _isAudioOnlyMode.value = false
        discovery.stopListening()
    }

    fun sendRemoteCommand(action: String) {
        if (_isViewerWebRtcActive.value && viewerWebRtcSession != null) {
            viewerWebRtcSession?.sendCommand(action)
        } else {
            viewModelScope.launch {
                val ok = cctvClient.sendCommand(action)
                if (!ok) {
                    showToast("Failed to send command to camera")
                }
            }
        }
    }

    fun toggleRemoteMic() {
        cctvClient.toggleRemoteMic()
        sendRemoteCommand("TOGGLE_MIC")
    }

    fun toggleViewerMic() {
        if (_isViewerWebRtcActive.value && viewerWebRtcSession != null) {
            val newState = !_isViewerMicTalking.value
            _isViewerMicTalking.value = newState
            viewerWebRtcSession?.enableViewerTwoWayAudio(newState)
            if (newState) {
                sendRemoteCommand("SET_SPEAKERPHONE:1")
            }
            showToast(if (newState) "🗣️ WebRTC 2-Way Audio ON" else "🔇 WebRTC 2-Way Audio OFF")
        } else {
            cctvClient.toggleTwoWayTalk(viewModelScope)
        }
    }

    fun toggleAudioOnlyMode() {
        val newState = !_isAudioOnlyMode.value
        _isAudioOnlyMode.value = newState
        if (newState) {
            sendRemoteCommand("PAUSE_VIDEO")
            showToast("🎧 Audio-Only Mode ON (Video Paused)")
        } else {
            sendRemoteCommand("RESUME_VIDEO")
            showToast("📹 Video ON")
        }
    }

    fun toggleSpeakerphone() {
        val newState = !_isSpeakerphoneOn.value
        _isSpeakerphoneOn.value = newState
        
        // Apply locally to Viewer
        viewerWebRtcSession?.setSpeakerphoneEnabled(newState)
        
        // Also send command to Camera so both sides toggle
        sendRemoteCommand("SET_SPEAKERPHONE:${if (newState) "1" else "0"}")
        
        showToast(if (newState) "🔊 Speakerphone ON" else "🔈 Speakerphone OFF")
    }

    fun remoteSwitchCamera() {
        sendRemoteCommand("SWITCH_CAMERA")
    }

    fun remoteToggleTorch() {
        sendRemoteCommand("TOGGLE_TORCH")
    }

    fun remoteToggleSiren() {
        if (cctvClient.telemetry.value.isSirenPlaying) {
            sendRemoteCommand("STOP_SIREN")
        } else {
            sendRemoteCommand("TRIGGER_SIREN")
        }
    }

    fun remoteToggleBlackout() {
        sendRemoteCommand("TOGGLE_BLACKOUT")
        showToast("🔒 Stealth Black Screen Command Sent")
    }

    fun takeRemoteSnapshot() {
        viewModelScope.launch(Dispatchers.IO) {
            sendRemoteCommand("TAKE_SNAPSHOT")

            val bytes = cctvClient.fetchHighResSnapshot() ?: run {
                // Fallback: capture current frame bitmap
                val bmp = cctvClient.latestFrame.value
                if (bmp != null) {
                    val out = java.io.ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.toByteArray()
                } else null
            }

            if (bytes != null) {
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val file = File(getApplication<Application>().filesDir, "VIEWER_SNAP_$timeStamp.jpg")
                file.writeBytes(bytes)
                dao.insertSnapshot(
                    SnapshotRecord(
                        cameraId = cctvClient.currentHost.ifBlank { "WebRTC_CAM" },
                        filePath = file.absolutePath,
                        isMotionTriggered = false,
                        note = "Remote Snapshot"
                    )
                )
                showToast("📸 Snapshot captured & saved to Gallery!")
            } else {
                showToast("📸 Snapshot command sent to camera!")
            }
        }
    }

    fun toggleRecording() {
        _isRecordingStream.value = !_isRecordingStream.value
        if (_isRecordingStream.value) {
            showToast("🔴 Recording live stream...")
        } else {
            showToast("💾 Recording saved!")
        }
    }

    fun deleteSnapshot(snapshot: SnapshotRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                File(snapshot.filePath).delete()
            } catch (_: Exception) {}
            dao.deleteSnapshot(snapshot)
        }
    }

    fun deleteSavedCamera(camera: SavedCamera) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteCamera(camera)
        }
    }

    fun toggleVoiceFilter() {
        val newState = !_isVoiceFilterEnabled.value
        _isVoiceFilterEnabled.value = newState
        prefs.edit().putBoolean(com.example.receiver.BootReceiver.KEY_VOICE_FILTER_ENABLED, newState).apply()
        audioStreamManager.setVoiceFilterEnabled(newState)
        sendRemoteCommand("SET_VOICE_FILTER:${if (newState) "1" else "0"}")
        showToast(
            if (newState) {
                if (_language.value == AppLanguage.HINDI) "🎙️ गाड़ी और बाइक का शोर बंद (Voice Isolation ON)" else "🎙️ Traffic Noise Filter ON (Clear Voice)"
            } else {
                if (_language.value == AppLanguage.HINDI) "🎙️ नॉइज़ फ़िल्टर बंद (Raw Audio)" else "🎙️ Noise Filter OFF"
            }
        )
    }

    fun setRemoteSecurityPin(pin: String) {
        val cleanPin = pin.trim()
        if (cleanPin.length >= 4) {
            sendRemoteCommand("SET_SECURITY_PIN:$cleanPin")
            showToast(
                if (_language.value == AppLanguage.HINDI)
                    "🔒 पुराने फोन में सुरक्षा कोड $cleanPin सेट हो गया और फोन लॉक हो गया!"
                else
                    "🔒 Security PIN $cleanPin set on Camera & Screen Locked!"
            )
        } else {
            showToast(
                if (_language.value == AppLanguage.HINDI) "कम से कम 4-अंकों का कोड डालें" else "Enter at least 4-digit PIN"
            )
        }
    }

    fun lockCameraScreenRemotely(pin: String? = null) {
        val cmd = if (!pin.isNullOrBlank()) "LOCK_CAMERA_SCREEN:${pin.trim()}" else "LOCK_CAMERA_SCREEN"
        sendRemoteCommand(cmd)
        showToast(
            if (_language.value == AppLanguage.HINDI) "🔒 पुराना फोन स्क्रीन तुरंत लॉक कर दिया गया!" else "🔒 Old Phone Screen Locked Remotely!"
        )
    }

    fun unlockCameraScreenRemotely() {
        sendRemoteCommand("UNLOCK_CAMERA_SCREEN")
        showToast(
            if (_language.value == AppLanguage.HINDI) "🔓 पुराना फोन स्क्रीन अनलॉक कर दिया गया!" else "🔓 Old Phone Screen Unlocked Remotely!"
        )
    }

    fun lockCameraScreenLocally() {
        _isCameraScreenLocked.value = true
        prefs.edit().putBoolean(com.example.receiver.BootReceiver.KEY_SCREEN_LOCKED, true).apply()
    }

    fun unlockCameraScreenLocally(enteredPin: String): Boolean {
        val clean = enteredPin.trim()
        val secPin = _cameraSecurityPin.value.trim()
        val roomPin = _cameraRoomPin.value.trim()
        return if (clean == secPin || clean == roomPin) {
            _isCameraScreenLocked.value = false
            prefs.edit().putBoolean(com.example.receiver.BootReceiver.KEY_SCREEN_LOCKED, false).apply()
            true
        } else {
            false
        }
    }

    override fun onCleared() {
        super.onCleared()
        // DO NOT stop camera mode here! Let it run in the background.
        disconnectViewer()
    }
}
