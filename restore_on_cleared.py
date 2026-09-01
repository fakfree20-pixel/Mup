import sys

with open("app/src/main/java/com/example/ui/viewmodel/CctvViewModel.kt", "r") as f:
    content = f.read()

content = content.replace("""    override fun onCleared() {
        super.onCleared()
        // DO NOT stop camera mode here so it runs in background!
        // stopCameraMode() 
        disconnectViewer()
    }""", """    override fun onCleared() {
        super.onCleared()
        stopCameraMode()
        disconnectViewer()
    }""")

with open("app/src/main/java/com/example/ui/viewmodel/CctvViewModel.kt", "w") as f:
    f.write(content)
