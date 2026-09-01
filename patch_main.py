import sys

with open("app/src/main/java/com/example/MainActivity.kt", "r") as f:
    lines = f.readlines()

new_lines = []
for line in lines:
    if "val permissionLauncher = rememberLauncherForActivityResult" in line:
        new_lines.append("""
    val viewerPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.selectRole(AppRole.VIEWER_DEVICE)
        } else {
            Toast.makeText(context, "Microphone permission is required for Two-Way Audio", Toast.LENGTH_SHORT).show()
            viewModel.selectRole(AppRole.VIEWER_DEVICE) // Still allow viewer, just no mic
        }
    }
""")
        new_lines.append(line)
    elif "viewModel.selectRole(role)" in line and "if (role == AppRole.CAMERA_DEVICE)" not in line and "else {" in new_lines[-1]:
        new_lines.append("""
                                if (role == AppRole.VIEWER_DEVICE) {
                                    val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                    if (hasAudio) {
                                        viewModel.selectRole(role)
                                    } else {
                                        viewerPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                } else {
                                    viewModel.selectRole(role)
                                }
""")
    else:
        new_lines.append(line)

with open("app/src/main/java/com/example/MainActivity.kt", "w") as f:
    f.writelines(new_lines)
