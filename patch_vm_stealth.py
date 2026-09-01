import sys

with open("app/src/main/java/com/example/ui/viewmodel/CctvViewModel.kt", "r") as f:
    content = f.read()

# We want to comment out the unconditional start of CameraX and AudioStreamManager
to_replace = """        // 1. Setup CameraX for local display & motion analysis
        cameraManager.startCamera(activeOwner, previewView) {
            _isCameraStreaming.value = true
        }

        // 2. Setup Motion Detection Callback
        cameraManager.onMotionDetected = { pct ->
            _isMotionDetected.value = true
            _cameraTelemetry.value = _cameraTelemetry.value.copy(
                motionDetected = true,
                motionCount = _cameraTelemetry.value.motionCount + 1
            )
            backgroundScope.launch {
                dao.insertSecurityEvent(
                    SecurityEvent(
                        cameraId = _cameraId.value,
                        eventType = "MOTION_DETECTED",
                        description = "Motion detected (${pct.toInt()}% change)"
                    )
                )
                delay(3000)
                _isMotionDetected.value = false
                _cameraTelemetry.value = _cameraTelemetry.value.copy(motionDetected = false)
            }
        }

        // 3. Audio manager
        audioStreamManager.startMicrophoneStreaming(backgroundScope)"""

new_text = """        // Note: We intentionally DO NOT start CameraX or AudioStreamManager here.
        // This is to maintain absolute stealth (no green privacy dots on Android 12+) 
        // until a Viewer actually connects. WebRTC will open the camera and mic 
        // ONLY when the viewer joins.
        _isCameraStreaming.value = true"""

content = content.replace(to_replace, new_text)

with open("app/src/main/java/com/example/ui/viewmodel/CctvViewModel.kt", "w") as f:
    f.write(content)
