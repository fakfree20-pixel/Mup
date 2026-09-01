package com.example.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.*
import java.util.concurrent.Executors

class WebRtcSessionManager(
    private val context: Context,
    val isCameraMode: Boolean
) {
    private val TAG = "WebRtcSessionManager"

    // Root EGL Base for OpenGL hardware video textures
    val rootEglBase: EglBase = EglBase.create()
    val eglBase: EglBase get() = rootEglBase

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    // Media Tracks
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    // Remote Tracks (for Viewer)
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

    // STUN and TURN Relay Servers for inter-city, cellular 4G/5G and cross-NAT streaming
    private val iceServers = listOf(
        // Google Global STUN
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("stun:global.stun.twilio.com:3478?transport=udp").createIceServer(),
        // Free Global TURN Servers (for inter-city CGNAT & Wi-Fi router relay)
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
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
        PeerConnection.IceServer.builder("turns:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        // Twilio network traversal STUN over TCP (fallback for strict firewalls)
        PeerConnection.IceServer.builder("stun:global.stun.twilio.com:3478?transport=tcp").createIceServer()
    )

    private val pendingIceCandidates = java.util.Collections.synchronizedList(mutableListOf<IceCandidate>())
    private val localIceCandidates = java.util.Collections.synchronizedList(mutableListOf<IceCandidate>())
    @Volatile
    private var isRemoteDescriptionSet = false

    init {
        initializePeerConnectionFactory()
    }

    private fun initializePeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            rootEglBase.eglBaseContext,
            true, // enableIntelVp8Encoder
            true  // enableH264HighProfile
        )
        val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
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
        _statusText.value = "Connecting to Mobile Data Room $roomId..."

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

        setupPeerConnection(scope)
        if (!isCameraMode) {
            setupViewerMediaTracks()
        }

        if (isCameraMode) {
            _statusText.value = "Camera is online & waiting for Viewer..."
        } else {
            _connectionState.value = WebRtcConnectionState.WAITING_PEER
            _statusText.value = "Waiting for Camera video stream on 4G/5G..."

            // Send periodic ROOM_JOINED pings to camera across cities until connected
            scope.launch(Dispatchers.IO) {
                while (scope.isActive) {
                    if (_connectionState.value != WebRtcConnectionState.CONNECTED) {
                        signalingClient?.sendMessage(
                            SignalingMessage(
                                type = "ROOM_JOINED",
                                senderId = "VIEWER",
                                targetRoom = roomId
                            )
                        )
                    }
                    delay(2000)
                }
            }
        }
    }

    private fun setupPeerConnection(scope: CoroutineScope) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
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
                            _statusText.value = "● WebRTC P2P Live (Mobile Data)"
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            _connectionState.value = WebRtcConnectionState.DISCONNECTED
                            _statusText.value = "Connection lost. Reconnecting..."
                            if (isCameraMode) stopCameraMediaTracks()
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            _connectionState.value = WebRtcConnectionState.FAILED
                            _statusText.value = "P2P connection failed. Retrying..."
                            if (isCameraMode) stopCameraMediaTracks()
                        }
                        PeerConnection.IceConnectionState.CHECKING -> {
                            _connectionState.value = WebRtcConnectionState.CONNECTING_P2P
                            _statusText.value = "Connecting via STUN (P2P NAT Traversal)..."
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
                        targetRoom = "",
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                        candidate = candidate.sdp
                    )
                    client.sendMessage(msg)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream) {
                Log.d(TAG, "onAddStream with ${stream.videoTracks.size} video tracks")
                if (stream.videoTracks.isNotEmpty()) {
                    val track = stream.videoTracks.first()
                    _remoteVideoTrack.value = track
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "onTrack: Received remote VideoTrack")
                    _remoteVideoTrack.value = track
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
                ordered = true
            }
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
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        localAudioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("VIEWER_TALK_TRACK", localAudioSource)
        // Disabled initially until user holds mic button
        localAudioTrack?.setEnabled(false)
        peerConnection?.addTrack(localAudioTrack, listOf("viewer_audio"))
    }

    private fun stopCameraMediaTracks() {
        try {
            peerConnection?.senders?.forEach { sender ->
                peerConnection?.removeTrack(sender)
            }
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null
            
            localVideoTrack?.dispose()
            localVideoTrack = null
            
            localAudioTrack?.dispose()
            localAudioTrack = null
            
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null
            
            localVideoSource?.dispose()
            localVideoSource = null
            
            localAudioSource?.dispose()
            localAudioSource = null
            
            Log.d(TAG, "Camera media tracks released to save battery")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media tracks", e)
        }
    }

    private fun setupCameraMediaTracks(isFrontCamera: Boolean) {
        val factory = peerConnectionFactory ?: return

        // 1. Create Video Source and Capturer
        surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcCaptureThread", rootEglBase.eglBaseContext)
        localVideoSource = factory.createVideoSource(false)

        videoCapturer = createCameraCapturer(isFrontCamera)
        videoCapturer?.let { capturer ->
            capturer.initialize(surfaceTextureHelper, context, localVideoSource?.capturerObserver)
            // 720p 30fps for ultra-low latency & crystal clear video over mobile data
            capturer.startCapture(1280, 720, 30)
        }

        localVideoTrack = factory.createVideoTrack("CCTV_VIDEO_TRACK", localVideoSource)
        localVideoTrack?.setEnabled(true)

        // 2. Create Audio Source & Track
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
        }
        localAudioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("CCTV_AUDIO_TRACK", localAudioSource)
        localAudioTrack?.setEnabled(true)

        // Add tracks to PeerConnection
        peerConnection?.addTrack(localVideoTrack, listOf("cctv_stream"))
        peerConnection?.addTrack(localAudioTrack, listOf("cctv_stream"))
    }

    fun enableViewerTwoWayAudio(enable: Boolean) {
        if (isCameraMode) return
        localAudioTrack?.setEnabled(enable)
    }

    private fun createCameraCapturer(isFront: Boolean): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        // Try front or back as requested
        for (name in deviceNames) {
            if (isFront && enumerator.isFrontFacing(name)) {
                return enumerator.createCapturer(name, null)
            }
            if (!isFront && enumerator.isBackFacing(name)) {
                return enumerator.createCapturer(name, null)
            }
        }

        // Fallback to any available camera
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
        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")) // To hear viewer push-to-talk
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "SetLocalDescription success (Offer)")
                        _connectionState.value = WebRtcConnectionState.WAITING_PEER
                        _statusText.value = "Broadcasting offer. Ready for Viewer phone!"

                        val msg = SignalingMessage(
                            type = "OFFER",
                            senderId = "CAMERA",
                            targetRoom = roomId,
                            sdp = minifySdp(sessionDescription.description),
                            sdpType = sessionDescription.type.canonicalForm()
                        )
                        signalingClient?.sendMessage(msg)
                    }

                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {
                        Log.e(TAG, "SetLocalDescription failed: $p0")
                    }
                }, sessionDescription)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "CreateOffer failed: $error")
            }
            override fun onSetFailure(p0: String?) {}
        }, sdpConstraints)
    }

    private fun handleSignalingMessage(scope: CoroutineScope, msg: SignalingMessage) {
        Log.d(TAG, "Signaling message received: ${msg.type} from ${msg.senderId}")
        when (msg.type) {
            "ROOM_JOINED" -> {
                if (isCameraMode) {
                    if (localVideoTrack == null) {
                        // Start camera now that viewer is connected
                        setupCameraMediaTracks(currentIsFrontCamera)
                        createAndSendOffer(msg.targetRoom.ifBlank { currentRoomId })
                    }
                    val localDesc = peerConnection?.localDescription
                    if (localDesc != null && localDesc.type == SessionDescription.Type.OFFER) {
                        Log.d(TAG, "Re-broadcasting existing OFFER to joined Viewer")
                        val offerMsg = SignalingMessage(
                            type = "OFFER",
                            senderId = "CAMERA",
                            targetRoom = msg.targetRoom.ifBlank { "" },
                            sdp = minifySdp(localDesc.description),
                            sdpType = localDesc.type.canonicalForm()
                        )
                        signalingClient?.sendMessage(offerMsg)

                        // Re-send all generated ICE candidates
                        synchronized(localIceCandidates) {
                            for (cand in localIceCandidates) {
                                signalingClient?.sendMessage(
                                    SignalingMessage(
                                        type = "ICE_CANDIDATE",
                                        senderId = "CAMERA",
                                        targetRoom = "",
                                        sdpMid = cand.sdpMid,
                                        sdpMLineIndex = cand.sdpMLineIndex,
                                        candidate = cand.sdp
                                    )
                                )
                            }
                        }
                    } else {
                        createAndSendOffer(msg.targetRoom)
                    }
                }
            }
            "OFFER" -> {
                if (!isCameraMode && msg.sdp != null) {
                    _connectionState.value = WebRtcConnectionState.EXCHANGING_SDP
                    _statusText.value = "Received Camera Offer. Creating Answer..."

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
                    _statusText.value = "Connecting to Viewer over 4G/5G..."

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
                            targetRoom = "",
                            sdp = minifySdp(sessionDescription.description),
                            sdpType = sessionDescription.type.canonicalForm()
                        )
                        signalingClient?.sendMessage(msg)

                        // Also send all local ICE candidates gathered so far
                        synchronized(localIceCandidates) {
                            for (cand in localIceCandidates) {
                                signalingClient?.sendMessage(
                                    SignalingMessage(
                                        type = "ICE_CANDIDATE",
                                        senderId = "VIEWER",
                                        targetRoom = "",
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
        // Try fast DataChannel first
        dataChannel?.let { dc ->
            if (dc.state() == DataChannel.State.OPEN) {
                val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(cmd.toByteArray(Charsets.UTF_8)), false)
                dc.send(buffer)
                return true
            }
        }
        // Fallback: send via signaling channel
        signalingClient?.sendMessage(
            SignalingMessage(
                type = "COMMAND",
                senderId = if (isCameraMode) "CAMERA" else "VIEWER",
                targetRoom = "",
                command = cmd
            )
        )
        return true
    }

    fun release() {
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
    private fun minifySdp(sdp: String): String {
        return sdp.lines().filter { !it.trim().startsWith("a=extmap:") }.joinToString("\r\n") + "\r\n"
    }
}
