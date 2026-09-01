import sys

with open("app/src/main/java/com/example/ui/viewmodel/CctvViewModel.kt", "r") as f:
    content = f.read()

# Replace the camera manager instantiation
content = content.replace("val cameraManager = CameraManager(application)", "val cameraManager get() = CameraEngine.cameraManager!!")
content = content.replace("val audioStreamManager = AudioStreamManager(application)", "val audioStreamManager get() = CameraEngine.audioStreamManager!!")
content = content.replace("var batteryMonitor: BatteryMonitor? = null", "")
content = content.replace("var httpServer: CctvHttpServer? = null", "")
content = content.replace("var cameraWebRtcSession: WebRtcSessionManager? = null\n        private set", "val cameraWebRtcSession get() = CameraEngine.cameraWebRtcSession")

# Now update startCameraMode
