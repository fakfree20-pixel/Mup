import sys

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "r") as f:
    content = f.read()

# I want to restore the specific `.fillMaxSize()` calls.
# 1. line 152: Column modifier `.aspectRatio(9f/16f)` -> `.fillMaxSize()`
# 2. line 727: Box modifier `.aspectRatio(9f/16f)` -> keep it!
# 3. line 749: WebRtcVideoPlayer modifier `.aspectRatio(9f/16f)` -> `.fillMaxSize()`
# 4. line 758: Image modifier `.aspectRatio(9f/16f)` -> `.fillMaxSize()`
# 5. line 786: Box (HUD overlay) modifier `.aspectRatio(9f/16f)` -> `.fillMaxSize()`

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "w") as f:
    for i, line in enumerate(content.split("\n")):
        if i == 151: # 0-indexed
            f.write(line.replace(".aspectRatio(9f/16f)", ".fillMaxSize()") + "\n")
        elif i == 748:
            f.write(line.replace(".aspectRatio(9f/16f)", ".fillMaxSize()") + "\n")
        elif i == 757:
            f.write(line.replace(".aspectRatio(9f/16f)", ".fillMaxSize()") + "\n")
        elif i == 785:
            f.write(line.replace(".aspectRatio(9f/16f)", ".fillMaxSize()") + "\n")
        else:
            f.write(line + "\n")
