import sys

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "r") as f:
    content = f.read()

content = content.replace("val webRtcVideoTrack = webRtcSession?.remoteVideoTrack", "val webRtcVideoTrack by (webRtcSession?.remoteVideoTrack ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()")

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "w") as f:
    f.write(content)
