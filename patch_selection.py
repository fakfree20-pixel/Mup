import sys

with open("app/src/main/java/com/example/ui/screens/ModeSelectionScreen.kt", "r") as f:
    content = f.read()

# Let's replace the whole ModeSelectionScreen to be super clean: just two buttons.
new_content = """package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.strings.AppLanguage
import com.example.ui.strings.AppStrings
import com.example.ui.theme.*
import com.example.AppRole

@Composable
fun ModeSelectionScreen(
    language: AppLanguage,
    onSelectRole: (AppRole) -> Unit,
    onOpenGallery: () -> Unit,
    onToggleLanguage: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CctvDarkBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            
            // 1. OLD PHONE (Camera Mode)
            Button(
                onClick = { onSelectRole(AppRole.CAMERA_DEVICE) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CctvNavyPrimary,
                    contentColor = CctvIceBlue
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Camera",
                        tint = CctvIceBlue,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = if (language == AppLanguage.HINDI) "पुराना फोन (कैमरा)" else "OLD PHONE (Camera)",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = CctvIceBlue
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // 2. NEW PHONE (Viewer Mode)
            Button(
                onClick = { onSelectRole(AppRole.VIEWER_DEVICE) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CctvCardBgSecondary,
                    contentColor = CctvTextPrimary
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Viewer",
                        tint = CctvIceBlue,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = if (language == AppLanguage.HINDI) "नया फोन (देखने के लिए)" else "NEW PHONE (Viewer)",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = CctvTextPrimary
                    )
                }
            }
        }
    }
}
"""

with open("app/src/main/java/com/example/ui/screens/ModeSelectionScreen.kt", "w") as f:
    f.write(new_content)
