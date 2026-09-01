import sys

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "r") as f:
    content = f.read()

content = content.replace("viewModel.connectViewer(mostRecent.cameraId)", "viewModel.connectWebRtc(mostRecent.cameraId)")

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "w") as f:
    f.write(content)
