import sys

with open("app/src/main/java/com/example/ui/viewmodel/CctvViewModel.kt", "r") as f:
    content = f.read()

content = content.replace("batteryMonitor = BatteryMonitor(application) { level, isCharging ->", "batteryMonitor = com.example.camera.BatteryMonitor(getApplication()) { level, isCharging ->")

with open("app/src/main/java/com/example/ui/viewmodel/CctvViewModel.kt", "w") as f:
    f.write(content)
