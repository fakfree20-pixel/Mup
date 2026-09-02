import org.webrtc.audio.JavaAudioDeviceModule
import android.media.AudioAttributes

fun test(builder: JavaAudioDeviceModule.Builder) {
    val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    builder.setAudioAttributes(attrs)
}
