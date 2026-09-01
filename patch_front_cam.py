import sys

with open("app/src/main/java/com/example/webrtc/WebRtcSessionManager.kt", "r") as f:
    content = f.read()

content = content.replace("private var currentRoomId: String = \"\"",
                          "private var currentRoomId: String = \"\"\n    private var currentIsFrontCamera: Boolean = false")

content = content.replace("currentRoomId = roomId",
                          "currentRoomId = roomId\n        currentIsFrontCamera = isFrontCamera")

content = content.replace("setupCameraMediaTracks(false)", "setupCameraMediaTracks(currentIsFrontCamera)")

with open("app/src/main/java/com/example/webrtc/WebRtcSessionManager.kt", "w") as f:
    f.write(content)
