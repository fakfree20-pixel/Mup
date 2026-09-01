import sys

with open("app/src/main/java/com/example/webrtc/WebRtcSessionManager.kt", "r") as f:
    content = f.read()

func = """    private fun stopCameraMediaTracks() {
        try {
            peerConnection?.senders?.forEach { sender ->
                peerConnection?.removeTrack(sender)
            }
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null
"""

content = content.replace("""    private fun stopCameraMediaTracks() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null
""", func)

with open("app/src/main/java/com/example/webrtc/WebRtcSessionManager.kt", "w") as f:
    f.write(content)
