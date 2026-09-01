import sys

with open("app/src/main/java/com/example/ui/screens/ModeSelectionScreen.kt", "r") as f:
    content = f.read()

# We need to simplify ModeSelectionScreen so that it only has "OLD PHONE" and "NEW PHONE" buttons,
# and no snapshot gallery or features grid.

start_str = "Spacer(modifier = Modifier.height(16.dp))"
end_str = "}    }\n}\n\n@Composable\nprivate fun SleekFeaturePill"

idx_start = content.find(start_str)
idx_end = content.find(end_str)

if idx_start != -1 and idx_end != -1:
    content = content[:idx_start] + "}    }\n}\n\n@Composable\nprivate fun SleekFeaturePill" + content[idx_end + len(end_str):]
    with open("app/src/main/java/com/example/ui/screens/ModeSelectionScreen.kt", "w") as f:
        f.write(content)
    print("ModeSelectionScreen patched successfully.")
else:
    print("Could not find start or end strings in ModeSelectionScreen.kt")
