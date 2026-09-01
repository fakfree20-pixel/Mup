import sys
import re

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "r") as f:
    content = f.read()

# Fix the state flow extraction
content = content.replace("val webRtcConnState by viewModel.webRtcConnectionState.collectAsState()", "val webRtcSession = viewModel.viewerWebRtcSession\n    val webRtcConnState = webRtcSession?.connectionState?.collectAsState()?.value ?: WebRtcConnectionState.DISCONNECTED")
content = content.replace("val webRtcVideoTrack by viewModel.remoteVideoTrack.collectAsState()", "val webRtcVideoTrack = webRtcSession?.remoteVideoTrack")
content = content.replace("val webRtcSession by viewModel.webRtcSession.collectAsState()", "") # Already defined above

# Fix function calls
content = content.replace("viewModel.toggleTwoWayTalk()", "viewModel.toggleViewerMic()")
content = content.replace("viewModel.flipRemoteCamera()", "viewModel.sendRemoteCommand(\"SWITCH_CAMERA\")")

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "w") as f:
    f.write(content)
