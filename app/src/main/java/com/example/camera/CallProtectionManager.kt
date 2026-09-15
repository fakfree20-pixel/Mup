package com.example.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.*

/**
 * Monitors phone calls (cellular GSM) and VoIP calls (WhatsApp audio/video, IMO, Skype, Zoom)
 * to ensure 100% zero disturbance on the camera device.
 *
 * When a call is incoming, ringing, or active, this manager immediately pauses the camera sensor,
 * microphone recording, and speaker playback so WhatsApp, IMO, and phone calls operate
 * with exclusive access to hardware without any conflict, lag, or noise.
 * Once the call ends, streaming automatically resumes.
 */
class CallProtectionManager(
    private val context: Context,
    private val onCallStarted: () -> Unit,
    private val onCallEnded: () -> Unit
) {
    companion object {
        private const val TAG = "CallProtectionManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isCallInProgress = false
    private var isMonitoring = false
    private var monitorJob: Job? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Audio focus change listener: Android calls this when WhatsApp, IMO, or Phone requests audio focus
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(TAG, "AudioFocus changed: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "Audio focus lost (WhatsApp/IMO/Phone call active). Yielding mic & camera.")
                handleCallStarted()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "Audio focus regained (Call finished). Resuming CCTV stream.")
                handleCallEnded()
            }
        }
    }

    // Camera availability callback: triggered if another app (e.g. WhatsApp video call) accesses the camera
    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            super.onCameraUnavailable(cameraId)
            Log.d(TAG, "Camera $cameraId unavailable (possibly opened by WhatsApp/IMO video call)")
            val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
            if (mode != AudioManager.MODE_NORMAL) {
                handleCallStarted()
            }
        }

        override fun onCameraAvailable(cameraId: String) {
            super.onCameraAvailable(cameraId)
            Log.d(TAG, "Camera $cameraId available again")
            if (isCallInProgress) {
                val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
                if (mode == AudioManager.MODE_NORMAL) {
                    handleCallEnded()
                }
            }
        }
    }

    // Broadcast receiver for GSM cellular calls
    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(TelephonyManager.EXTRA_STATE)
            Log.d(TAG, "Telephony state: $state")
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING,
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    Log.i(TAG, "Cellular call ringing or connected. Pausing stream.")
                    handleCallStarted()
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    Log.i(TAG, "Cellular call ended.")
                    handleCallEnded()
                }
            }
        }
    }

    fun startMonitoring(scope: CoroutineScope) {
        if (isMonitoring) return
        isMonitoring = true
        Log.d(TAG, "Starting call & VoIP zero-disturbance monitoring")

        // 1. Request transient audio focus with listener so WhatsApp/IMO triggers AUDIOFOCUS_LOSS
        requestAudioFocus()

        // 2. Register camera availability callback
        try {
            cameraManager?.registerAvailabilityCallback(cameraAvailabilityCallback, mainHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register camera availability callback: ${e.message}")
        }

        // 3. Register phone state receiver
        try {
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            context.registerReceiver(phoneStateReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register phone state receiver: ${e.message}")
        }

        // 4. Background polling of AudioManager.mode every 500ms
        // WhatsApp & IMO switch audioManager.mode to MODE_IN_COMMUNICATION (3) or MODE_RINGTONE (2)
        monitorJob = scope.launch(Dispatchers.IO) {
            while (isActive && isMonitoring) {
                delay(500)
                val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
                val isVoipOrCallActive = (mode == AudioManager.MODE_IN_COMMUNICATION ||
                                          mode == AudioManager.MODE_IN_CALL ||
                                          mode == AudioManager.MODE_RINGTONE)

                if (isVoipOrCallActive && !isCallInProgress) {
                    Log.i(TAG, "Active call/VoIP detected (audioManager.mode=$mode). Pausing camera & mic.")
                    withContext(Dispatchers.Main) {
                        handleCallStarted()
                    }
                } else if (!isVoipOrCallActive && isCallInProgress) {
                    delay(700) // Brief grace period for mode transition
                    val currentMode = audioManager?.mode ?: AudioManager.MODE_NORMAL
                    if (currentMode == AudioManager.MODE_NORMAL) {
                        Log.i(TAG, "Call finished (audioManager.mode returned to NORMAL). Resuming stream.")
                        withContext(Dispatchers.Main) {
                            handleCallEnded()
                        }
                    }
                }
            }
        }
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(playbackAttrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
                    .build()

                am.requestAudioFocus(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error requesting audio focus: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusChangeListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning audio focus: ${e.message}")
        }
    }

    @Synchronized
    private fun handleCallStarted() {
        if (isCallInProgress) return
        isCallInProgress = true
        Log.i(TAG, "Zero-disturbance mode activated: pausing camera & mic for incoming/active call")
        try {
            onCallStarted()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCallStarted callback", e)
        }
    }

    @Synchronized
    private fun handleCallEnded() {
        if (!isCallInProgress) return
        isCallInProgress = false
        Log.i(TAG, "Call ended: restoring audio focus and resuming stream")
        requestAudioFocus()
        try {
            onCallEnded()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCallEnded callback", e)
        }
    }

    fun stopMonitoring() {
        isMonitoring = false
        monitorJob?.cancel()
        monitorJob = null
        try {
            cameraManager?.unregisterAvailabilityCallback(cameraAvailabilityCallback)
        } catch (_: Exception) {}
        try {
            context.unregisterReceiver(phoneStateReceiver)
        } catch (_: Exception) {}
        abandonAudioFocus()
    }
}
