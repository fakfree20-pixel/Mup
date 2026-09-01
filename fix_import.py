import sys

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "r") as f:
    content = f.read()

if "import androidx.compose.runtime.LaunchedEffect" not in content:
    content = content.replace("import androidx.compose.runtime.Composable", "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.LaunchedEffect")

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "w") as f:
    f.write(content)
