import sys

with open("app/src/main/java/com/example/ui/viewmodel/CctvViewModel.kt", "r") as f:
    content = f.read()

print("Found cameraWebRtcSession:", "cameraWebRtcSession" in content)
