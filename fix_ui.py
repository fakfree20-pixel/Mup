import re

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "r") as f:
    content = f.read()

new_controls = """                // Secondary Controls Row (Torch, Switch Camera, Audio Only)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp, start = 48.dp, end = 48.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Flashlight
                    IconButton(
                        onClick = { viewModel.sendRemoteCommand("TOGGLE_TORCH") },
                        modifier = Modifier
                            .size(48.dp)
                            .background(if (remoteTelemetry.isTorchOn) CctvSuccessGreen else Color(0x77000000), CircleShape)
                    ) {
                        Icon(Icons.Default.FlashlightOn, contentDescription = "Flashlight", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    
                    // Remote Switch Camera (Front/Back)
                    IconButton(
                        onClick = { viewModel.sendRemoteCommand("SWITCH_CAMERA") },
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color(0x77000000), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Cameraswitch, 
                            contentDescription = "Switch Camera", 
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    // Audio Only Mode Toggle Button
                    IconButton(
                        onClick = { viewModel.toggleAudioOnlyMode() },
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                if (isAudioOnlyMode) CctvSuccessGreen else Color(0x77000000), 
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isAudioOnlyMode) Icons.Default.MusicNote else Icons.Default.VideocamOff,
                            contentDescription = "Audio Only",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Bottom Bar Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 36.dp, start = 36.dp, end = 36.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,"""

content = content.replace("""                // Bottom Bar Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 36.dp, start = 28.dp, end = 28.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,""", new_controls)

speakerphone_button = """                    // Speakerphone Toggle (हैंड्स-फ्री)
                    IconButton(
                        onClick = { viewModel.toggleSpeakerphone() },
                        modifier = Modifier
                            .size(54.dp)
                            .background(
                                if (isSpeakerphoneOn) CctvSuccessGreen else Color(0x77000000), 
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isSpeakerphoneOn) Icons.Default.VolumeUp else Icons.Default.PhoneInTalk,
                            contentDescription = "Speakerphone",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        } else {"""

content = re.sub(r"                    // Remote Switch Camera.*} else \{", speakerphone_button, content, flags=re.DOTALL)

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "w") as f:
    f.write(content)
