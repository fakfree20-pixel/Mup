import sys
import re

with open("app/src/main/java/com/example/ui/screens/CameraModeScreen.kt", "r") as f:
    content = f.read()

content = content.replace("viewModel.toggleCameraLens()", "viewModel.switchCameraLens(lifecycleOwner, previewViewRef)")

with open("app/src/main/java/com/example/ui/screens/CameraModeScreen.kt", "w") as f:
    f.write(content)
