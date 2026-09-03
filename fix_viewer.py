import re

with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "r") as f:
    content = f.read()

# We need to find where I messed up the file.
# The messed up part starts from:
#                     // Speakerphone Toggle (हैंड्स-फ्री)
# ...
#                     }
#                 }
#             }
#         } else {                                    if (language == AppLanguage.HINDI) "🎙️ नॉइज़ फ़िल्टर चालू करें" else "🎙️ Turn On Noise Filter"
#                                 },

# Let's truncate the file at `} else {` and append a proper Connect Screen and Dialog.

match = re.search(r"(\s*// Speakerphone Toggle.*?\n\s*\}\n\s*\}\n\s*\}\n\s*\} else \{)", content, re.DOTALL)
if match:
    prefix = content[:match.end()]
    suffix = """
            // 3. ENTER ROOM PIN / CONNECT SCREEN
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F1117))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (isAttemptingConnection) {
                    CircularProgressIndicator(
                        color = Color(0xFFCE93D8),
                        modifier = Modifier.size(52.dp),
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = if (language == AppLanguage.HINDI) "कैमरा से कनेक्ट हो रहा है..." else "Connecting to Camera...",
                        color = Color.White,
                        fontSize = 16.sp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Camera",
                        modifier = Modifier.size(72.dp),
                        tint = Color(0xFFCE93D8)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = if (language == AppLanguage.HINDI) "कैमरा से जुड़ें" else "Connect to Camera",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (language == AppLanguage.HINDI) "पुराने फोन का रूम पिन डालें" else "Enter old phone's Room PIN",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    OutlinedTextField(
                        value = roomPinInput,
                        onValueChange = { viewModel.setViewerRoomPinInput(it) },
                        label = { Text("Room PIN") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFCE93D8),
                            unfocusedBorderColor = Color.Gray,
                            cursorColor = Color(0xFFCE93D8)
                        ),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        onClick = { viewModel.connectViewer() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF673AB7)
                        )
                    ) {
                        Text(
                            text = if (language == AppLanguage.HINDI) "कनेक्ट करें" else "CONNECT",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        
        // Security Lock Dialog
        if (showSecurityLockDialog) {
            AlertDialog(
                onDismissRequest = { showSecurityLockDialog = false },
                title = {
                    Text(
                        text = if (language == AppLanguage.HINDI) "कैमरा नियंत्रण" else "Camera Controls",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (language == AppLanguage.HINDI) "सुरक्षा पिन (4-6 अंक):" else "Security PIN (4-6 digits):",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = securityPinInput,
                            onValueChange = { if (it.length <= 6 && it.all { ch -> ch.isDigit() }) securityPinInput = it },
                            placeholder = { Text("e.g. 1234", color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFCE93D8),
                                unfocusedBorderColor = Color.Gray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Button(
                            onClick = {
                                val pinToSet = if (securityPinInput.isNotBlank()) securityPinInput else "1234"
                                viewModel.setRemoteSecurityPin(pinToSet)
                                showSecurityLockDialog = false
                            },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF673AB7))
                        ) {
                            Text(if (language == AppLanguage.HINDI) "🔒 लॉक करें" else "🔒 Lock Camera Screen")
                        }
                        
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        OutlinedButton(
                            onClick = {
                                viewModel.unlockCameraScreenRemotely()
                                showSecurityLockDialog = false
                            },
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
                            Text(if (language == AppLanguage.HINDI) "🔓 अनलॉक करें" else "🔓 Unlock Camera Screen")
                        }
                        
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        Button(
                            onClick = { viewModel.toggleVoiceFilter() },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isVoiceFilterEnabled) Color(0xFF00897B) else Color(0xFF455A64))
                        ) {
                            Text(if (isVoiceFilterEnabled) "🎙️ Filter ON" else "🎙️ Filter OFF")
                        }
                        
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        Button(
                            onClick = {
                                viewModel.remoteToggleBlackout()
                                showSecurityLockDialog = false
                            },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238))
                        ) {
                            Text("🕶️ Stealth Black Screen")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSecurityLockDialog = false }) {
                        Text(if (language == AppLanguage.HINDI) "बंद करें" else "Close", color = Color(0xFFCE93D8))
                    }
                },
                containerColor = Color(0xFF181524)
            )
        }
    }
}
"""
    with open("app/src/main/java/com/example/ui/screens/ViewerModeScreen.kt", "w") as f:
        f.write(prefix + suffix)
    print("Fixed!")
else:
    print("Match not found")
