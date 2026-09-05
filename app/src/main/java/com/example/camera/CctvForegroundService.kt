package com.example.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.receiver.BootReceiver
import com.example.ui.viewmodel.CctvViewModel
import com.example.webrtc.WebRtcSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground Service that keeps CCTV video streaming, WebRTC, audio recording,
 * and background connection alive 24/7 even when the user switches apps
 * or turns off the screen or restarts the device.
 */
class CctvForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "CctvForegroundService"
        const val CHANNEL_ID = "cctv_background_stream_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.cctv.START_BACKGROUND_STREAM"
        const val ACTION_STOP = "com.example.cctv.STOP_BACKGROUND_STREAM"
        const val EXTRA_ROOM_PIN = "extra_room_pin"
        const val EXTRA_CAM_ID = "extra_cam_id"

        fun startService(context: Context, roomPin: String, camId: String) {
            val intent = Intent(context, CctvForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ROOM_PIN, roomPin)
                putExtra(EXTRA_CAM_ID, camId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting foreground service", e)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CctvForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping foreground service", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)

        if (intent?.action == ACTION_STOP) {
            prefs.edit().putBoolean(BootReceiver.KEY_CAMERA_MODE_ACTIVE, false).apply()
            CctvViewModel.cameraWebRtcSessionInstance?.release()
            CctvViewModel.cameraWebRtcSessionInstance = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val roomPin = intent?.getStringExtra(EXTRA_ROOM_PIN) 
            ?: prefs.getString(BootReceiver.KEY_ROOM_PIN, null) 
            ?: "ACTIVE"
        val camId = intent?.getStringExtra(EXTRA_CAM_ID) 
            ?: prefs.getString(BootReceiver.KEY_CAM_ID, null) 
            ?: "CAM"

        // Persist camera mode as active
        prefs.edit()
            .putBoolean(BootReceiver.KEY_CAMERA_MODE_ACTIVE, true)
            .putString(BootReceiver.KEY_ROOM_PIN, roomPin)
            .putString(BootReceiver.KEY_CAM_ID, camId)
            .apply()

        // Ensure WebRTC Session is active and listening
        if (CctvViewModel.cameraWebRtcSessionInstance == null && roomPin.isNotBlank()) {
            CctvViewModel.backgroundScope.launch {
                try {
                    val session = WebRtcSessionManager(
                        context = applicationContext,
                        isCameraMode = true
                    ).apply {
                        startSession(
                            scope = CctvViewModel.backgroundScope,
                            roomId = roomPin,
                            isFrontCamera = false
                        )
                    }
                    CctvViewModel.cameraWebRtcSessionInstance = session
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to init background WebRtcSessionManager", e)
                }
            }
        }

        val notification = buildNotification(roomPin, camId)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            try {
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } catch (e: Exception) {
                Log.w(TAG, "Failed startForeground with camera type, falling back: ${e.message}")
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CctvCamera::StreamingWakeLock"
            )?.apply {
                acquire(24 * 60 * 60 * 1000L) // 24 hours max
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock", e)
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CCTV Background Stream",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps CCTV Camera Streaming in Background 24/7"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(roomPin: String, camId: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CctvForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 CCTV Camera Active 24/7")
            .setContentText("Room PIN: $roomPin • Background Ready")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_view, "Open App", openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Stream", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }
}
