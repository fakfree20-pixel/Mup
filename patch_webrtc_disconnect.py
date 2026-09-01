import sys

with open("app/src/main/java/com/example/webrtc/WebRtcSessionManager.kt", "r") as f:
    content = f.read()

new_logic = """                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            _connectionState.value = WebRtcConnectionState.DISCONNECTED
                            _statusText.value = "Connection lost. Reconnecting..."
                            if (isCameraMode) stopCameraMediaTracks()
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            _connectionState.value = WebRtcConnectionState.FAILED
                            _statusText.value = "P2P connection failed. Retrying..."
                            if (isCameraMode) stopCameraMediaTracks()
                        }"""

content = content.replace("""                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            _connectionState.value = WebRtcConnectionState.DISCONNECTED
                            _statusText.value = "Connection lost. Reconnecting..."
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            _connectionState.value = WebRtcConnectionState.FAILED
                            _statusText.value = "P2P connection failed. Retrying..."
                        }""", new_logic)

stop_camera_func = """
    private fun stopCameraMediaTracks() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null
            
            localVideoTrack?.dispose()
            localVideoTrack = null
            
            localAudioTrack?.dispose()
            localAudioTrack = null
            
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null
            
            localVideoSource?.dispose()
            localVideoSource = null
            
            localAudioSource?.dispose()
            localAudioSource = null
            
            Log.d(TAG, "Camera media tracks released to save battery")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media tracks", e)
        }
    }

    private fun setupCameraMediaTracks"""

content = content.replace("    private fun setupCameraMediaTracks", stop_camera_func)

with open("app/src/main/java/com/example/webrtc/WebRtcSessionManager.kt", "w") as f:
    f.write(content)
