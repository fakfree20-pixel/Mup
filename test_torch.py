import sys

with open("app/src/main/java/com/example/ui/viewmodel/CctvViewModel.kt", "r") as f:
    content = f.read()

# Replace toggleTorch handling
new_handler = """            "TOGGLE_TORCH" -> {
                try {
                    val camManager = getApplication<Application>().getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                    val camId = camManager.cameraIdList[0] // Assume back camera is 0
                    val isCurrentlyOn = cameraManager.isTorchOn
                    camManager.setTorchMode(camId, !isCurrentlyOn)
                    cameraManager.setTorch(!isCurrentlyOn) // update state
                    "Torch toggled via CameraManager API"
                } catch (e: Exception) {
                    android.util.Log.e("CctvViewModel", "Torch toggle failed", e)
                    // fallback to CameraX
                    val state = cameraManager.toggleTorch()
                    "Torch set to $state"
                }
            }"""

import re
content = re.sub(r'"TOGGLE_TORCH" -> \{[^\}]+\}', new_handler, content)

with open("app/src/main/java/com/example/ui/viewmodel/CctvViewModel.kt", "w") as f:
    f.write(content)
