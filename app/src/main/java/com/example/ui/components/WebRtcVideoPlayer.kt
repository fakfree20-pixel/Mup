package com.example.ui.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun WebRtcVideoPlayer(
    videoTrack: VideoTrack?,
    eglBase: EglBase,
    modifier: Modifier = Modifier,
    isMirror: Boolean = false
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    var rendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var attachedTrack by remember { mutableStateOf<VideoTrack?>(null) }

    // Safely attach/detach video track to the SurfaceViewRenderer
    LaunchedEffect(videoTrack, rendererRef) {
        val renderer = rendererRef
        if (renderer != null) {
            if (attachedTrack != null && attachedTrack != videoTrack) {
                try {
                    attachedTrack?.removeSink(renderer)
                } catch (e: Exception) {
                    Log.w("WebRtcVideoPlayer", "Error removing old sink: ${e.message}")
                }
                attachedTrack = null
            }

            if (videoTrack != null && attachedTrack != videoTrack) {
                try {
                    videoTrack.addSink(renderer)
                    attachedTrack = videoTrack
                    Log.d("WebRtcVideoPlayer", "Attached videoTrack to SurfaceViewRenderer")
                } catch (e: Exception) {
                    Log.e("WebRtcVideoPlayer", "Error adding sink to videoTrack: ${e.message}")
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val renderer = rendererRef
            val track = attachedTrack
            if (renderer != null && track != null) {
                try {
                    track.removeSink(renderer)
                } catch (_: Exception) {}
            }
            try {
                renderer?.release()
            } catch (_: Exception) {}
            rendererRef = null
            attachedTrack = null
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                ),
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    try {
                        init(eglBase.eglBaseContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setMirror(isMirror)
                        setEnableHardwareScaler(true)
                    } catch (e: Exception) {
                        Log.e("WebRtcVideoPlayer", "Init error: ${e.message}")
                    }
                    rendererRef = this
                }
            },
            update = { renderer ->
                renderer.setMirror(isMirror)
            }
        )
    }
}
