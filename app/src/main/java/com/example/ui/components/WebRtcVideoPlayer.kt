package com.example.ui.components

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private const val TAG = "WebRtcVideoPlayer"

@Composable
fun WebRtcVideoPlayer(
    videoTrack: VideoTrack?,
    eglBase: EglBase?,
    modifier: Modifier = Modifier,
    isMirror: Boolean = false,
    onReconnectClick: (() -> Unit)? = null
) {
    var rendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var attachedTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var isFirstFrameRendered by remember { mutableStateOf(false) }
    var showReconnectPrompt by remember { mutableStateOf(false) }

    // Reset first frame when track changes or disconnects
    LaunchedEffect(videoTrack) {
        if (videoTrack == null) {
            isFirstFrameRendered = false
            showReconnectPrompt = false
        } else {
            // If frame doesn't render within 7 seconds, offer reconnect prompt
            delay(7000)
            if (!isFirstFrameRendered) {
                showReconnectPrompt = true
            }
        }
    }

    val rendererEvents = remember {
        object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {
                Log.d(TAG, "First WebRTC video frame rendered successfully!")
                isFirstFrameRendered = true
                showReconnectPrompt = false
            }

            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                Log.d(TAG, "Video resolution changed: ${width}x${height}, rotation=$rotation")
                isFirstFrameRendered = true
                showReconnectPrompt = false
            }
        }
    }

    // Attach/detach track safely when track or renderer changes
    LaunchedEffect(videoTrack, rendererRef) {
        val renderer = rendererRef
        if (renderer != null) {
            if (attachedTrack != null && attachedTrack != videoTrack) {
                try {
                    attachedTrack?.removeSink(renderer)
                    Log.d(TAG, "Removed old track sink")
                } catch (e: Exception) {
                    Log.w(TAG, "Error removing old sink: ${e.message}")
                }
                attachedTrack = null
            }

            if (videoTrack != null && attachedTrack != videoTrack) {
                try {
                    videoTrack.setEnabled(true)
                    videoTrack.addSink(renderer)
                    attachedTrack = videoTrack
                    Log.d(TAG, "Attached VideoTrack ($videoTrack) to SurfaceViewRenderer")
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding sink to videoTrack: ${e.message}")
                }
            }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Native SurfaceView for hardware-accelerated video rendering
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    try {
                        val eglContext = eglBase?.eglBaseContext
                        init(eglContext, rendererEvents)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        setMirror(isMirror)
                        setEnableHardwareScaler(true)
                        Log.d(TAG, "SurfaceViewRenderer initialized with EglContext: $eglContext")
                    } catch (e: Exception) {
                        Log.e(TAG, "SurfaceViewRenderer init error: ${e.message}", e)
                    }
                    rendererRef = this
                }
            },
            update = { renderer ->
                renderer.setMirror(isMirror)
                if (videoTrack != null && attachedTrack != videoTrack) {
                    try {
                        attachedTrack?.removeSink(renderer)
                    } catch (_: Exception) {}
                    try {
                        videoTrack.setEnabled(true)
                        videoTrack.addSink(renderer)
                        attachedTrack = videoTrack
                        Log.d(TAG, "Attached VideoTrack in update block")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error attaching sink in update: ${e.message}")
                    }
                }
            },
            onRelease = { renderer ->
                try {
                    attachedTrack?.removeSink(renderer)
                } catch (_: Exception) {}
                try {
                    renderer.release()
                } catch (_: Exception) {}
                attachedTrack = null
                rendererRef = null
                isFirstFrameRendered = false
                Log.d(TAG, "SurfaceViewRenderer released")
            }
        )

        // Loading Overlay: Visible while waiting for the first video frame
        AnimatedVisibility(
            visible = !isFirstFrameRendered,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F1117)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(Color(0x2210B981), RoundedCornerShape(36.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "Camera Stream",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    CircularProgressIndicator(
                        color = Color(0xFF10B981),
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(36.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (videoTrack != null) "लाइव वीडियो शुरू हो रहा है..." else "पुराने फोन के कैमरे से जुड़ रहे हैं...",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "कम लेटेंसी P2P वीडियो स्ट्रीम तैयार हो रही है",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )

                    if (showReconnectPrompt && onReconnectClick != null) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onReconnectClick,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reconnect",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "रीकनेक्ट करें (Reconnect)",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
