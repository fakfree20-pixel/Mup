import sys

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "r") as f:
    content = f.read()

content = content.replace("mostRecent.roomPin", "mostRecent.cameraId")
content = content.replace("val roomPinInput by viewModel.viewerRoomPinInput.collectAsState()", "val roomPinInput by viewModel.viewerRoomPinInput.collectAsState()\n    val isConnecting by viewModel.cctvClient.isConnecting.collectAsState()")

# Also, when the viewer clicks "Viewer Mode" from Selection screen, the role changes.
# The ViewerModeScreen opens. isAnyConnected is false. savedCameras might be populated.
# If so, it auto connects!

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "w") as f:
    f.write(content)
