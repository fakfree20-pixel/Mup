import sys

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "r") as f:
    content = f.read()

# Make the video container flexible to take full height
content = content.replace('.aspectRatio(4f / 3f)', '.fillMaxSize()')

# Also, in WebRtcVideoPlayer.kt, change SCALE_ASPECT_FIT to SCALE_ASPECT_FILL?
# Actually, the user asked for full screen. Let's patch WebRtcVideoPlayer.kt too.
with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "w") as f:
    f.write(content)
