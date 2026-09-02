package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.camera.CctvForegroundService

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CctvBootReceiver"
        const val PREFS_NAME = "cctv_app_prefs"
        const val KEY_CAMERA_MODE_ACTIVE = "camera_mode_active"
        const val KEY_ROOM_PIN = "saved_room_pin"
        const val KEY_CAM_ID = "saved_cam_id"
        const val KEY_SECURITY_LOCK_PIN = "security_lock_pin"
        const val KEY_SCREEN_LOCKED = "is_screen_locked"
        const val KEY_VOICE_FILTER_ENABLED = "voice_filter_enabled"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.d(TAG, "BootReceiver triggered with action: $action")
        
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == "android.intent.action.QUICKBOOT_POWERON" || 
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isCameraActive = prefs.getBoolean(KEY_CAMERA_MODE_ACTIVE, false)
            val roomPin = prefs.getString(KEY_ROOM_PIN, null)
            val camId = prefs.getString(KEY_CAM_ID, null)

            if (isCameraActive && !roomPin.isNullOrBlank()) {
                Log.d(TAG, "Auto-restarting CCTV Camera Foreground Service for Room PIN: $roomPin")
                CctvForegroundService.startService(
                    context = context,
                    roomPin = roomPin,
                    camId = camId ?: "CAM"
                )
            }
        }
    }
}
