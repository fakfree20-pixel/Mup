package com.example.webrtc

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.Collections
import java.util.concurrent.TimeUnit

class WebRtcSignalingClient(
    private val clientRole: String, // "CAMERA" or "VIEWER"
    private val roomId: String,
    private val onMessageReceived: (SignalingMessage) -> Unit,
    private val onStateChanged: ((String) -> Unit)? = null
) {
    private val TAG = "WebRtcSignaling"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // For WebSocket
        .writeTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val pollingClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var isRunning = false
    private var pollingJob: Job? = null
    private val processedMessageHashes = Collections.synchronizedSet(mutableSetOf<String>())

    // Room topics:
    // Camera listens on: cctv_sig_${cleanRoom}_viewer (messages sent by viewer)
    // Camera sends to:   cctv_sig_${cleanRoom}_camera
    // Viewer listens on: cctv_sig_${cleanRoom}_camera (messages sent by camera)
    // Viewer sends to:   cctv_sig_${cleanRoom}_viewer
    private val cleanRoom = roomId.filter { it.isLetterOrDigit() }.lowercase()
    private val listenTopic = if (clientRole == "CAMERA") "cctv_sig_${cleanRoom}_viewer" else "cctv_sig_${cleanRoom}_camera"
    private val sendTopic = if (clientRole == "CAMERA") "cctv_sig_${cleanRoom}_camera" else "cctv_sig_${cleanRoom}_viewer"

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true
        onStateChanged?.invoke("Connecting to 4G/5G room $cleanRoom...")

        connectWebSocket(scope)
        startHttpPollingFallback(scope)

        // Heartbeat / ping job to keep signaling channel warm
        scope.launch(Dispatchers.IO) {
            while (isRunning && isActive) {
                delay(12000)
                if (isRunning) {
                    sendMessage(
                        SignalingMessage(
                            type = "HEARTBEAT",
                            senderId = clientRole,
                            targetRoom = cleanRoom
                        )
                    )
                }
            }
        }
    }

    private fun connectWebSocket(scope: CoroutineScope) {
        val wsUrl = "wss://ntfy.sh/$listenTopic/ws?since=all"
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Signaling WebSocket opened for topic: $listenTopic")
                onStateChanged?.invoke("Signaling channel connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val event = root.optString("event")
                    if (event == "message" || event.isEmpty()) {
                        val messageBody = root.optString("message")
                        if (messageBody.isNotBlank()) {
                            parseAndDispatch(messageBody)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing WS message: $text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Signaling WS failed: ${t.message}. Retrying...")
                if (isRunning) {
                    scope.launch(Dispatchers.IO) {
                        delay(3000)
                        if (isRunning) {
                            connectWebSocket(scope)
                        }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Signaling WS closed ($code): $reason")
            }
        })
    }

    private fun startHttpPollingFallback(scope: CoroutineScope) {
        pollingJob = scope.launch(Dispatchers.IO) {
            var since = "all"
            while (isActive && isRunning) {
                try {
                    val pollUrl = "https://ntfy.sh/$listenTopic/json?poll=1&since=$since"
                    val request = Request.Builder().url(pollUrl).build()
                    val response = pollingClient.newCall(request).execute()
                    if (response.isSuccessful && response.body != null) {
                        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val l = line?.trim() ?: continue
                            if (l.isNotBlank()) {
                                try {
                                    val obj = JSONObject(l)
                                    val event = obj.optString("event")
                                    if (event == "message" || event.isEmpty()) {
                                        val msgBody = obj.optString("message")
                                        if (msgBody.isNotBlank()) {
                                            parseAndDispatch(msgBody)
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                        since = "30s"
                    }
                    response.close()
                } catch (_: Exception) {
                    // Ignore network hiccups, wait before polling again
                }
                delay(2000)
            }
        }
    }

    private fun parseAndDispatch(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val type = json.optString("type")
            val sender = json.optString("senderId")

            // Don't process self messages
            if (sender == clientRole) return

            // Deduplication by signature
            val sig = "$type:$sender:${json.optString("candidate").take(25)}:${json.optString("sdp").take(25)}"
            if (!processedMessageHashes.add(sig)) {
                return // Already processed
            }

            val msg = SignalingMessage(
                type = type,
                senderId = sender,
                targetRoom = json.optString("targetRoom"),
                sdp = if (json.has("sdp")) json.getString("sdp") else null,
                sdpType = if (json.has("sdpType")) json.getString("sdpType") else null,
                sdpMid = if (json.has("sdpMid")) json.getString("sdpMid") else null,
                sdpMLineIndex = if (json.has("sdpMLineIndex")) json.getInt("sdpMLineIndex") else null,
                candidate = if (json.has("candidate")) json.getString("candidate") else null,
                command = if (json.has("command")) json.getString("command") else null,
                timestamp = json.optLong("timestamp", System.currentTimeMillis())
            )
            onMessageReceived(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incoming signaling JSON", e)
        }
    }

    fun sendMessage(msg: SignalingMessage) {
        if (!isRunning) return
        try {
            val json = JSONObject().apply {
                put("type", msg.type)
                put("senderId", msg.senderId)
                put("targetRoom", msg.targetRoom)
                msg.sdp?.let { put("sdp", it) }
                msg.sdpType?.let { put("sdpType", it) }
                msg.sdpMid?.let { put("sdpMid", it) }
                msg.sdpMLineIndex?.let { put("sdpMLineIndex", it) }
                msg.candidate?.let { put("candidate", it) }
                msg.command?.let { put("command", it) }
                put("timestamp", msg.timestamp)
            }

            val postUrl = "https://ntfy.sh/$sendTopic"
            val requestBody = json.toString().toRequestBody("text/plain".toMediaType())
            val request = Request.Builder()
                .url(postUrl)
                .post(requestBody)
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "Failed to POST signaling message: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error packaging signaling message", e)
        }
    }

    fun stop() {
        isRunning = false
        pollingJob?.cancel()
        pollingJob = null
        try {
            webSocket?.close(1000, "Normal closure")
        } catch (_: Exception) {}
        webSocket = null
        processedMessageHashes.clear()
    }
}
