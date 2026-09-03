package com.example.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.BatteryStatusChip
import com.example.ui.strings.AppLanguage
import com.example.ui.theme.*
import com.example.ui.viewmodel.CctvViewModel

@Composable
fun CameraModeScreen(
    viewModel: CctvViewModel,
    language: AppLanguage,
    onStopCamera: () -> Unit,
    onStartScreenMirroring: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboardManager = LocalClipboardManager.current
    
    val roomPin by viewModel.cameraRoomPin.collectAsState()
    val isStreaming by viewModel.isCameraStreaming.collectAsState()
    val connectedViewers by viewModel.connectedViewersCount.collectAsState()
    val isCameraScreenLocked by viewModel.isCameraScreenLocked.collectAsState()
    val isVoiceFilterEnabled by viewModel.isVoiceFilterEnabled.collectAsState()
    val securityPin by viewModel.cameraSecurityPin.collectAsState()
    val cameraTelemetry by viewModel.cameraTelemetry.collectAsState()

    var lockPinInput by remember { mutableStateOf("") }
    var unlockError by remember { mutableStateOf(false) }


    // Start background CCTV service immediately on entering this screen
    LaunchedEffect(Unit) {
        viewModel.startCameraMode(lifecycleOwner, null)
    }

    if (isCameraScreenLocked) {
        // --- 1. FULLSCREEN SECURE LOCK OVERLAY ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090B10))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Glowing Lock Icon
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF673AB7).copy(alpha = 0.2f))
                        .border(2.dp, Color(0xFF9C27B0), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked",
                        tint = Color(0xFFCE93D8),
                        modifier = Modifier.size(52.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Live Pulse Status
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1E1B2E),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(CctvSuccessGreen)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "🔴 CCTV 24x7 LIVE ACTIVE",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (language == AppLanguage.HINDI) "🔒 CCTV कैमरा सुरक्षित लॉक है" else "🔒 CCTV Camera Protected & Locked",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (language == AppLanguage.HINDI)
                        "यह फोन नए मोबाइल (Viewer) द्वारा रिमोटली लॉक किया गया है। कोई भी इस ऐप को अनइंस्टॉल या बंद नहीं कर सकता।"
                    else
                        "This camera is locked remotely from the Viewer device. Unauthorized deletion or tampering is prevented.",
                    fontSize = 13.sp,
                    color = Color(0xFFA0AEC0),
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(28.dp))

                // PIN Entry Card to unlock locally
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF3B2D54), RoundedCornerShape(18.dp)),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151221))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (language == AppLanguage.HINDI) "अनलॉक करने हेतु पिन डालें" else "Enter PIN to Unlock Screen",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFD1C4E9)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = lockPinInput,
                            onValueChange = {
                                if (it.length <= 8) {
                                    lockPinInput = it
                                    unlockError = false
                                }
                            },
                            placeholder = { Text("पिन डालें (उदा. $roomPin)", color = Color.Gray, textAlign = TextAlign.Center) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                textAlign = TextAlign.Center,
                                fontSize = 20.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 3.sp
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = if (unlockError) CctvAlertRed else Color(0xFFAB47BC),
                                unfocusedBorderColor = if (unlockError) CctvAlertRed else Color(0xFF4A148C),
                                focusedContainerColor = Color(0xFF1E1B2E),
                                unfocusedContainerColor = Color(0xFF0F0D17)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (unlockError) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (language == AppLanguage.HINDI) "❌ गलत पिन! सही कोड डालें।" else "❌ Incorrect PIN! Please re-enter.",
                                color = CctvAlertRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                val success = viewModel.unlockCameraScreenLocally(lockPinInput)
                                if (success) {
                                    lockPinInput = ""
                                    unlockError = false
                                    Toast.makeText(
                                        context,
                                        if (language == AppLanguage.HINDI) "🔓 फोन अनलॉक हो गया" else "🔓 Phone Unlocked",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    unlockError = true
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF673AB7),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (language == AppLanguage.HINDI) "फोन अनलॉक करें" else "Unlock Phone",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CctvDarkBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // 1. Status Indicator Badge & Battery
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(30.dp),
                        color = CctvCardBgSecondary,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (connectedViewers > 0) Color(0xFFFF1744) else Color(0xFF00E676))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (connectedViewers > 0) {
                                    if (language == AppLanguage.HINDI) "🔴 लाइव स्ट्रीम चालू (कैमरा सक्रिय)" else "🔴 Live Streaming (Camera ON)"
                                } else {
                                    if (language == AppLanguage.HINDI) "💤 स्टैंडबाय (कैमरा हार्डवेयर बंद)" else "💤 Standby (Camera Hardware OFF)"
                                },
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Battery Chip
                    BatteryStatusChip(
                        level = cameraTelemetry.batteryLevel,
                        isCharging = cameraTelemetry.isCharging
                    )
                }


                // 2. Large Pairing Code Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CctvCardBorder, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = CctvCardBg)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (language == AppLanguage.HINDI) "पेयरिंग कोड (ROOM PIN)" else "PAIRING CODE (ROOM PIN)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = CctvTextMuted,
                            letterSpacing = 1.5.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Large PIN display
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CctvNavyDark,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(2.dp, CctvNavyPrimary, RoundedCornerShape(12.dp))
                        ) {
                            Text(
                                text = roomPin.chunked(3).joinToString(" "),
                                fontSize = 38.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = CctvIceBlue,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Copy PIN Button
                        FilledTonalButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(roomPin))
                                Toast.makeText(
                                    context,
                                    if (language == AppLanguage.HINDI) "कोड कॉपी हो गया: $roomPin" else "PIN Copied: $roomPin",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = CctvCardBgSecondary,
                                contentColor = CctvTextPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (language == AppLanguage.HINDI) "कोड कॉपी करें" else "Copy PIN",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2.5 Voice Isolation DSP Feature Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, if (isVoiceFilterEnabled) Color(0xFF00897B) else CctvCardBorder, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isVoiceFilterEnabled) Color(0xFF004D40).copy(alpha = 0.4f) else CctvCardBg.copy(alpha = 0.6f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(if (isVoiceFilterEnabled) Color(0xFF00897B) else Color(0xFF37474F)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (language == AppLanguage.HINDI) "🎙️ गाड़ी व बाइक का शोर बंद" else "🎙️ Traffic Noise Filter",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (language == AppLanguage.HINDI)
                                        "गाड़ियों का भारी शोर हटाकर केवल इंसान की साफ़ आवाज़ सुनाई देगी।"
                                    else
                                        "Engine and road rumbles are filtered out so only human speech is clear.",
                                    fontSize = 12.sp,
                                    color = CctvTextSecondary,
                                    lineHeight = 16.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Switch(
                            checked = isVoiceFilterEnabled,
                            onCheckedChange = { viewModel.toggleVoiceFilter() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF00897B),
                                uncheckedThumbColor = Color.LightGray,
                                uncheckedTrackColor = Color(0xFF263238)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Flip Camera Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.switchCameraLens(lifecycleOwner, null) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CctvCardBg)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF673AB7)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cameraswitch,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (language == AppLanguage.HINDI) "🔄 कैमरा पलटें (फ्रंट / बैक)" else "🔄 Switch Camera (Front/Back)",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (language == AppLanguage.HINDI) "आगे या पीछे का कैमरा बदलें" else "Change between front and back camera",
                                    fontSize = 12.sp,
                                    color = CctvTextSecondary
                                )
                            }
                        }
                        Button(
                            onClick = { viewModel.switchCameraLens(lifecycleOwner, null) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF673AB7)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (language == AppLanguage.HINDI) "बदलें" else "Switch")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3. Simple Guidance Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CctvCardBg.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        Text(
                            text = if (language == AppLanguage.HINDI) "आगे क्या करना है:" else "What to do next:",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = CctvIceBlue
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (language == AppLanguage.HINDI)
                                "1. यह 6-अंकों का कोड अपने नए फोन (Viewer Mode) में डालें।\n\n2. जब तक नया फोन नहीं देखता, इस पुराने फोन का कैमरा हार्डवेयर और माइक पूरी तरह बंद (Standby) रहेगा, जिससे कोई कैमरा आइकॉन नहीं आएगा और बैटरी बचेगी।\n\n3. जैसे ही नया फोन कनेक्ट करेगा, कैमरा अपने आप चालू हो जाएगा और नया फोन कट/बंद करते ही तुरंत बंद हो जाएगा।"
                            else
                                "1. Enter this 6-digit PIN in your New Phone (Viewer Mode).\n\n2. While waiting, camera hardware & mic stay completely OFF in silent Standby (no privacy green dots or camera icon).\n\n3. Camera turns ON only when the Viewer connects and turns OFF immediately when Viewer disconnects.",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = CctvTextSecondary
                        )
                    }
                }
            }

            // 4. Bottom Action Buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Button to Lock Screen Immediately
                Button(
                    onClick = {
                        viewModel.lockCameraScreenLocally()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF512DA8),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (language == AppLanguage.HINDI) "🔒 फोन स्क्रीन तुरंत लॉक करें" else "🔒 Lock Phone Screen Now",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Button to minimize app & run silently in background
                Button(
                    onClick = {
                        Toast.makeText(
                            context,
                            if (language == AppLanguage.HINDI) "कैमरा बैकग्राउंड में 24 घंटे चालू रहेगा" else "CCTV Camera running 24/7 in background",
                            Toast.LENGTH_LONG
                        ).show()
                        (context as? Activity)?.moveTaskToBack(true)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CctvSuccessGreen,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (language == AppLanguage.HINDI) "ऐप से बाहर आएं (बैकग्राउंड में चालू रहेगा)" else "Minimize (Runs in Background)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Anti-Uninstall Protection Button (Device Administrator)
                Button(
                    onClick = {
                        try {
                            val dpm = context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                            val adminComponent = android.content.ComponentName(context, com.example.receiver.AdminReceiver::class.java)
                            val intent = android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                putExtra(
                                    android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    if (language == AppLanguage.HINDI)
                                        "यह सुरक्षा सक्रिय करने पर कोई भी इस ऐप को बिना अनुमति के डिलीट (अनइंस्टॉल) नहीं कर पाएगा।"
                                    else
                                        "Enabling this protection prevents anyone from uninstalling or deleting this app without authorization."
                                )
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF673AB7),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (language == AppLanguage.HINDI) "🔒 ऐप डिलीट होने से रोकें (Anti-Uninstall)" else "🔒 Prevent App Deletion (Anti-Uninstall)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Screen Mirroring Button
                Button(
                    onClick = {
                        onStartScreenMirroring()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0288D1),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (language == AppLanguage.HINDI) "📱 मोबाइल स्क्रीन शेयरिंग शुरू करें (Screen Mirror)" else "📱 Start Screen Mirroring",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Stop Camera Button
                var showPinDialog by remember { mutableStateOf(false) }
                var enteredPin by remember { mutableStateOf("") }

                OutlinedButton(
                    onClick = {
                        showPinDialog = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = CctvAlertRed
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = CctvAlertRed, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (language == AppLanguage.HINDI) "कैमरा बंद करें (Stop Camera)" else "Stop Camera",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CctvAlertRed
                    )
                }

                if (showPinDialog) {
                    AlertDialog(
                        onDismissRequest = { showPinDialog = false },
                        title = {
                            Text(if (language == AppLanguage.HINDI) "🔒 सुरक्षा कोड (Room PIN) दर्ज करें" else "🔒 Enter Security PIN")
                        },
                        text = {
                            Column {
                                Text(
                                    if (language == AppLanguage.HINDI)
                                        "पुराने फोन से कैमरा बंद करने के लिए रूम पिन दर्ज करें:"
                                    else
                                        "Enter the Room PIN to stop camera mode:"
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = enteredPin,
                                    onValueChange = { enteredPin = it },
                                    singleLine = true,
                                    placeholder = { Text("Enter PIN") }
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (enteredPin.trim() == roomPin.trim()) {
                                        showPinDialog = false
                                        enteredPin = ""
                                        onStopCamera()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            if (language == AppLanguage.HINDI) "❌ गलत पिन! कैमरा बंद नहीं किया जा सकता।" else "❌ Incorrect PIN! Cannot stop camera.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CctvAlertRed)
                            ) {
                                Text(if (language == AppLanguage.HINDI) "कैमरा बंद करें" else "Confirm Stop")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPinDialog = false; enteredPin = "" }) {
                                Text(if (language == AppLanguage.HINDI) "रद्द करें" else "Cancel")
                            }
                        }
                    )
                }
            }
        }
    }
}
