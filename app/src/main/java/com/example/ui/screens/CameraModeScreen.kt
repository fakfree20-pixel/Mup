package com.example.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
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
import com.example.ui.strings.AppLanguage
import com.example.ui.theme.*
import com.example.ui.viewmodel.CctvViewModel

@Composable
fun CameraModeScreen(
    viewModel: CctvViewModel,
    language: AppLanguage,
    onStopCamera: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboardManager = LocalClipboardManager.current
    
    val roomPin by viewModel.cameraRoomPin.collectAsState()
    val isStreaming by viewModel.isCameraStreaming.collectAsState()
    val connectedViewers by viewModel.connectedViewersCount.collectAsState()

    // Start background CCTV service immediately on entering this screen
    LaunchedEffect(Unit) {
        viewModel.startCameraMode(lifecycleOwner, null)
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

                // 1. Status Indicator Badge
                Surface(
                    shape = RoundedCornerShape(30.dp),
                    color = CctvCardBgSecondary,
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (connectedViewers > 0) CctvSuccessGreen else Color(0xFF00E676))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (connectedViewers > 0) {
                                if (language == AppLanguage.HINDI) "🟢 नया फोन लाइव देख रहा है" else "🟢 Viewer Connected (Live)"
                            } else {
                                if (language == AppLanguage.HINDI) "🟢 कैमरा 24x7 बैकग्राउंड में सक्रिय है" else "🟢 CCTV Active in Background"
                            },
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
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

                Spacer(modifier = Modifier.height(20.dp))

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
                                "1. यह 6-अंकों का कोड अपने नए फोन (Viewer) में डालें।\n\n2. कोड डालने के बाद आप इस पुराने फोन की ऐप को बंद कर सकते हैं या स्क्रीन ऑफ कर सकते हैं।\n\n3. नया फोन जब भी चाहेगा, तुरंत लाइव वीडियो देख सकेगा। फोन रीस्टार्ट होने पर भी यह अपने आप बैकग्राउंड में चालू रहेगा।"
                            else
                                "1. Enter this 6-digit PIN in your New Phone (Viewer Mode).\n\n2. After setting up, you can exit this app or lock the screen.\n\n3. The camera stays ready 24/7 in the background and starts streaming the instant the new phone connects.",
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

                // Stop Camera Button
                OutlinedButton(
                    onClick = {
                        onStopCamera()
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
            }
        }
    }
}
