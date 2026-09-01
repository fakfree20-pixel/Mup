import sys
import re

with open("app/src/main/java/com/example/ui/viewmodel/CctvViewModel.kt", "r") as f:
    content = f.read()

# Can we just make `cameraManager`, `cameraWebRtcSession`, `httpServer`, `audioStreamManager`, `batteryMonitor` static?
