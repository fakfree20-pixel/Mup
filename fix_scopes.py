import sys

with open("app/src/main/java/com/example/ui/viewmodel/CctvViewModel.kt", "r") as f:
    content = f.read()

# Replace viewModelScope with backgroundScope inside startCameraMode and handleRemoteCommand
# Specifically, we know these lines:
content = content.replace("audioStreamManager.startMicrophoneStreaming(viewModelScope)", "audioStreamManager.startMicrophoneStreaming(backgroundScope)")
content = content.replace("""        cameraManager.onMotionDetected = { pct ->
            _isMotionDetected.value = true
            _cameraTelemetry.value = _cameraTelemetry.value.copy(
                motionDetected = true,
                motionCount = _cameraTelemetry.value.motionCount + 1
            )
            viewModelScope.launch {""", """        cameraManager.onMotionDetected = { pct ->
            _isMotionDetected.value = true
            _cameraTelemetry.value = _cameraTelemetry.value.copy(
                motionDetected = true,
                motionCount = _cameraTelemetry.value.motionCount + 1
            )
            backgroundScope.launch {""")

content = content.replace("""        viewModelScope.launch {
            cameraWebRtcSession?.connectionState?.collect { state ->""", """        backgroundScope.launch {
            cameraWebRtcSession?.connectionState?.collect { state ->""")

content = content.replace("""            "SWITCH_CAMERA" -> {
                viewModelScope.launch(Dispatchers.Main) {""", """            "SWITCH_CAMERA" -> {
                backgroundScope.launch(Dispatchers.Main) {""")

content = content.replace('audioStreamManager.startSiren(viewModelScope)', 'audioStreamManager.startSiren(backgroundScope)')

content = content.replace("""            "TAKE_SNAPSHOT" -> {
                viewModelScope.launch {""", """            "TAKE_SNAPSHOT" -> {
                backgroundScope.launch {""")

with open("app/src/main/java/com/example/ui/viewmodel/CctvViewModel.kt", "w") as f:
    f.write(content)
