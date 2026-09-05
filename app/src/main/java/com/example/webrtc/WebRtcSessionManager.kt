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
    }
    
    private val TAG = "WebRtcSessionManager"

    // Root EGL Base for OpenGL hardware video textures
    val rootEglBase: EglBase = EglBase.create()
    val eglBase: EglBase get() = rootEglBase

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
        // Google Global Anycast STUN
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
        // Cloudflare Worldwide Anycast STUN
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        // Global STUN Nodes
        PeerConnection.IceServer.builder("stun:global.stun.twilio.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.services.mozilla.com").createIceServer(),
        // Global TURN Relays (UDP + TCP on Port 80 & 443)
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80?transport=tcp")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        // TURNS over TLS on 443 (100% Unblockable across all world firewalls)
        PeerConnection.IceServer.builder("turns:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        // Secondary Worldwide Relay Node
        PeerConnection.IceServer.builder("turn:relay.metered.ca:80")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:relay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turns:relay.metered.ca:443?transport=tcp")
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

    init {
        initializePeerConnectionFactory()
        configureAudioManager()
    }

    fun setSpeakerphoneEnabled(isEnabled: Boolean) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.let {
                it.mode = AudioManager.MODE_IN_COMMUNICATION
                it.isSpeakerphoneOn = isEnabled
                it.setSpeakerphoneOn(isEnabled)
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
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = true
                am.setSpeakerphoneOn(true)
                try {
                    val maxVoice = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoice, 0)
                } catch (_: Exception) {}
                try {
                    val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure AudioManager: ${e.message}")
        }
    }

    private fun initializePeerConnectionFactory() {
        synchronized(WebRtcSessionManager::class.java) {
            if (!isWebRtcInitialized) {
                val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(options)
                isWebRtcInitialized = true
            }
        }

        val encoderFactory = DefaultVideoEncoderFactory(
            rootEglBase.eglBaseContext,
            false, // enableIntelVp8Encoder
            false  // enableH264HighProfile
        )
        val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

        val isAecSupported = JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
        val isNsSupported = JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()

        val audioAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setAudioAttributes(audioAttributes)
            .setUseHardwareAcousticEchoCanceler(isAecSupported)
            .setUseHardwareNoiseSuppressor(isNsSupported)
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
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
            _connectionState.value = WebRtcConnectionState.CONNECTING_P2P
            _statusText.value = "Starting camera and broadcasting..."
            startCameraHardware(isFrontCamera)
            scope.launch(Dispatchers.IO) {
                delay(600)
                localVideoTrack?.let {
                    peerConnection?.addTrack(it, listOf("cctv_stream"))
                }
                localAudioTrack?.let {
                    peerConnection?.addTrack(it, listOf("cctv_stream"))
                }
                createAndSendOffer(roomId)
            }
        } else {
            setupViewerMediaTracks()
            _connectionState.value = WebRtcConnectionState.WAITING_PEER
            _statusText.value = "Connecting to Camera..."

            scope.launch(Dispatchers.IO) {
                delay(300)
                signalingClient?.sendMessage(
                    SignalingMessage(
                        type = "ROOM_JOINED",
                        senderId = "VIEWER",
                        targetRoom = roomId
                    )
                )
            }
        }

        // Background watchdog: only retry ROOM_JOINED if waiting for peer
        scope.launch(Dispatchers.IO) {
            var retryCount = 0
            while (scope.isActive) {
                delay(1500)
                val state = _connectionState.value
                if (state == WebRtcConnectionState.WAITING_PEER || state == WebRtcConnectionState.FAILED) {
                    retryCount++
                    if (!isCameraMode) {
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
                } else if (state == WebRtcConnectionState.CONNECTED || state == WebRtcConnectionState.EXCHANGING_SDP) {
                    // Reset retry count once connection establishes or exchanges sdp
                    retryCount = 0
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
                            _connectionState.value = WebRtcConnectionState.CONNECTED
                            _statusText.value = "● Live Stream Connected"
                            configureAudioManager()
                            if (isCameraMode) {
                                onViewerConnected?.invoke()
                            }
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            _connectionState.value = WebRtcConnectionState.DISCONNECTED
                            _statusText.value = "Viewer disconnected. Re-establishing..."
                            if (isCameraMode) {
                                scope.launch(Dispatchers.IO) {
                                    delay(4000)
                                    if (_connectionState.value == WebRtcConnectionState.DISCONNECTED) {
                                        Log.d(TAG, "Disconnected for 4s, stopping camera hardware for standby")
                                        stopCameraHardware()
                                    }
                                }
                            }
                        }
                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.CLOSED -> {
                            _connectionState.value = WebRtcConnectionState.FAILED
                            _statusText.value = "Connection closed"
                            if (isCameraMode) {
                                scope.launch(Dispatchers.IO) {
                                    delay(2000)
                                    if (_connectionState.value != WebRtcConnectionState.CONNECTED) {
                                        Log.d(TAG, "Connection failed/closed, returning to standby")
                                        stopCameraHardware()
                                    }
                                }
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
                    _remoteVideoTrack.value = track
                } else if (track is AudioTrack) {
                    Log.d(TAG, "onTrack: Received remote AudioTrack")
                    try {
                        track.setEnabled(true)
                        track.setVolume(1.0)
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
            val dcInit = DataChannel.Init().apply { ordered = true }
            dataChannel = peerConnection?.createDataChannel("cctv_commands", dcInit)
            dataChannel?.let { setupDataChannelListeners(it) }
        }
    }

    private fun setupDataChannelListeners(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Log.d(TAG, "DataChannel State: ${dc.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                val cmd = String(data, Charsets.UTF_8)
                Log.d(TAG, "DataChannel message received: $cmd")
                onCommandReceived?.invoke(cmd)
            }
        })
    }

    private fun setupViewerMediaTracks() {
        val factory = peerConnectionFactory ?: return
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        localAudioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("VIEWER_TALK_TRACK", localAudioSource)
        localAudioTrack?.setEnabled(false)
        peerConnection?.addTrack(localAudioTrack, listOf("viewer_audio"))
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
            try {
                androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (_: Exception) {}

            if (surfaceTextureHelper == null) {
                surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcCaptureThread", rootEglBase.eglBaseContext)
            }
            if (localVideoSource == null) {
                localVideoSource = factory.createVideoSource(false)
            }

            if (videoCapturer == null) {
                videoCapturer = createCameraCapturer(isFrontCamera)
                videoCapturer?.let { capturer ->
                    capturer.initialize(surfaceTextureHelper, context, localVideoSource?.capturerObserver)
                    capturer.startCapture(1280, 720, 30)
                }
            }

            if (localVideoTrack == null) {
                localVideoTrack = factory.createVideoTrack("CCTV_VIDEO_TRACK", localVideoSource)
                localVideoTrack?.setEnabled(true)
            }

            if (localAudioSource == null) {
                val audioConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
                }
                localAudioSource = factory.createAudioSource(audioConstraints)
            }

            if (localAudioTrack == null) {
                localAudioTrack = factory.createAudioTrack("CCTV_AUDIO_TRACK", localAudioSource)
                localAudioTrack?.setEnabled(true)
            }

            isCameraHardwareActive = true
            Log.d(TAG, "Camera hardware and microphone opened successfully")
        } catch (e: Exception) {
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
                surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcScreenCaptureThread", rootEglBase.eglBaseContext)
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

            if (localAudioTrack == null) {
                val audioConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
                }
                localAudioSource = factory.createAudioSource(audioConstraints)
                localAudioTrack = factory.createAudioTrack("CCTV_AUDIO_TRACK", localAudioSource)
                localAudioTrack?.setEnabled(true)
                peerConnection?.addTrack(localAudioTrack, listOf("cctv_stream"))
            }
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
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        for (name in deviceNames) {
            if (isFront && enumerator.isFrontFacing(name)) {
                return enumerator.createCapturer(name, null)
            }
            if (!isFront && enumerator.isBackFacing(name)) {
                return enumerator.createCapturer(name, null)
            }
        }

        for (name in deviceNames) {
            val capturer = enumerator.createCapturer(name, null)
            if (capturer != null) return capturer
        }
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

    private fun createAndSendOffer(roomId: String) {
        if (isCreatingOffer) return
        isCreatingOffer = true

        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        isCreatingOffer = false
                        Log.d(TAG, "SetLocalDescription success (Offer)")
                        _connectionState.value = WebRtcConnectionState.WAITING_PEER
                        _statusText.value = "Offer ready. Waiting for Viewer..."

                        val msg = SignalingMessage(
                            type = "OFFER",
                            senderId = "CAMERA",
                            targetRoom = roomId.ifBlank { currentRoomId },
                            sdp = sessionDescription.description,
                            sdpType = sessionDescription.type.canonicalForm()
                        )
                        signalingClient?.sendMessage(msg)

                        // Send local ICE candidates
                        synchronized(localIceCandidates) {
                            for (cand in localIceCandidates) {
                                signalingClient?.sendMessage(
                                    SignalingMessage(
                                        type = "ICE_CANDIDATE",
                                        senderId = "CAMERA",
                                        targetRoom = roomId.ifBlank { currentRoomId },
                                        sdpMid = cand.sdpMid,
                                        sdpMLineIndex = cand.sdpMLineIndex,
                                        candidate = cand.sdp
                                    )
                                )
                            }
                        }
                    }

                    override fun onCreateFailure(p0: String?) { isCreatingOffer = false }
                    override fun onSetFailure(p0: String?) {
                        isCreatingOffer = false
                        Log.e(TAG, "SetLocalDescription failed: $p0")
                    }
                }, sessionDescription)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                isCreatingOffer = false
                Log.e(TAG, "CreateOffer failed: $error")
            }
            override fun onSetFailure(p0: String?) { isCreatingOffer = false }
        }, sdpConstraints)
    }

    private fun resetPeerConnectionForFreshOffer(scope: CoroutineScope, roomId: String) {
        if (isCameraHardwareActive && peerConnection != null && _connectionState.value == WebRtcConnectionState.CONNECTED) {
            Log.d(TAG, "Already connected and camera active, ignoring duplicate ROOM_JOINED")
            return
        }
        executor.submit {
            try {
                Log.d(TAG, "Resetting PeerConnection for new/reconnecting viewer in room $roomId")
                isCreatingOffer = false
                isRemoteDescriptionSet = false
                pendingIceCandidates.clear()
                localIceCandidates.clear()

                // Start physical camera and mic on-demand when viewer connects
                startCameraHardware(currentIsFrontCamera)

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

                // Re-add live video and audio tracks
                localVideoTrack?.let {
                    peerConnection?.addTrack(it, listOf("cctv_stream"))
                }
                localAudioTrack?.let {
                    peerConnection?.addTrack(it, listOf("cctv_stream"))
                }

                createAndSendOffer(roomId)
                onViewerConnected?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting peer connection for new viewer", e)
            }
        }
    }

    private fun handleSignalingMessage(scope: CoroutineScope, msg: SignalingMessage) {
        Log.d(TAG, "Signaling message received: ${msg.type} from ${msg.senderId}")
        when (msg.type) {
            "ROOM_JOINED", "START_STREAM", "VIEWER_CONNECT" -> {
                if (isCameraMode) {
                    Log.d(TAG, "Viewer joined room, activating camera & sending fresh offer")
                    resetPeerConnectionForFreshOffer(scope, msg.targetRoom.ifBlank { currentRoomId })
                }
            }
            "ROOM_LEFT", "LEAVE", "VIEWER_DISCONNECT", "STOP_STREAM" -> {
                if (isCameraMode) {
                    Log.d(TAG, "Viewer left room, releasing camera hardware and entering standby")
                    executor.submit { stopCameraHardware() }
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
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "SetLocalDescription success (Answer)")
                        val msg = SignalingMessage(
                            type = "ANSWER",
                            senderId = "VIEWER",
                            targetRoom = currentRoomId,
                            sdp = sessionDescription.description,
                            sdpType = sessionDescription.type.canonicalForm()
                        )
                        signalingClient?.sendMessage(msg)

                        synchronized(localIceCandidates) {
                            for (cand in localIceCandidates) {
                                signalingClient?.sendMessage(
                                    SignalingMessage(
                                        type = "ICE_CANDIDATE",
                                        senderId = "VIEWER",
                                        targetRoom = currentRoomId,
                                        sdpMid = cand.sdpMid,
                                        sdpMLineIndex = cand.sdpMLineIndex,
                                        candidate = cand.sdp
                                    )
                                )
                            }
                        }
                    }

                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(err: String?) {
                        Log.e(TAG, "SetLocalDescription Answer failed: $err")
                    }
                }, sessionDescription)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                Log.e(TAG, "CreateAnswer failed: $err")
            }
            override fun onSetFailure(p0: String?) {}
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
        executor.submit {
            try {
                signalingClient?.stop()
                signalingClient = null

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

                rootEglBase.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing WebRTC resources", e)
            }
        }
    }
}
