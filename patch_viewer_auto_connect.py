import sys

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "r") as f:
    content = f.read()

effect = """
    // Auto-connect to the most recent saved camera on open if not already connected
    LaunchedEffect(savedCameras, isAnyConnected) {
        if (!isAnyConnected && savedCameras.isNotEmpty() && roomPinInput.isBlank()) {
            val mostRecent = savedCameras.first()
            viewModel.setViewerRoomPinInput(mostRecent.roomPin)
            viewModel.connectViewer(mostRecent.roomPin)
        }
    }
"""

content = content.replace("    val scrollState = rememberScrollState()", "    val scrollState = rememberScrollState()\n" + effect)

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "w") as f:
    f.write(content)
