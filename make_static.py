import sys

with open("app/src/main/java/com/example/ui/viewmodel/CctvViewModel.kt", "r") as f:
    content = f.read()

replacement = """    companion object {
        var isCameraModeActive = false
        var cameraManagerInstance: com.example.camera.CameraManager? = null
        var audioStreamManagerInstance: com.example.camera.AudioStreamManager? = null
        var cameraWebRtcSessionInstance: com.example.webrtc.WebRtcSessionManager? = null
        var httpServerInstance: com.example.network.CctvHttpServer? = null
        var batteryMonitorInstance: com.example.camera.BatteryMonitor? = null
        var backgroundLifecycleOwnerInstance: AlwaysActiveLifecycleOwner? = null
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
"""

# Replace lines 44 to 50
content = content.replace("""    private val TAG = "CctvViewModel"
    private val db = AppDatabase.getDatabase(application)
    private val dao = db.cctvDao()

    private var backgroundLifecycleOwner: AlwaysActiveLifecycleOwner? = null""", replacement + """
    private val db = AppDatabase.getDatabase(application)
    private val dao = db.cctvDao()
""")

content = content.replace("val cameraManager = CameraManager(application)", "")
content = content.replace("val audioStreamManager = AudioStreamManager(application)", "")
content = content.replace("private var batteryMonitor: BatteryMonitor? = null", "")
content = content.replace("private var httpServer: CctvHttpServer? = null", "")
content = content.replace("var cameraWebRtcSession: WebRtcSessionManager? = null\n        private set", "")

# Update startCameraMode to use backgroundScope for starting sessions
content = content.replace("scope = viewModelScope,", "scope = backgroundScope,")
content = content.replace("start(viewModelScope)", "start(backgroundScope)")
content = content.replace("batteryMonitor = BatteryMonitor(application) { pct, isCharging ->", "batteryMonitor = com.example.camera.BatteryMonitor(application) { pct, isCharging ->")

# Don't call stopCameraMode in onCleared!
content = content.replace("""    override fun onCleared() {
        super.onCleared()
        stopCameraMode()
        disconnectViewer()
    }""", """    override fun onCleared() {
        super.onCleared()
        // DO NOT stop camera mode here! Let it run in the background.
        disconnectViewer()
    }""")

with open("app/src/main/java/com/example/ui/viewmodel/CctvViewModel.kt", "w") as f:
    f.write(content)
