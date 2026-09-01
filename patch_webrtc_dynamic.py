import sys

with open("app/src/main/java/com/example/webrtc/WebRtcSessionManager.kt", "r") as f:
    content = f.read()

# Remove immediate camera start in startSession
content = content.replace("""        setupPeerConnection(scope)
        if (isCameraMode) {
            setupCameraMediaTracks(isFrontCamera)
            createAndSendOffer(roomId)""", """        setupPeerConnection(scope)
        if (isCameraMode) {
            // Wait for Viewer to join before starting camera to save battery in background""")

# When Viewer joins, start camera and send offer
content = content.replace("""            "ROOM_JOINED" -> {
                if (isCameraMode) {
                    val localDesc = peerConnection?.localDescription""", """            "ROOM_JOINED" -> {
                if (isCameraMode) {
                    if (localVideoTrack == null) {
                        // Start camera now that viewer is connected
                        setupCameraMediaTracks(false)
                        createAndSendOffer(msg.targetRoom.ifBlank { roomId })
                    }
                    val localDesc = peerConnection?.localDescription""")

with open("app/src/main/java/com/example/webrtc/WebRtcSessionManager.kt", "w") as f:
    f.write(content)
