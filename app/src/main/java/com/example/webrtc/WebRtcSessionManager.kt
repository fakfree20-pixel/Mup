package com.example.webrtc

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors

class WebRtcSessionManager(
    private val context: Context,
    val isCameraMode: Boolean
) {
    companion object {
        @Volatile
        var isWebRtcInitialized = false

        val rootEglBase: EglBase? by lazy {
            try {
                EglBase.create()
            } catch (e: Throwable) {
                android.util.Log.e("WebRtcSessionManager", "EglBase creation failed", e)
                null
            }
        }
    }
    
    private val TAG = "WebRtcSessionManager"

    val eglBase: EglBase? get() = rootEglBase

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null

    // Media Tracks
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    // Remote Tracks (for Viewer & Camera)
    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack

    private val _connectionState = MutableStateFlow(WebRtcConnectionState.IDLE)
    val connectionState: StateFlow<WebRtcConnectionState> = _connectionState

    private val _statusText = MutableStateFlow("Ready")
    val statusText: StateFlow<String> = _statusText

    private var signalingClient: WebRtcSignalingClient? = null
    private var currentRoomId: String = ""
    private var currentIsFrontCamera: Boolean = false
    private var lastOfferTimestamp: Long = 0L
    private val executor = Executors.newSingleThreadExecutor()

    // Callbacks
    var onCommandReceived: ((String) -> Unit)? = null
    var onRemoteSnapshotRequested: (() -> Unit)? = null
    var onViewerConnected: (() -> Unit)? = null
    var onViewerDisconnected: (() -> Unit)? = null

    @Volatile
    private var isCameraHardwareActive = false
    val isCameraActive: Boolean get() = isCameraHardwareActive

    /**
     * Worldwide Global STUN & TURN Relay Infrastructure:
     * - Anycast Global Google STUN Cluster
     * - Anycast Global Cloudflare STUN
     * - Worldwide Metered Multi-Region TURN Relays (Port 80, 443 TCP/UDP, TLS 443)
     * Bypasses all ISP firewalls, NATs, and restrictions worldwide (US, EU, Middle East, Asia, India, etc.)
     */
    private val iceServers = listOf(
        // Google Global Anycast STUN (Fastest, sub-20ms resolution worldwide)
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        // Cloudflare Anycast STUN
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        // Global TURN Relays (Fastest responsive ports: UDP 80, 443 + TLS 443 fallback)
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turns:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer()
    )

    private val pendingIceCandidates = java.util.Collections.synchronizedList(mutableListOf<IceCandidate>())
    private val localIceCandidates = java.util.Collections.synchronizedList(mutableListOf<IceCandidate>())
    
    @Volatile
    private var isRemoteDescriptionSet = false
    @Volatile
    private var isCreatingOffer = false
    @Volatile
    private var isNegotiating = false

    init {
        initializePeerConnectionFactory()
        configureAudioManager()
    }

    private var currentSpeakerphoneState = false

    fun setSpeakerphoneEnabled(isEnabled: Boolean) {
        currentSpeakerphoneState = isEnabled
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.let {
                if (isEnabled) {
                    it.mode = AudioManager.MODE_NORMAL
                    it.isSpeakerphoneOn = true
                    it.setSpeakerphoneOn(true)
                } else {
                    it.mode = AudioManager.MODE_IN_COMMUNICATION
                    it.isSpeakerphoneOn = false
                    it.setSpeakerphoneOn(false)
                }
                Log.d(TAG, "Speakerphone set to $isEnabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting speakerphone", e)
        }
    }

    private fun configureAudioManager() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let { am ->
                val useSpeaker = currentSpeakerphoneState
                
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = useSpeaker
                am.setSpeakerphoneOn(useSpeaker)
                
                try {
                    val maxVoice = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoice, 0)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure AudioManager: ${e.message}")
        }
    }

    private fun initializePeerConnectionFactory() {
        synchronized(WebRtcSessionManager::class.java) {
            if (!isWebRtcInitialized) {
                try {
                    val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                    PeerConnectionFactory.initialize(options)
                    isWebRtcInitialized = true
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to initialize PeerConnectionFactory", e)
                }
            }
        }

        val encoderFactory = try {
            DefaultVideoEncoderFactory(rootEglBase?.eglBaseContext, true, true)
        } catch (e: Throwable) {
            org.webrtc.SoftwareVideoEncoderFactory()
        }
        
        val decoderFactory = try {
            DefaultVideoDecoderFactory(rootEglBase?.eglBaseContext)
        } catch (e: Throwable) {
            org.webrtc.SoftwareVideoDecoderFactory()
        }

        val isAecSupported = JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
        val isNsSupported = JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()

        val audioAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        try {
            audioDeviceModule = JavaAudioDeviceModule.builder(context)
                .setAudioAttributes(audioAttributes)
                .setUseHardwareAcousticEchoCanceler(isAecSupported)
                .setUseHardwareNoiseSuppressor(isNsSupported)
                .createAudioDeviceModule()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create JavaAudioDeviceModule", e)
        }

        try {
            val builder = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .setOptions(PeerConnectionFactory.Options())
            
            audioDeviceModule?.let { builder.setAudioDeviceModule(it) }
            
            peerConnectionFactory = builder.createPeerConnectionFactory()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create PeerConnectionFactory", e)
        }
    }

    fun startSession(
        scope: CoroutineScope,
        roomId: String,
        isFrontCamera: Boolean = false
    ) {
        currentRoomId = roomId
        currentIsFrontCamera = isFrontCamera
        _connectionState.value = WebRtcConnectionState.CONNECTING_SIGNALING
        _statusText.value = "Connecting to Global Network ($roomId)..."

        setupPeerConnection(scope)

        signalingClient = WebRtcSignalingClient(
            clientRole = if (isCameraMode) "CAMERA" else "VIEWER",
            roomId = roomId,
            onMessageReceived = { msg ->
                handleSignalingMessage(scope, msg)
            },
            onStateChanged = { status ->
                _statusText.value = status
            }
        ).apply {
            start(scope)
        }

        if (isCameraMode) {
            _connectionState.value = WebRtcConnectionState.WAITING_PEER
            _statusText.value = "💤 Standby (Camera & Mic Off) - Waiting for viewer..."
            // Camera hardware and microphone remain completely OFF until a viewer connects!
        } else {
            _connectionState.value = WebRtcConnectionState.WAITING_PEER
            _statusText.value = "Connecting to Camera..."

            scope.launch(Dispatchers.IO) {
                signalingClient?.sendMessage(
                    SignalingMessage(
                        type = "ROOM_JOINED",
                        senderId = "VIEWER",
                        targetRoom = roomId
                    )
                )
                delay(300)
                if (_connectionState.value == WebRtcConnectionState.WAITING_PEER) {
                    signalingClient?.sendMessage(
                        SignalingMessage(
                            type = "ROOM_JOINED",
                            senderId = "VIEWER",
                            targetRoom = roomId
                        )
                    )
                }
            }
        }

        // Background watchdog: retry if waiting for peer
        scope.launch(Dispatchers.IO) {
            var retryCount = 0
            // Initial grace period of 4.5 seconds to allow initial handshake without interruption
            delay(4500)
            while (scope.isActive) {
                delay(3500)
                val state = _connectionState.value
                if (state == WebRtcConnectionState.WAITING_PEER || state == WebRtcConnectionState.FAILED) {
                    retryCount++
                    if (!isCameraMode && retryCount <= 6) {
                        Log.d(TAG, "Watchdog ($retryCount): Sending ROOM_JOINED sync...")
                        signalingClient?.sendMessage(
                            SignalingMessage(
                                type = "ROOM_JOINED",
                                senderId = "VIEWER",
                                targetRoom = roomId
                            )
                        )
                    } else if (isCameraMode && state == WebRtcConnectionState.FAILED) {
                        Log.d(TAG, "Watchdog: Restarting ICE on Camera...")
                        peerConnection?.restartIce()
                    }
                } else if (state == WebRtcConnectionState.CONNECTED || state == WebRtcConnectionState.EXCHANGING_SDP || state == WebRtcConnectionState.CONNECTING_P2P) {
                    // Reset retry count once connection establishes or exchanges sdp
                    retryCount = 0
                }
                
                // Audio Watchdog: Continuously enforce audio routing to prevent OS/WebRTC from reverting to earpiece
                if (state == WebRtcConnectionState.CONNECTED) {
                    configureAudioManager()
                }
            }
        }
    }

    private fun setupPeerConnection(scope: CoroutineScope) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            keyType = PeerConnection.KeyType.ECDSA
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "SignalingState: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "IceConnectionState: $state")
                scope.launch(Dispatchers.Main) {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            isNegotiating = false
                            _connectionState.value = WebRtcConnectionState.CONNECTED
                            _statusText.value = "● Live Stream Connected"
                            configureAudioManager()
                            if (isCameraMode) {
                                onViewerConnected?.invoke()
                            }
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            isNegotiating = false
                            _connectionState.value = WebRtcConnectionState.DISCONNECTED
                            _statusText.value = "Viewer disconnected"
                            if (isCameraMode) {
                                scope.launch(Dispatchers.IO) {
                                    delay(1000)
                                    if (_connectionState.value == WebRtcConnectionState.DISCONNECTED) {
                                        Log.d(TAG, "Viewer disconnected, immediately shutting down camera & mic")
                                        stopCameraHardware()
                                        onViewerDisconnected?.invoke()
                                        _connectionState.value = WebRtcConnectionState.WAITING_PEER
                                        _statusText.value = "💤 Standby (Camera & Mic Off) - Waiting for viewer..."
                                    }
                                }
                            }
                        }
                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.CLOSED -> {
                            isNegotiating = false
                            _connectionState.value = WebRtcConnectionState.FAILED
                            _statusText.value = "Connection closed"
                            if (isCameraMode) {
                                Log.d(TAG, "Connection failed/closed, shutting down camera & mic immediately")
                                stopCameraHardware()
                                onViewerDisconnected?.invoke()
                                _connectionState.value = WebRtcConnectionState.WAITING_PEER
                                _statusText.value = "💤 Standby (Camera & Mic Off) - Waiting for viewer..."
                            } else {
                                peerConnection?.restartIce()
                            }
                        }
                        PeerConnection.IceConnectionState.CHECKING -> {
                            _connectionState.value = WebRtcConnectionState.CONNECTING_P2P
                            _statusText.value = "Establishing Global Peer Connection..."
                        }
                        else -> {}
                    }
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "IceGatheringState: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                localIceCandidates.add(candidate)
                signalingClient?.let { client ->
                    val msg = SignalingMessage(
                        type = "ICE_CANDIDATE",
                        senderId = if (isCameraMode) "CAMERA" else "VIEWER",
                        targetRoom = currentRoomId,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                        candidate = candidate.sdp
                    )
                    client.sendMessage(msg)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream) {
                Log.d(TAG, "onAddStream with ${stream.videoTracks.size} video, ${stream.audioTracks.size} audio")
                if (stream.videoTracks.isNotEmpty()) {
                    val track = stream.videoTracks.first()
                    _remoteVideoTrack.value = track
                }
                if (stream.audioTracks.isNotEmpty()) {
                    for (track in stream.audioTracks) {
                        try {
                            track.setEnabled(true)
                            track.setVolume(1.0)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error enabling remote audio: ${e.message}")
                        }
                    }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "onTrack: Received remote VideoTrack")
                    track.setEnabled(true)
                    _remoteVideoTrack.value = track
                } else if (track is AudioTrack) {
                    Log.d(TAG, "onTrack: Received remote AudioTrack")
                    try {
                        track.setEnabled(true)
                        track.setVolume(1.0)
                        configureAudioManager()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error setting volume on remote audio track: ${e.message}")
                    }
                }
            }

            override fun onRemoveStream(stream: MediaStream) {
                _remoteVideoTrack.value = null
            }

            override fun onDataChannel(dc: DataChannel) {
                dataChannel = dc
                setupDataChannelListeners(dc)
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded")
            }
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)

        if (isCameraMode) {
            val dcInit = DataChannel.Init().apply {
                ordered = false
                maxRetransmits = 0
            }
            dataChannel = peerConnection?.createDataChannel("cctv_commands", dcInit)
            dataChannel?.let { setupDataChannelListeners(it) }
        } else {
            setupViewerMediaTracks()
        }
    }

    var onAudioDataReceived: ((ByteArray) -> Unit)? = null

    private fun setupDataChannelListeners(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Log.d(TAG, "DataChannel State: ${dc.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                
                if (buffer.binary) {
                    onAudioDataReceived?.invoke(data)
                } else {
                    val cmd = String(data, Charsets.UTF_8)
                    Log.d(TAG, "DataChannel message received: $cmd")
                    
                    if (isCameraMode && (cmd == "VIEWER_DISCONNECT" || cmd == "STOP_STREAM")) {
                        Log.d(TAG, "Received VIEWER_DISCONNECT command via DataChannel, stopping camera hardware")
                        executor.submit { stopCameraHardware() }
                    } else if (isCameraMode && cmd.startsWith("SET_SPEAKERPHONE:")) {
                        val isOn = cmd.substringAfter("SET_SPEAKERPHONE:").trim() == "1"
                        setSpeakerphoneEnabled(isOn)
                    }
                    
                    onCommandReceived?.invoke(cmd)
                }
            }
        })
    }
    
    fun sendAudioData(pcm: ByteArray) {
        try {
            dataChannel?.let { dc ->
                if (dc.state() == DataChannel.State.OPEN) {
                    // Drop packet if network buffer has > 8KB queued (~250ms of audio)
                    // This prevents queue buildup and guarantees real-time zero audio lag!
                    if (dc.bufferedAmount() > 8192) {
                        return
                    }
                    val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(pcm), true)
                    dc.send(buffer)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio via DataChannel", e)
        }
    }

    private fun setupViewerMediaTracks() {
        // Explicitly declare RECV_ONLY video transceiver so WebRTC allocates video decoder pipeline
        // directly aligned with camera's video offer.
        try {
            peerConnection?.addTransceiver(
                org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                org.webrtc.RtpTransceiver.RtpTransceiverInit(org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            Log.d(TAG, "Added RECV_ONLY video transceiver for viewer")
        } catch (e: Exception) {
            Log.w(TAG, "Could not add explicit RECV_ONLY video transceiver: ${e.message}")
        }
    }

    @Synchronized
    fun startCameraHardware(isFrontCamera: Boolean) {
        if (!isCameraMode) return
        if (isCameraHardwareActive && videoCapturer != null) {
            Log.d(TAG, "Camera hardware already running")
            return
        }

        val factory = peerConnectionFactory ?: return
        Log.d(TAG, "Opening camera hardware and mic on-demand...")

        try {
            if (surfaceTextureHelper == null) {
                surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcCaptureThread", rootEglBase?.eglBaseContext)
            }
            if (localVideoSource == null) {
                localVideoSource = factory.createVideoSource(false)
            }

            if (videoCapturer == null) {
                videoCapturer = createCameraCapturer(isFrontCamera)
                videoCapturer?.let { capturer ->
                    capturer.initialize(surfaceTextureHelper, context, localVideoSource?.capturerObserver)
                    try {
                        capturer.startCapture(640, 480, 30)
                        Log.d(TAG, "Camera started at 640x480 30fps")
                    } catch (e1: Throwable) {
                        Log.w(TAG, "640x480 capture failed, trying 1280x720: ${e1.message}")
                        try {
                            capturer.startCapture(1280, 720, 30)
                            Log.d(TAG, "Camera started at 1280x720 30fps")
                        } catch (e2: Throwable) {
                            Log.w(TAG, "1280x720 capture failed, trying 320x240: ${e2.message}")
                            capturer.startCapture(320, 240, 15)
                            Log.d(TAG, "Camera started at 320x240 15fps")
                        }
                    }
                }
            }

            if (localVideoTrack == null) {
                localVideoTrack = factory.createVideoTrack("CCTV_VIDEO_TRACK", localVideoSource)
                localVideoTrack?.setEnabled(true)
            }

            // NOTE: We do NOT create localAudioSource or localAudioTrack here anymore.
            // The camera's microphone is captured by AudioStreamManager (with VoiceIsolationDsp)
            // and the raw PCM bytes are sent over the DataChannel to the viewer.
            
            isCameraHardwareActive = true
            Log.d(TAG, "Camera hardware and microphone opened successfully")
        } catch (e: Throwable) {
            Log.e(TAG, "Error starting camera hardware", e)
        }
    }

    @Synchronized
    fun stopCameraHardware() {
        if (!isCameraMode) return
        Log.d(TAG, "Stopping camera hardware & mic. Returning to silent standby...")
        isCameraHardwareActive = false

        try {
            try {
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping video capturer: ${e.message}")
            }
            videoCapturer = null

            try {
                surfaceTextureHelper?.dispose()
            } catch (_: Exception) {}
            surfaceTextureHelper = null

            try {
                localVideoTrack?.setEnabled(false)
                localVideoTrack?.dispose()
            } catch (_: Exception) {}
            localVideoTrack = null

            try {
                localVideoSource?.dispose()
            } catch (_: Exception) {}
            localVideoSource = null

            try {
                localAudioTrack?.setEnabled(false)
                localAudioTrack?.dispose()
            } catch (_: Exception) {}
            localAudioTrack = null

            try {
                localAudioSource?.dispose()
            } catch (_: Exception) {}
            localAudioSource = null

            _connectionState.value = WebRtcConnectionState.WAITING_PEER
            _statusText.value = "Standby: Waiting for Viewer to connect..."
            onViewerDisconnected?.invoke()
            Log.d(TAG, "Camera hardware completely released (Standby Mode)")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing camera hardware", e)
        }
    }

    fun startScreenCapture(mediaProjectionData: android.content.Intent) {
        val factory = peerConnectionFactory ?: return
        try {
            if (localVideoTrack == null) {
                surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcScreenCaptureThread", rootEglBase?.eglBaseContext)
                localVideoSource = factory.createVideoSource(true)

                videoCapturer = ScreenCapturerAndroid(mediaProjectionData, object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        Log.d(TAG, "MediaProjection onStop")
                    }
                })

                videoCapturer?.let { capturer ->
                    capturer.initialize(surfaceTextureHelper, context, localVideoSource?.capturerObserver)
                    capturer.startCapture(1280, 720, 30)
                }

                localVideoTrack = factory.createVideoTrack("CCTV_SCREEN_TRACK", localVideoSource)
                localVideoTrack?.setEnabled(true)
                peerConnection?.addTrack(localVideoTrack, listOf("cctv_stream"))
            }

            // NOTE: We do NOT create localAudioSource or localAudioTrack here anymore.
            // The camera's microphone is captured by AudioStreamManager (with VoiceIsolationDsp)
            // and the raw PCM bytes are sent over the DataChannel to the viewer.
        } catch (e: Exception) {
            Log.e(TAG, "Error starting screen capture", e)
        }
    }

    fun enableViewerTwoWayAudio(enable: Boolean) {
        if (isCameraMode) return
        localAudioTrack?.setEnabled(enable)
        configureAudioManager()
    }

    fun enableLocalVideo(enable: Boolean) {
        if (!isCameraMode) return
        localVideoTrack?.setEnabled(enable)
        try {
            if (enable) {
                videoCapturer?.startCapture(1280, 720, 30)
            } else {
                videoCapturer?.stopCapture()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling local video: ${e.message}")
        }
    }

    private fun createCameraCapturer(isFront: Boolean): VideoCapturer? {
        val cameraEventsHandler = object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(errorDescription: String?) {
                Log.e(TAG, "Camera error event: $errorDescription")
            }
            override fun onCameraDisconnected() {
                Log.w(TAG, "Camera disconnected event")
            }
            override fun onCameraFreezed(errorDescription: String?) {
                Log.w(TAG, "Camera freezed event: $errorDescription")
            }
            override fun onCameraOpening(cameraName: String?) {
                Log.d(TAG, "Camera opening: $cameraName")
            }
            override fun onFirstFrameAvailable() {
                Log.d(TAG, "Camera first frame captured!")
            }
            override fun onCameraClosed() {
                Log.d(TAG, "Camera closed")
            }
        }

        val enumerators = mutableListOf<org.webrtc.CameraEnumerator>()

        // 1. Check Camera2 support
        if (org.webrtc.Camera2Enumerator.isSupported(context)) {
            try {
                enumerators.add(org.webrtc.Camera2Enumerator(context))
            } catch (e: Throwable) {
                Log.w(TAG, "Camera2Enumerator failed: ${e.message}")
            }
        }

        // 2. Camera1 with texture capture
        try {
            enumerators.add(org.webrtc.Camera1Enumerator(true))
        } catch (e: Throwable) {
            Log.w(TAG, "Camera1Enumerator (texture) failed: ${e.message}")
        }

        // 3. Camera1 without texture capture (raw buffer fallback for older phones)
        try {
            enumerators.add(org.webrtc.Camera1Enumerator(false))
        } catch (e: Throwable) {
            Log.w(TAG, "Camera1Enumerator (no texture) failed: ${e.message}")
        }

        for (enumerator in enumerators) {
            try {
                val deviceNames = enumerator.deviceNames ?: continue
                // First pass: match exact facing (front or back)
                for (name in deviceNames) {
                    if ((isFront && enumerator.isFrontFacing(name)) || (!isFront && enumerator.isBackFacing(name))) {
                        val capturer = enumerator.createCapturer(name, cameraEventsHandler)
                        if (capturer != null) {
                            Log.d(TAG, "Successfully created capturer for $name using ${enumerator.javaClass.simpleName}")
                            return capturer
                        }
                    }
                }
                // Second pass: pick any available camera on the device
                for (name in deviceNames) {
                    val capturer = enumerator.createCapturer(name, cameraEventsHandler)
                    if (capturer != null) {
                        Log.d(TAG, "Successfully created fallback capturer for $name using ${enumerator.javaClass.simpleName}")
                        return capturer
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Enumerator failed: ${enumerator.javaClass.simpleName}", e)
            }
        }
        Log.e(TAG, "No working camera capturer found on device!")
        return null
    }

    fun switchCamera(isFront: Boolean) {
        val capturer = videoCapturer as? CameraVideoCapturer
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                Log.d(TAG, "Switched camera lens to front=$isFrontCamera")
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Log.e(TAG, "Error switching camera: $errorDescription")
            }
        })
    }

    private fun preferCodec(sdp: String, codec: String): String {
        try {
            val lines = sdp.split("\r\n").toMutableList()
            val mLineIndex = lines.indexOfFirst { it.startsWith("m=video") }
            if (mLineIndex == -1) return sdp

            val codecRtpMap = lines.firstOrNull { it.startsWith("a=rtpmap:") && it.contains(codec, ignoreCase = true) }
                ?: return sdp

            val payloadType = codecRtpMap.substringAfter("a=rtpmap:").substringBefore(" ").trim()
            val mLine = lines[mLineIndex]
            val parts = mLine.split(" ").toMutableList()
            if (parts.size > 3) {
                val header = parts.take(3)
                val payloads = parts.drop(3).toMutableList()
                if (payloads.remove(payloadType)) {
                    payloads.add(0, payloadType)
                    lines[mLineIndex] = (header + payloads).joinToString(" ")
                }
            }
            return lines.joinToString("\r\n")
        } catch (e: Exception) {
            Log.w(TAG, "Failed prioritizing codec: ${e.message}")
            return sdp
        }
    }

    private fun createAndSendOffer(roomId: String) {
        if (isCreatingOffer) return
        isCreatingOffer = true

        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                val preferredSdp = preferCodec(sessionDescription.description, "VP8")
                val modifiedDesc = SessionDescription(sessionDescription.type, preferredSdp)
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        isCreatingOffer = false
                        Log.d(TAG, "SetLocalDescription success (Offer)")
                        _connectionState.value = WebRtcConnectionState.EXCHANGING_SDP
                        _statusText.value = "Offer sent. Waiting for Viewer..."

                        val msg = SignalingMessage(
                            type = "OFFER",
                            senderId = "CAMERA",
                            targetRoom = roomId.ifBlank { currentRoomId },
                            sdp = preferredSdp,
                            sdpType = sessionDescription.type.canonicalForm()
                        )
                        signalingClient?.sendMessage(msg)
                    }

                    override fun onCreateFailure(p0: String?) {
                        isCreatingOffer = false
                        isNegotiating = false
                    }
                    override fun onSetFailure(p0: String?) {
                        isCreatingOffer = false
                        isNegotiating = false
                        Log.e(TAG, "SetLocalDescription failed: $p0")
                    }
                }, modifiedDesc)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                isCreatingOffer = false
                isNegotiating = false
                Log.e(TAG, "CreateOffer failed: $error")
            }
            override fun onSetFailure(p0: String?) {
                isCreatingOffer = false
                isNegotiating = false
            }
        }, sdpConstraints)
    }

    private fun resetPeerConnectionForFreshOffer(scope: CoroutineScope, roomId: String) {
        if (isNegotiating) {
            Log.d(TAG, "Negotiation already in progress, ignoring duplicate ROOM_JOINED")
            return
        }

        isNegotiating = true
        _connectionState.value = WebRtcConnectionState.EXCHANGING_SDP
        _statusText.value = "Viewer connecting... Preparing camera"

        // Watchdog: If negotiation does not complete in 12s, release lock and revert to waiting
        scope.launch(Dispatchers.IO) {
            delay(12000)
            if (_connectionState.value != WebRtcConnectionState.CONNECTED) {
                Log.d(TAG, "Negotiation timed out after 12s, resetting negotiation state")
                isNegotiating = false
                isCreatingOffer = false
                if (_connectionState.value != WebRtcConnectionState.CONNECTED) {
                    _connectionState.value = WebRtcConnectionState.WAITING_PEER
                    _statusText.value = "Camera Active - Waiting for viewer..."
                    onViewerDisconnected?.invoke()
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Resetting PeerConnection for fresh offer in room $roomId")
                isCreatingOffer = false
                isRemoteDescriptionSet = false
                pendingIceCandidates.clear()
                localIceCandidates.clear()

                // Ensure physical camera is running and capturing
                if (localVideoTrack == null || videoCapturer == null) {
                    startCameraHardware(currentIsFrontCamera)
                }
                
                // Wait up to 3 seconds for localVideoTrack to be ready (non-blocking delay)
                var attempts = 0
                while (localVideoTrack == null && attempts < 30) {
                    kotlinx.coroutines.delay(100)
                    attempts++
                }

                try {
                    dataChannel?.close()
                    dataChannel?.dispose()
                    dataChannel = null
                } catch (_: Exception) {}

                try {
                    peerConnection?.close()
                    peerConnection?.dispose()
                    peerConnection = null
                } catch (_: Exception) {}

                setupPeerConnection(scope)

                // Re-add live video track
                localVideoTrack?.let {
                    Log.d(TAG, "Adding localVideoTrack to peer connection for new viewer")
                    peerConnection?.addTrack(it, listOf("cctv_stream"))
                } ?: Log.e(TAG, "ERROR: localVideoTrack is null after startCameraHardware!")

                createAndSendOffer(roomId)
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting peer connection for new viewer", e)
                isNegotiating = false
                _connectionState.value = WebRtcConnectionState.WAITING_PEER
            }
        }
    }

    private fun handleSignalingMessage(scope: CoroutineScope, msg: SignalingMessage) {
        Log.d(TAG, "Signaling message received: ${msg.type} from ${msg.senderId}")
        when (msg.type) {
            "ROOM_JOINED", "START_STREAM", "VIEWER_CONNECT" -> {
                if (isCameraMode) {
                    if (isNegotiating || isCreatingOffer) {
                        Log.d(TAG, "Viewer joined room, but camera already negotiating. Skipping duplicate reset.")
                        return
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastOfferTimestamp < 1500) {
                        Log.d(TAG, "Viewer joined room, but throttled (< 1.5s). Skipping duplicate reset.")
                        return
                    }
                    lastOfferTimestamp = now
                    Log.d(TAG, "Viewer joined room, preparing fresh offer with video track")
                    resetPeerConnectionForFreshOffer(scope, msg.targetRoom.ifBlank { currentRoomId })
                }
            }
            "ROOM_LEFT", "LEAVE", "VIEWER_DISCONNECT", "STOP_STREAM" -> {
                if (isCameraMode) {
                    Log.d(TAG, "Viewer explicitly left room, immediately stopping camera hardware & mic")
                    stopCameraHardware()
                    onViewerDisconnected?.invoke()
                    _connectionState.value = WebRtcConnectionState.WAITING_PEER
                    _statusText.value = "💤 Standby (Camera & Mic Off) - Waiting for viewer..."
                }
            }
            "OFFER" -> {
                if (!isCameraMode && msg.sdp != null) {
                    // Do not reprocess offer if we are already actively connected
                    if (_connectionState.value == WebRtcConnectionState.CONNECTED) {
                        Log.d(TAG, "Already connected, skipping duplicate OFFER")
                        return
                    }

                    _connectionState.value = WebRtcConnectionState.EXCHANGING_SDP
                    _statusText.value = "Received Camera stream. Connecting..."

                    val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, msg.sdp)
                    peerConnection?.setRemoteDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            isRemoteDescriptionSet = true
                            drainPendingIceCandidates()
                            createAndSendAnswer()
                            
                            // Check if video transceiver already has track available
                            try {
                                peerConnection?.transceivers?.forEach { transceiver ->
                                    val track = transceiver.receiver.track()
                                    if (track is VideoTrack) {
                                        Log.d(TAG, "Found VideoTrack in transceiver after setRemoteDescription")
                                        track.setEnabled(true)
                                        _remoteVideoTrack.value = track
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error checking transceivers: ${e.message}")
                            }
                        }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(err: String?) {
                            Log.e(TAG, "SetRemoteDescription OFFER failed: $err")
                        }
                    }, remoteSdp)
                }
            }
            "ANSWER" -> {
                if (isCameraMode && msg.sdp != null) {
                    _connectionState.value = WebRtcConnectionState.CONNECTING_P2P
                    _statusText.value = "Connecting to Viewer phone..."

                    val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, msg.sdp)
                    peerConnection?.setRemoteDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.d(TAG, "SetRemoteDescription ANSWER success")
                            isRemoteDescriptionSet = true
                            drainPendingIceCandidates()
                        }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(err: String?) {
                            Log.e(TAG, "SetRemoteDescription ANSWER failed: $err")
                        }
                    }, remoteSdp)
                }
            }
            "ICE_CANDIDATE" -> {
                if (msg.candidate != null && msg.sdpMid != null && msg.sdpMLineIndex != null) {
                    val iceCandidate = IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.candidate)
                    if (isRemoteDescriptionSet) {
                        try {
                            peerConnection?.addIceCandidate(iceCandidate)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error adding ICE candidate directly", e)
                        }
                    } else {
                        pendingIceCandidates.add(iceCandidate)
                    }
                }
            }
            "COMMAND" -> {
                msg.command?.let { cmd ->
                    if (isCameraMode && (cmd == "VIEWER_DISCONNECT" || cmd == "STOP_STREAM")) {
                        Log.d(TAG, "Received VIEWER_DISCONNECT command, stopping camera hardware")
                        executor.submit { stopCameraHardware() }
                    } else if (isCameraMode && cmd.startsWith("SET_SPEAKERPHONE:")) {
                        val isOn = cmd.substringAfter("SET_SPEAKERPHONE:").trim() == "1"
                        setSpeakerphoneEnabled(isOn)
                    }
                    onCommandReceived?.invoke(cmd)
                }
            }
        }
    }

    private fun drainPendingIceCandidates() {
        synchronized(pendingIceCandidates) {
            for (cand in pendingIceCandidates) {
                try {
                    peerConnection?.addIceCandidate(cand)
                } catch (e: Exception) {
                    Log.w(TAG, "Error adding pending ICE candidate", e)
                }
            }
            pendingIceCandidates.clear()
        }
    }

    private fun createAndSendAnswer() {
        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                val preferredSdp = preferCodec(sessionDescription.description, "VP8")
                val modifiedDesc = SessionDescription(sessionDescription.type, preferredSdp)
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "SetLocalDescription success (Answer)")
                        val msg = SignalingMessage(
                            type = "ANSWER",
                            senderId = "VIEWER",
                            targetRoom = currentRoomId,
                            sdp = preferredSdp,
                            sdpType = sessionDescription.type.canonicalForm()
                        )
                        signalingClient?.sendMessage(msg)
                    }

                    override fun onCreateFailure(p0: String?) {
                        isNegotiating = false
                    }
                    override fun onSetFailure(err: String?) {
                        Log.e(TAG, "SetLocalDescription Answer failed: $err")
                        isNegotiating = false
                    }
                }, modifiedDesc)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                Log.e(TAG, "CreateAnswer failed: $err")
                isNegotiating = false
            }
            override fun onSetFailure(p0: String?) {
                isNegotiating = false
            }
        }, sdpConstraints)
    }

    fun sendCommand(cmd: String): Boolean {
        dataChannel?.let { dc ->
            if (dc.state() == DataChannel.State.OPEN) {
                val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(cmd.toByteArray(Charsets.UTF_8)), false)
                dc.send(buffer)
                return true
            }
        }
        signalingClient?.sendMessage(
            SignalingMessage(
                type = "COMMAND",
                senderId = if (isCameraMode) "CAMERA" else "VIEWER",
                targetRoom = currentRoomId,
                command = cmd
            )
        )
        return true
    }

    fun release() {
        try {
            signalingClient?.stop()
            signalingClient = null
        } catch (_: Exception) {}

        executor.submit {
            try {
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                videoCapturer = null

                surfaceTextureHelper?.dispose()
                surfaceTextureHelper = null

                localVideoTrack?.dispose()
                localVideoTrack = null

                localAudioTrack?.dispose()
                localAudioTrack = null

                localVideoSource?.dispose()
                localVideoSource = null

                localAudioSource?.dispose()
                localAudioSource = null

                dataChannel?.close()
                dataChannel?.dispose()
                dataChannel = null

                peerConnection?.close()
                peerConnection?.dispose()
                peerConnection = null

                peerConnectionFactory?.dispose()
                peerConnectionFactory = null
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing WebRTC resources", e)
            }
        }
    }

    fun notifyViewerDisconnect() {
        try {
            signalingClient?.sendMessage(
                SignalingMessage(
                    type = "VIEWER_DISCONNECT",
                    senderId = "VIEWER",
                    targetRoom = currentRoomId
                )
            )
        } catch (_: Exception) {}
        try {
            sendCommand("VIEWER_DISCONNECT")
        } catch (_: Exception) {}
    }

    @Volatile
    var isCallPaused = false
        private set

    fun pauseForPhoneCall() {
        if (!isCameraMode) return
        Log.i(TAG, "Phone call / VoIP detected! Temporarily pausing camera capture.")
        isCallPaused = true
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping capturer for call: ${e.message}")
        }
        try {
            localVideoTrack?.setEnabled(false)
        } catch (_: Exception) {}
        try {
            sendCommand("PHONE_CALL_ACTIVE")
        } catch (_: Exception) {}
        _statusText.value = "📞 कॉल चालू है (कैमरा व माइक रोके गए)"
    }

    fun resumeAfterPhoneCall(scope: CoroutineScope) {
        if (!isCameraMode || !isCallPaused) return
        Log.i(TAG, "Phone call / VoIP finished. Resuming camera.")
        isCallPaused = false
        try {
            localVideoTrack?.setEnabled(true)
            videoCapturer?.startCapture(640, 480, 30)
            sendCommand("PHONE_CALL_ENDED")
        } catch (e: Exception) {
            Log.w(TAG, "Error resuming capturer: ${e.message}")
            startCameraHardware(currentIsFrontCamera)
        }
        _statusText.value = "● Live Stream Connected"
    }
}
