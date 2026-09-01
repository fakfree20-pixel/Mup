import sys

with open("app/src/main/java/com/example/webrtc/WebRtcSessionManager.kt", "r") as f:
    content = f.read()

content = content.replace("private var signalingClient: WebRtcSignalingClient? = null", 
                          "private var signalingClient: WebRtcSignalingClient? = null\n    private var currentRoomId: String = \"\"")

content = content.replace("fun startSession(\n        scope: CoroutineScope,\n        roomId: String,\n        isFrontCamera: Boolean = false\n    ) {",
                          "fun startSession(\n        scope: CoroutineScope,\n        roomId: String,\n        isFrontCamera: Boolean = false\n    ) {\n        currentRoomId = roomId")

content = content.replace("msg.targetRoom.ifBlank { roomId }", "msg.targetRoom.ifBlank { currentRoomId }")

with open("app/src/main/java/com/example/webrtc/WebRtcSessionManager.kt", "w") as f:
    f.write(content)
