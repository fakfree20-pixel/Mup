import sys

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "r") as f:
    content = f.read()

content = content.replace("    val roomPinInput by viewModel.viewerRoomPinInput.collectAsState()\n    val isConnecting by viewModel.cctvClient.isConnecting.collectAsState()", "    val roomPinInput by viewModel.viewerRoomPinInput.collectAsState()")

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "w") as f:
    f.write(content)
