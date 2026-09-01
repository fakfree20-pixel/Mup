package com.example.webrtc

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.Collections
import java.util.UUID

class WebRtcSignalingClient(
    private val clientRole: String, // "CAMERA" or "VIEWER"
    private val roomId: String,
    private val onMessageReceived: (SignalingMessage) -> Unit,
    private val onStateChanged: ((String) -> Unit)? = null
) {
    private val TAG = "WebRtcSignaling"
    
    private var mqttClient: MqttClient? = null
    private var isRunning = false
    private var connectionJob: Job? = null
    
    private val processedIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val processedIdsList = Collections.synchronizedList(mutableListOf<String>())

    private fun isDuplicate(id: String): Boolean {
        synchronized(processedIds) {
            if (processedIds.contains(id)) return true
            processedIds.add(id)
            processedIdsList.add(id)
            if (processedIdsList.size > 200) {
                val eldest = processedIdsList.removeAt(0)
                processedIds.remove(eldest)
            }
            return false
        }
    }

    private val cleanRoom = roomId.filter { it.isLetterOrDigit() }.lowercase()
    private val listenTopic = if (clientRole == "CAMERA") "cctv_sig_${cleanRoom}_viewer" else "cctv_sig_${cleanRoom}_camera"
    private val sendTopic = if (clientRole == "CAMERA") "cctv_sig_${cleanRoom}_camera" else "cctv_sig_${cleanRoom}_viewer"
    
    fun start(scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true
        onStateChanged?.invoke("Connecting to Secure Relay...")
        
        connectionJob = scope.launch(Dispatchers.IO) {
            while (isRunning && isActive) {
                try {
                    if (mqttClient == null || !mqttClient!!.isConnected) {
                        connectMqtt()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "MQTT connection error", e)
                }
                delay(3000)
            }
        }
    }
    
    private fun connectMqtt() {
        try {
            val broker = "ssl://broker.emqx.io:8883"
            val clientId = "cctv_${clientRole}_${UUID.randomUUID().toString().take(8)}"
            
            mqttClient = MqttClient(broker, clientId, MemoryPersistence())
            
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 20
                isAutomaticReconnect = true
            }
            
            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT Connection lost")
                    if (isRunning) onStateChanged?.invoke("Relay disconnected, reconnecting...")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    message?.let {
                        val payload = String(it.payload)
                        parseAndDispatch(payload)
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            
            mqttClient?.connect(options)
            mqttClient?.subscribe(listenTopic, 1)
            
            Log.d(TAG, "MQTT Connected and subscribed to $listenTopic")
            onStateChanged?.invoke("Connected to Secure Relay")
            
            // Send ROOM_JOINED automatically upon connect
            sendMessage(SignalingMessage(type = "ROOM_JOINED", senderId = clientRole, targetRoom = cleanRoom))
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect MQTT", e)
            throw e
        }
    }

    private fun parseAndDispatch(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val id = json.optString("id")
            val type = json.optString("type")
            val sender = json.optString("senderId")
            
            if (sender == clientRole) return
            
            if (id.isNotBlank() && isDuplicate(id)) return
            
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
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                id = if (id.isNotBlank()) id else UUID.randomUUID().toString()
            )
            onMessageReceived(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incoming MQTT message", e)
        }
    }

    fun sendMessage(msg: SignalingMessage) {
        if (!isRunning) return
        try {
            val json = JSONObject().apply {
                put("id", msg.id)
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
            
            val payload = json.toString().toByteArray()
            if (mqttClient?.isConnected == true) {
                val mqttMsg = MqttMessage(payload).apply { qos = 1 }
                mqttClient?.publish(sendTopic, mqttMsg)
                Log.d(TAG, "Published ${msg.type} to $sendTopic (Size: ${payload.size} bytes)")
            } else {
                Log.w(TAG, "MQTT not connected, cannot send ${msg.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending MQTT message", e)
        }
    }

    fun stop() {
        isRunning = false
        connectionJob?.cancel()
        connectionJob = null
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (_: Exception) {}
        mqttClient = null
        processedIds.clear()
        processedIdsList.clear()
    }
}
