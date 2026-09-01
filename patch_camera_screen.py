import sys
import re

with open("app/src/main/java/com/example/ui/screens/CameraModeScreen.kt", "r") as f:
    content = f.read()

# We need to replace the Box containing the AndroidView and HUD with a simple Stealth Dashboard.
# Let's completely rewrite the CameraModeScreen body.

