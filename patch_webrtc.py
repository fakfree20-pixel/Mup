import sys

with open("app/src/main/java/com/example/webrtc/WebRtcSessionManager.kt", "r") as f:
    content = f.read()

# Replace the unconditional setup with just waiting
old_block = """        if (isCameraMode) {
            setupCameraMediaTracks(isFrontCamera)
            createAndSendOffer(roomId)

            // Periodically re-broadcast offer if viewer hasn't completed handshake yet
            scope.launch(Dispatchers.IO) {
                while (scope.isActive) {
                    delay(4000)
                    if (_connectionState.value != WebRtcConnectionState.CONNECTED) {
                        val localDesc = peerConnection?.localDescription
                        if (localDesc != null && localDesc.type == SessionDescription.Type.OFFER) {
                            signalingClient?.sendMessage(
                                SignalingMessage(
                                    type = "OFFER",
                                    senderId = "CAMERA",
                                    targetRoom = roomId,
                                    sdp = minifySdp(localDesc.description),
                                    sdpType = localDesc.type.canonicalForm()
                                )
                            )
                        }
                    }
                }
            }
        } else {"""

new_block = """        if (isCameraMode) {
            _statusText.value = "Camera is online & waiting for Viewer..."
        } else {"""

content = content.replace(old_block, new_block)

with open("app/src/main/java/com/example/webrtc/WebRtcSessionManager.kt", "w") as f:
    f.write(content)
