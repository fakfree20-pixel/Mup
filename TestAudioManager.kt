import android.media.AudioManager
import android.content.Context

fun forceSpeaker(context: Context, on: Boolean) {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    am.mode = AudioManager.MODE_IN_COMMUNICATION
    am.isSpeakerphoneOn = on
}
