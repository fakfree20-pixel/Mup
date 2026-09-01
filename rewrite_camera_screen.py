import sys
import re

with open("app/src/main/java/com/example/ui/screens/CameraModeScreen.kt", "r") as f:
    content = f.read()

# Find the start of the function
start_idx = content.find("@Composable\nfun CameraModeScreen")

# The imports are above start_idx. We want to keep imports and replace the rest.
new_content = content[:start_idx] + """@Composable
fun CameraModeScreen(
    viewModel: CctvViewModel,
    language: AppLanguage,
    onStopCamera: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraRoomPin by viewModel.cameraRoomPin.collectAsState()
    val connectedViewers by viewModel.connectedViewersCount.collectAsState()
    val isPowerSaverActive by viewModel.isPowerSaverActive.collectAsState()

    var currentTime by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.startCameraMode(lifecycleOwner, null) // Start service and signaling, no preview
        while (true) {
            currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            kotlinx.coroutines.delay(1000)
        }
    }

    // Stealth / Power Saver Mode brightness adjustment
    DisposableEffect(isPowerSaverActive) {
        if (isPowerSaverActive) {
            try {
                activity?.window?.attributes = activity?.window?.attributes?.apply {
                    screenBrightness = 0.01f
                }
            } catch (_: Exception) {}
        } else {
            try {
                activity?.window?.attributes = activity?.window?.attributes?.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            } catch (_: Exception) {}
        }
        onDispose {
            try {
                activity?.window?.attributes = activity?.window?.attributes?.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            } catch (_: Exception) {}
        }
    }

    val isConnected = connectedViewers > 0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // If Power saver is strictly on, we can show absolutely nothing but black.
        // But let's show the stealth dashboard when not blacked out.
        if (!isPowerSaverActive) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = currentTime,
                    color = CctvTextSecondary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(32.dp))

                // Connection Status
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = CctvCardBgSecondary,
                    border = androidx.compose.foundation.BorderStroke(1.dp, CctvCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "ROOM PIN",
                            color = CctvTextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = cameraRoomPin,
                            color = CctvIceBlue,
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 4.sp
                        )
                        
                        Text(
                            text = if (isConnected) "VIEWER CONNECTED" else "WAITING FOR VIEWER...",
                            color = if (isConnected) CctvSuccessGreen else CctvTextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))

                // Dynamic Indicators (Stealth requirement)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Camera Indicator
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) CctvSuccessGreen.copy(alpha = 0.2f) else CctvCardBgSecondary)
                                .border(2.dp, if (isConnected) CctvSuccessGreen else CctvCardBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isConnected) Icons.Default.Videocam else Icons.Default.VisibilityOff,
                                contentDescription = "Camera Status",
                                tint = if (isConnected) CctvSuccessGreen else CctvTextSecondary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "CAMERA",
                            color = if (isConnected) CctvSuccessGreen else CctvTextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Mic Indicator
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) CctvSuccessGreen.copy(alpha = 0.2f) else CctvCardBgSecondary)
                                .border(2.dp, if (isConnected) CctvSuccessGreen else CctvCardBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isConnected) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = "Mic Status",
                                tint = if (isConnected) CctvSuccessGreen else CctvTextSecondary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "MICROPHONE",
                            color = if (isConnected) CctvSuccessGreen else CctvTextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Stop Button
                Button(
                    onClick = {
                        viewModel.stopCameraMode()
                        onStopCamera()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("stop_camera_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CctvAlertRed)
                ) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "STOP BACKGROUND SERVICE", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        } else {
            // Extreme Blackout Screen - User can tap anywhere to wake up
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { viewModel.togglePowerSaver() },
                contentAlignment = Alignment.Center
            ) {
                // Invisible interaction layer
            }
        }
    }
}
"""

with open("app/src/main/java/com/example/ui/screens/CameraModeScreen.kt", "w") as f:
    f.write(new_content)

